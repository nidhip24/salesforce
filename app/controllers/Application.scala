package controllers

import javax.inject.Inject
import core.TriggerEvent.TriggerEvent
import core.{TriggerEvent, TriggerMetadata}
import play.api.http.{HeaderNames, HttpVerbs, Writeable}
import play.api.libs.json._
import play.api.libs.ws.WSClient
import play.api.mvc._
import utils.ForceUtil

import scala.concurrent.{ExecutionContext, Future}

class Application @Inject() (forceUtil: ForceUtil, ws: WSClient)
                            (indexView: views.html.index, appView: views.html.app)
                            (implicit ec: ExecutionContext) extends InjectedController {

  case class Error(message: String, code: Option[String] = None)
  object Error {
    implicit def jsonFormat: Format[Error] = Json.format[Error]
  }

  case class ErrorResponse(error: Error)

  object ErrorResponse {

    def fromThrowable(t: Throwable): ErrorResponse = {
      ErrorResponse(Error(t.getMessage))
    }

    implicit def jsonFormat: Format[ErrorResponse] = Json.format[ErrorResponse]
  }

  implicit def jsWriteable[A](implicit wa: Writes[A], wjs: Writeable[JsValue]): Writeable[A] = wjs.map(Json.toJson(_))


  def index = Action { request =>
    if (request.session.get("oauthAccessToken").isDefined && request.session.get("env").isDefined) {
      Redirect(routes.Application.app())
    }
    else {
      Ok(indexView(request))
    }
  }

  def logout = Action {
    Redirect(routes.Application.index).withNewSession
  }

  def getSobjects = ConnectionAction.async { request =>
    val debug = request.session.get("debug").flatMap(_.toBooleanOption).getOrElse(false)

    forceUtil.getSobjects(request.env, request.sessionId, debug).map { sobjects =>
      val triggerables = sobjects.filter(_.\("triggerable").asOpt[Boolean].contains(true)).map(_.\("name").as[String])
      if (triggerables.isEmpty) {
        BadRequest(ErrorResponse(Error("No triggerable SObjects found")))
      }
      else {
        Ok(triggerables)
      }
    } recover {
      case e: Exception => BadRequest(ErrorResponse(Error(e.getMessage)))
    }
  }

  def getWebhooks = ConnectionAction.async { request =>
    forceUtil.getApexTriggers(request.env, request.sessionId).map { triggers =>
      val rawWebhooks = triggers.filter(_.\("Name").as[String].endsWith("WebhookTrigger"))

      val webhooks = rawWebhooks.map { webhook =>
        val name = (webhook \ "Name").as[String]
        val body = (webhook \ "Body").as[String]
        val firstLine = body.linesIterator.next()
        val sobject = firstLine.split(" ")(3)
        val eventsString = firstLine.substring(firstLine.indexOf("(") + 1, firstLine.indexOf(")"))
        val events: List[TriggerEvent] = eventsString.split(",").map(TriggerEvent.withName).toList
        val url = body.substring(body.indexOf("String url = '") + 14, body.indexOf("';"))
        TriggerMetadata(name, sobject, events, url)
      }

      Ok(webhooks)
    } recover {
      case e: Exception => BadRequest(ErrorResponse(Error(e.getMessage)))
    }
  }

  def createWebhook = ConnectionAction.async(parse.json) { request =>

    val maybeTriggerMetadata = request.body.asOpt[TriggerMetadata]

    maybeTriggerMetadata.fold(Future.successful(BadRequest(ErrorResponse(Error("Missing required fields"))))) { triggerMetadata =>

      // gonna do these sequentially because the apex is dependent
      val webhookCreateFuture = for {
        webhookCreate <- forceUtil.createApexClass(request.env, request.sessionId, "Webhook", apextemplates.classes.txt.Webhook().body).recover {
          // ignore failure due to duplicate
          // todo: update
          case e: forceUtil.DuplicateException => Json.obj()
        }

        remoteSiteSettingCreate <- forceUtil.createRemoteSite(request.env, request.sessionId, triggerMetadata.name + "RemoteSiteSetting", triggerMetadata.url)

        triggerBody = apextemplates.triggers.txt.WebhookTrigger(triggerMetadata.name, triggerMetadata.sobject, triggerMetadata.events.map(_.toString), triggerMetadata.url).body
        triggerCreate <- forceUtil.createApexTrigger(request.env, request.sessionId, triggerMetadata.name, triggerBody, triggerMetadata.sobject)

        triggerTestBody = apextemplates.classes.txt.TriggerTest(triggerMetadata.name, triggerMetadata.sobject, triggerMetadata.events.map(_.toString), triggerMetadata.url).body
        triggerTestCreate <- forceUtil.createApexClass(request.env, request.sessionId, triggerMetadata.name, triggerTestBody)
      } yield (webhookCreate, remoteSiteSettingCreate, triggerCreate, triggerTestCreate)

      webhookCreateFuture.map(_ => Ok(Results.EmptyContent())).recover {
        case e: Exception => BadRequest(ErrorResponse(Error(e.getMessage)))
      }
    }
  }

  def app(debug: Boolean) = ConnectionAction { implicit request =>
    Ok(appView(request)).addingToSession("debug" -> debug.toString)
  }

  def oauthCallback(code: String, env: String) = Action.async { implicit request =>
    val url = forceUtil.tokenUrl(env)

    val wsFuture = ws.url(url).withQueryStringParameters(
      "grant_type" -> "authorization_code",
      "client_id" -> forceUtil.consumerKey,
      "client_secret" -> forceUtil.consumerSecret,
      "redirect_uri" -> forceUtil.redirectUri,
      "code" -> code
    ).execute(HttpVerbs.POST)

    wsFuture.map { response =>

      val maybeAppResponse = for {
        accessToken <- (response.json \ "access_token").asOpt[String]
        // todo? instanceUrl <- (response.json \ "instance_url").asOpt[String]
      } yield {
        Redirect(routes.Application.app()).withSession("oauthAccessToken" -> accessToken, "env" -> env)
      }

      maybeAppResponse.getOrElse(Redirect(routes.Application.index).flashing("error" -> "Could not authenticate"))
    }
  }


  class ConnectionRequest[A](val sessionId: String, val env: String, request: Request[A]) extends WrappedRequest[A](request)

  def ConnectionAction = new ActionBuilder[ConnectionRequest, AnyContent] with ActionRefiner[Request, ConnectionRequest] {
    override protected def executionContext: ExecutionContext = ec

    override def parser: BodyParser[AnyContent] = parse.anyContent

    override protected def refine[A](request: Request[A]): Future[Either[Result, ConnectionRequest[A]]] = Future.successful {

      val maybeSessionId = request.session.get("oauthAccessToken").orElse(request.headers.get(HeaderNames.AUTHORIZATION).map(_.stripPrefix("Bearer ")))
      val maybeEnv = request.session.get("env").orElse(request.headers.get("Env"))

      val maybeSessionIdAndEnv = for {
        sessionId <- maybeSessionId
        env <- maybeEnv
      } yield new ConnectionRequest(sessionId, env, request)

      maybeSessionIdAndEnv.toRight(Redirect(routes.Application.index).withNewSession)
    }
  }

}
