package game.mode.skirmish

import actors.PlayerWithActor
import common.{Impure, Pure, Resources, Util}
import controllers._
import game.GameContext
import game.mode.skirmish.SkirmishGameContext._
import game.state._
import models.{Army, NeutralPlayer}

import scala.collection.mutable.ArrayBuffer
import scala.math.min

/**
  * Sub-object of SkirmishGameMode that handles game state progression
  * skirmish game mode
  */
object ProgressionHandler {
  /**
    * Handles an incoming packet, processing the current game state using it and
    * potentially sending other packets out as a result
    *
    * @param packet  The incoming packet from the network to process
    * @param context Incoming context wrapping current game state
    * @param sender  The player actor that initiated the request
    * @return The updated GameContext wrapping the updated state
    */
  @Impure.Nondeterministic
  def handle(packet: InGamePacket)
            (implicit context: GameContext, sender: PlayerWithActor): GameContext =
      packet match {
        case RequestPlaceReinforcements(_, _, assignments) => requestPlaceReinforcements(assignments)
        case RequestAttack(_, _, attack)                   => requestAttack(attack)
        case DefenseResponse(_, _, defenders)              => defenseResponse(defenders)
        case RequestEndTurn(_, _)                          => requestEndTurn
      }

  /**
    * Handles incoming request place reinforcements packet. After successful
    * validation, adjusts game state as necessary and move the turn state's
    * state machine forwards
    *
    * @param assignments The proposed assignments Seq[(territory index -> amount)]
    * @param context     Incoming context wrapping current game state
    * @param sender      The player that initiated the request
    * @return An updated game context object
    */
  @Pure
  def requestPlaceReinforcements(assignments: Seq[(Int, Int)])
                                (implicit context: GameContext, sender: PlayerWithActor): GameContext = {
    // Calculate new board state with mutable buffer
    val boardBuffer: ArrayBuffer[TerritoryState] = Util.arrayBuffer(context.state.boardState)
    assignments.foreach {
      case (index, increment) => boardBuffer(index) = boardBuffer(index) add Army(increment)
    }

    // Calculate new player state
    val total = assignments.map { case (_, increment) => increment }.sum
    val playerStates = context.state.playerStates.map {
      case state@PlayerState(player, units, _) if player == sender.player =>
        state.copy(units = units + total)
      case otherState => otherState
    }

    context
      .map(gs => gs.copy(
        boardState   = boardBuffer.toIndexedSeq,
        playerStates = playerStates
      ))
      .advanceTurnState
      .thenBroadcastBoardState
      .thenBroadcastPlayerState
  }

  /**
    * Handles RequestAttack packets, updating game state as necessary
    *
    * @param attackData Incoming data from the packet
    * @param context    Incoming context wrapping current game state
    * @param sender     The player that initiated the request
    * @return An updated game context object
    */
  @Impure.Nondeterministic
  def requestAttack(attackData: Seq[Int])
                   (implicit context: GameContext, sender: PlayerWithActor): GameContext = {
    // noinspection ZeroIndexToHead
    val attack = AttackState(attackData)
    val defendingTerritory = context.state.boardState(attack.defendingIndex)
    val defendingPlayer    = defendingTerritory.owner

    defendingPlayer match {
      // Attacking neutral player, simulate attack here (nondeterministic)
      case NeutralPlayer => attackNeutralTerritory(attack)

      // Attacking other player, make them defend
      case _ =>
        context
          .map(gs => gs.copy(
            currentAttack = Some(attack)
          ))
          .advanceAttackTurnState(defendingPlayer, "attack" -> attack.unapply)
          .thenBroadcastPlayerState
    }
  }

  /**
    * Simulates the entire attack-defense phase against a neutral territory
    *
    * @param attack  The information about the attack coming from the network
    * @param context Incoming context wrapping current game state
    * @return An updated game context object
    */
  @Impure.Nondeterministic
  def attackNeutralTerritory(attack: AttackState)(implicit context: GameContext): GameContext = {
    val defendingTerritory = context.state.boardState(attack.defendingIndex)
    val defendingPlayer    = defendingTerritory.owner
    val defenders = defendingTerritory.army.size match {
      case d if d > 2 => 2
      case d          => d
    }

    val contextBefore = context
      .map(gs => gs.copy(
        currentAttack = Some(attack)
      ))
      .advanceAttackTurnState(defendingPlayer, "attack" -> attack.unapply)

    // Calculate result with temporary modified game context/state (nondeterministic)
    val result = attackResult(attack.attackAmount, defenders)(contextBefore)

    processAttackResult(result)(contextBefore)
      .map(gs => gs.copy(
        currentAttack = None
      ))
      .advanceAttackTurnState(defendingPlayer,
        "attack" -> (attack.unapply ++ Seq(defenders)),
        "result" -> result.unapply)
      .thenBroadcastBoardState
      .thenBroadcastPlayerState
  }

