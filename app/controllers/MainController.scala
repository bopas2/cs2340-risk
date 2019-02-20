package controllers

import javax.inject.Inject
import models._
import play.api.cache.Cached
import play.api.{Configuration, Logger}
import play.api.data.Form
import play.api.mvc._

import scala.collection.mutable
import scala.concurrent.ExecutionContext

class MainController @Inject()(cached: Cached,
                               cc: MessagesControllerComponents,
                               config: Configuration)
                              (implicit ec: ExecutionContext)
    extends MessagesAbstractController(cc) with SameOriginCheck {
  val logger: Logger = Logger(this.getClass)
  private val makeURL = routes.MainController.make()

  // mutable val collection
  private val lobbies: mutable.HashMap[String, Lobby] = mutable.HashMap()

  // Host HTTP calls

  /** This is the entry point for the host; this loads the page
    * at which a new game is made
    */
  // GET /
  def index: EssentialAction = cached("indexPage") {
    Action {
      implicit request =>
        // send landing page to the client (host)
        Ok(views.html.index(Resources.UserForm, Resources.Colors, makeURL))
    }
  }

  // POST /lobby/make
  def make: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    val formValidationResult: Form[UserData] = Resources.UserForm.bindFromRequest
    formValidationResult.fold(
      userData => {
        logger.debug(s"Form submission for $userData failed")
        // this is the bad case, where the form had validation errors.
        // show the user the form again, with the errors highlighted.
        BadRequest("Form submission failed")
      },
      userData => {
        if (userData.name.length > Player.MaxNameLength)
          BadRequest(s"Length of name ${userData.name.length} too long (max: ${Player.MaxNameLength})")
        else if (userData.colorIndex >= Resources.Colors.size || userData.colorIndex < 0)
          BadRequest(s"Color index ${userData.colorIndex} out of bounds (max: ${Resources.Colors.size})")
        else {
          val newLobby = Lobby.make(userData.name, Resources.Colors(userData.colorIndex))
          lobbies.put(newLobby.id, newLobby)
          logger.debug(s"Lobby id=${newLobby.id} created")
          Redirect(s"/lobby/host/${newLobby.id}")
        }
      }
    )
  }

  // Obtains the corresponding main page after a host has created
  // a main
  // GET /lobby/host/:id
  def host(id: String): Action[AnyContent] = Action { implicit request =>
    if (!lobbies.isDefinedAt(id))
      BadRequest(s"Invalid lobby id $id")
    else if (lobbies(id).hasHostJoined)
      BadRequest(s"Host has already joined")
    else {
      // send lobby host page to the client
      // (address should get rewritten to normal main url on the
      // front end immediately upon load)
      // TODO implement
      Ok(views.html.main(id, request.headers.get(HOST).getOrElse("*")))
    }
  }

  //This is the entry points for *non-hosts*;it gives them
  //the page responsible for them setting their name & color
  //and then joining the existing game
  // GET /lobby/:id
  def lobby(id: String): Action[AnyContent] = Action { implicit request =>
    if (!lobbies.contains(id))
      BadRequest(s"Invalid lobby id $id")
    else {
      // send main page to the client
      // TODO implement
      Ok(views.html.main(id, request.headers.get(HOST).getOrElse("*")))
    }
  }

  //ERROR HANDLING

  //Redirects user to host index page if they do /main by mistake
  def redirectIndex: Action[AnyContent] = Action {
    // redirect to landing page
    Redirect("/")
  }

  // WEB SOCKETS

  // TODO web socket here

  override def validOrigin(path: String): Boolean = config.get[Seq[String]](Resources.OriginsConfigKey)
    .exists(path.contains(_))
}

/**
  * Sourced from https://github.com/playframework/play-scala-websocket-example/blob/2.7.x/app/controllers/HomeController.scala
  * @author Will Sargent
  */
trait SameOriginCheck{
  def logger: Logger
  def sameOriginCheck(rh: RequestHeader): Boolean = {
    rh.headers.get("Origin") match {
      case Some(originValue) if validOrigin(originValue) =>
        logger.debug(s"originCheck: originValue = $originValue")
        true

      case Some(badOrigin) =>
        logger.error(s"originCheck: rejecting request because Origin header value $badOrigin is not in the same origin")
        false

      case None =>
        logger.error("originCheck: rejecting request because no Origin header found")
        false
    }
  }
  def validOrigin(origin: String): Boolean
}