  /**
    * Wrapper class for the result of an attack
    *
    * @param diceRolls          The resulting dice rolls
    * @param attackersDestroyed The number of attacking troops destroyed
    * @param defendersDestroyed The number of defending troops destroyed
    */
  case class AttackResult(diceRolls: Seq[Int], attackersDestroyed: Int, defendersDestroyed: Int) {
    @Pure
    def unapply: (Seq[Int], Int, Int) = (this.diceRolls, this.attackersDestroyed, this.defendersDestroyed)
  }


  /**
    * Calculates the result of an attack, simulating the dice rolls and saving their result
    *
    * @param attackers The number of attacking troops to use
    * @param defenders The number of defending troops to use
    * @param context   Incoming context wrapping current game state
    * @return The result of the attack, contained in a wrapper object
    */
  @Impure.Nondeterministic
  def attackResult(attackers: Int, defenders: Int)(implicit context: GameContext): AttackResult = {
    val faces = Resources.DiceFaces
    val attackerResult = (for (_ <- 1 to attackers) yield 1 + scala.util.Random.nextInt(faces)).sorted
    val defenderResult = (for (_ <- 1 to defenders) yield 1 + scala.util.Random.nextInt(faces)).sorted

    var attackersDestroyed: Int = 0
    var defendersDestroyed: Int = 0
    for (i <- 0 until min(attackers, defenders)) {
      if (attackerResult(i) <= defenderResult(i)) {
        attackersDestroyed += 1
      } else if (attackerResult(i) > defenderResult(i)) {
        defendersDestroyed += 1
      }
    }

    AttackResult(attackerResult ++ defenderResult, attackersDestroyed, defendersDestroyed)
  }

  /**
    * Processes the result of an attack and applies the changes to the board state
    *
    * @param result  The attack result wrapper object
    * @param context Incoming context wrapping current game state
    * @return An updated game context object
    */
  @Pure
  def processAttackResult(result: AttackResult)(implicit context: GameContext): GameContext = {
    context.state.currentAttack match {
      case Some(AttackState(attackingIndex, defendingIndex, _)) =>
        val attackingArmy = context.state.boardState(attackingIndex)
        val defendingArmy = context.state.boardState(defendingIndex)

        // Update board state
        val boardStateBuffer = Util.arrayBuffer(context.state.boardState)
        boardStateBuffer(attackingIndex) = attackingArmy add Army(-result.attackersDestroyed)
        boardStateBuffer(defendingIndex) = defendingArmy add Army(-result.defendersDestroyed)

        // If defending territory became empty
        if (boardStateBuffer(defendingIndex).size == 0) {
          boardStateBuffer(attackingIndex) = boardStateBuffer(attackingIndex) add Army(-1)
          boardStateBuffer(defendingIndex) = TerritoryState(1, attackingArmy.owner)
        }

        context.map(gs => gs.copy(
          boardState = boardStateBuffer.toIndexedSeq
        ))

      case None => context // pass
    }
  }

  /**
    * Handles a DefenseResponse packet and simulates the dice roll battle
    *
    * @param defenders The number of defenders committed (from the packet)
    * @param context   Incoming context wrapping current game state
    * @param sender    The player that initiated the request
    * @return An updated game context object
    */
  @Impure.Nondeterministic
  def defenseResponse(defenders: Int)
                     (implicit context: GameContext, sender: PlayerWithActor): GameContext = {
    context.state.currentAttack match {
      case Some(currentAttack@AttackState(_, _, attackAmount)) =>
        val result = attackResult(attackAmount, defenders)
        val attackData = currentAttack.unapply ++ Seq(defenders)

        processAttackResult(result)
          .map(gs => gs.copy(
            currentAttack = None
          ))
          .advanceAttackTurnState(sender.player,
            "attack" -> attackData,
            "result" -> result.unapply)
          .thenBroadcastBoardState
          .thenBroadcastPlayerState

      case None => context // pass
    }
  }

  /**
    * Handles a RequestEndTurn packet coming in from the network and advances turn
    * state as appropriate
    *
    * @param context Incoming context wrapping current game state
    * @param sender  The player that initiated the request
    * @return An updated game context object
    */
  @Pure
  def requestEndTurn(implicit context: GameContext, sender: PlayerWithActor): GameContext =
    // Update turn state twice to skip maneuver phase
    context
      .advanceTurnState
      .advanceTurnState
      .thenBroadcastBoardState
      .thenBroadcastPlayerState
}