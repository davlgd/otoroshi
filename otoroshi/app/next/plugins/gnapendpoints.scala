package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

// ---------------------------------------------------------------------------
// GNAP Authorization Server — unified NgRequestSink
// Handles grant, continue, interaction, token management, user code, and
// discovery endpoints in a single plugin (RFC 9635).
// ---------------------------------------------------------------------------

class GnapAuthorizationServer extends NgRequestSink {

  private val logger = play.api.Logger("otoroshi-plugins-gnap-as")

  override def name: String                                = "GNAP Authorization Server"
  override def description: Option[String]                 =
    "Exposes the GNAP authorization server endpoints (RFC 9635): grant negotiation, continuation, user interaction, token management, user code, and discovery — all in a single plugin".some
  override def defaultConfigObject: Option[NgPluginConfig] = GnapPluginConfig.default.some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.Sink)
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = GnapPluginConfig.configFlow
  override def configSchema: Option[JsObject]              = GnapPluginConfig.configSchema.some

  override def matches(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val conf          = ctx.config.asOpt(GnapPluginConfig.format).getOrElse(GnapPluginConfig.default)
    val domainMatches = conf.domain match {
      case "*"   => true
      case value => ctx.request.theDomain == value
    }
    if (!domainMatches || ctx.origin != NgRequestOrigin.NgReverseProxy) return false

    val uri    = ctx.request.relativeUri
    val method = ctx.request.method

    // Grant endpoint (POST)
    (method == "POST" && uri == conf.grantEndpointPath) ||
      // Continue endpoint (POST + DELETE)
      ((method == "POST" || method == "DELETE") && uri.startsWith(conf.continueEndpointPath + "/")) ||
      // Interaction endpoint (GET + POST)
      uri.startsWith(conf.interactionEndpointPath + "/") ||
      // Token management endpoint (POST + DELETE)
      ((method == "POST" || method == "DELETE") && uri.startsWith(conf.tokenManagementPath + "/")) ||
      // User code endpoint (GET + POST)
      uri.startsWith(conf.userCodePath) ||
      // Discovery document (GET)
      (method == "GET" && uri == conf.discoveryPath)
  }

  override def handle(ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat: Materializer = env.otoroshiMaterializer
    val conf = ctx.config.asOpt(GnapPluginConfig.format).getOrElse(GnapPluginConfig.default)
    val uri  = ctx.request.relativeUri

    if (uri == conf.discoveryPath) {
      handleDiscovery(ctx, conf)
    } else if (uri == conf.grantEndpointPath) {
      handleGrant(ctx, conf)
    } else if (uri.startsWith(conf.continueEndpointPath + "/")) {
      if (ctx.request.method == "DELETE") handleGrantCancel(ctx, conf)
      else handleContinue(ctx, conf)
    } else if (uri.startsWith(conf.interactionEndpointPath + "/")) {
      handleInteraction(ctx, conf)
    } else if (uri.startsWith(conf.tokenManagementPath + "/")) {
      handleTokenManagement(ctx, conf)
    } else if (uri.startsWith(conf.userCodePath)) {
      handleUserCode(ctx, conf)
    } else {
      Future.successful(Results.NotFound(Json.obj("error" -> "not_found")).as("application/json"))
    }
  }

  // -------------------------------------------------------------------------
  // Discovery document (GET /.well-known/gnap-as-rs) — RFC 9635 Section 9
  // -------------------------------------------------------------------------

  private def handleDiscovery(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val baseUrl = s"${ctx.request.theProtocol}://${ctx.request.host}"
    Future.successful(Results.Ok(
      GnapResponse.discoveryDocument(
        grantEndpoint = s"$baseUrl${conf.grantEndpointPath}",
        tokenManagementEndpoint = s"$baseUrl${conf.tokenManagementPath}",
        interactionStartModes = Seq("redirect", "user_code"),
        interactionFinishMethods = Seq("redirect", "push")
      )
    ).as("application/json"))
  }

  // -------------------------------------------------------------------------
  // Grant endpoint (POST /.well-known/gnap/tx) — RFC 9635 Section 2
  // -------------------------------------------------------------------------

  private def handleGrant(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    if (conf.secure && !ctx.request.theSecured) {
      return Future.successful(
        GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "HTTPS required")
      )
    }

    conf.biscuitPrivkey match {
      case None =>
        Future.successful(
          GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, "GNAP AS not configured: missing biscuit_privkey")
        )
      case Some(privkey) =>
        ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          val contentType = ctx.request.headers.get("Content-Type").getOrElse("")
          if (!contentType.toLowerCase.contains("application/json")) {
            Future.successful(
              GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "Content-Type must be application/json")
            )
          } else {
            Try(Json.parse(bodyRaw.utf8String)) match {
              case Failure(_) =>
                Future.successful(
                  GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "Invalid JSON body")
                )
              case Success(json) =>
                json.validate[GnapGrantRequest] match {
                  case JsError(errors) =>
                    Future.successful(
                      GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, s"Invalid grant request: ${errors.flatMap(_._2).map(_.message).mkString(", ")}")
                    )
                  case JsSuccess(grantRequest, _) =>
                    GnapRequestValidator.validate(grantRequest) match {
                      case Left(err) =>
                        Future.successful(GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, err))
                      case Right(_) =>
                        processGrantRequest(grantRequest, conf, privkey, ctx)
                    }
                }
            }
          }
        }
    }
  }

  private def processGrantRequest(
      req: GnapGrantRequest,
      conf: GnapPluginConfig,
      privkey: String,
      ctx: NgRequestSinkContext
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {

    val grantId       = IdGenerator.uuid
    val continueToken = GnapCrypto.generateNonce()
    val tokenHash     = GnapCrypto.hashToken(continueToken)
    val now           = System.currentTimeMillis()
    val baseUrl       = s"${ctx.request.theProtocol}://${ctx.request.host}"
    val interactionRequested = req.interact.isDefined

    if (interactionRequested) {
      val interactRef = GnapCrypto.generateNonce(16)
      val serverNonce = GnapCrypto.generateNonce(16)
      val startModes  = req.interact.map(_.start).getOrElse(Seq.empty)

      val grant = GnapGrant(
        grantId = grantId,
        state = GnapGrantState.Pending,
        clientInstance = req.client,
        requestedAccess = allAccessRights(req),
        grantedAccess = Seq.empty,
        interactRef = Some(interactRef),
        clientNonce = req.interact.flatMap(_.finish.map(_.nonce)),
        serverNonce = Some(serverNonce),
        finishUri = req.interact.flatMap(_.finish.map(_.uri)),
        finishMethod = req.interact.flatMap(_.finish.map(_.method)),
        hashMethod = req.interact.flatMap(_.finish.flatMap(_.hashMethod)),
        subject = None,
        continueTokenHash = tokenHash,
        biscuitRevocationId = None,
        createdAt = now,
        expiresAt = now + (conf.grantTtlSeconds * 1000L),
        tokenExpiresAt = now + (conf.tokenTtlSeconds * 1000L)
      )

      // Build interaction response based on requested start modes
      val continueUri = s"$baseUrl${conf.continueEndpointPath}/$grantId"
      var interactObj = Json.obj()

      if (startModes.contains("redirect")) {
        interactObj = interactObj ++ Json.obj("redirect" -> s"$baseUrl${conf.interactionEndpointPath}/$interactRef")
      }

      // User code support (RFC 9635 Section 2.5.1.4)
      val userCodeFuture: Future[Option[String]] = if (startModes.contains("user_code")) {
        val code = GnapCrypto.generateUserCode(conf.userCodeLength)
        GnapDatastore.storeUserCode(code, grantId, conf.grantTtlSeconds).map(_ => Some(code))
      } else {
        Future.successful(None)
      }

      userCodeFuture.flatMap { maybeCode =>
        maybeCode.foreach { code =>
          interactObj = interactObj ++ Json.obj(
            "user_code" -> Json.obj(
              "code" -> code,
              "url"  -> s"$baseUrl${conf.userCodePath}"
            )
          )
        }

        // Add finish nonce
        grant.serverNonce.foreach { sn =>
          interactObj = interactObj ++ Json.obj("finish" -> sn)
        }

        GnapDatastore.storeGrant(grant, conf.grantTtlSeconds).map { _ =>
          val response = Json.obj(
            "continue" -> Json.obj(
              "access_token" -> Json.obj("value" -> continueToken),
              "uri"          -> continueUri,
              "wait"         -> 30
            ),
            "interact" -> interactObj
          )
          Results.Ok(response).as("application/json")
        }
      }
    } else if (conf.autoApproveM2m) {
      emitTokensForGrant(req, grantId, Seq.empty, None, privkey, conf, ctx, tokenHash, now)
    } else {
      Future.successful(
        GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "M2M auto-approval is disabled; interaction is required")
      )
    }
  }

  // -------------------------------------------------------------------------
  // Continue endpoint (POST /.well-known/gnap/continue/:grantId) — §5
  // -------------------------------------------------------------------------

  private def handleContinue(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val contentType = ctx.request.headers.get("Content-Type").getOrElse("")
    if (!contentType.toLowerCase.contains("application/json")) {
      return Future.successful(
        GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "Content-Type must be application/json")
      )
    }

    val authHeader = ctx.request.headers.get("Authorization")
    val continueToken = authHeader.filter(_.startsWith("GNAP ")).map(_.stripPrefix("GNAP "))

    continueToken match {
      case None =>
        Future.successful(
          GnapResponse.errorResult(Results.Unauthorized, GnapErrorCode.InvalidContinuation, "Missing Authorization: GNAP <token> header")
        )
      case Some(token) =>
        val tokenHash = GnapCrypto.hashToken(token)
        GnapDatastore.loadGrantByContinueToken(tokenHash).flatMap {
          case None =>
            Future.successful(
              GnapResponse.errorResult(Results.NotFound, GnapErrorCode.InvalidContinuation, "Grant not found or expired")
            )
          case Some(grant) if grant.state == GnapGrantState.Denied =>
            Future.successful(GnapResponse.errorResult(Results.Forbidden, GnapErrorCode.UserDenied, "Grant was denied"))
          case Some(grant) if grant.state == GnapGrantState.Finalized =>
            Future.successful(
              GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidContinuation, "Grant already finalized")
            )
          case Some(grant) if grant.state == GnapGrantState.Pending =>
            ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
              val bodyJson = Try(Json.parse(bodyRaw.utf8String)).getOrElse(Json.obj())
              val interactRef = (bodyJson \ "interact_ref").asOpt[String]

              interactRef match {
                case None =>
                  // Polling — rotate continue token (RFC 9635 Section 5.1)
                  val newToken     = GnapCrypto.generateNonce()
                  val newTokenHash = GnapCrypto.hashToken(newToken)
                  val rotated      = grant.copy(continueTokenHash = newTokenHash)
                  GnapDatastore.storeGrant(rotated, conf.grantTtlSeconds).map { _ =>
                    val baseUrl = s"${ctx.request.theProtocol}://${ctx.request.host}"
                    Results.Ok(Json.obj("continue" -> Json.obj(
                      "access_token" -> Json.obj("value" -> newToken),
                      "uri"          -> s"$baseUrl${conf.continueEndpointPath}/${grant.grantId}",
                      "wait"         -> 30
                    ))).as("application/json")
                  }
                case Some(_) =>
                  Future.successful(
                    GnapResponse.errorResult(Results.Accepted, GnapErrorCode.InvalidInteraction, "Grant is still pending approval")
                  )
              }
            }
          case Some(grant) if grant.state == GnapGrantState.Approved =>
            ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
              val bodyJson    = Try(Json.parse(bodyRaw.utf8String)).getOrElse(Json.obj())
              val interactRef = (bodyJson \ "interact_ref").asOpt[String]

              (interactRef, grant.interactRef) match {
                case (Some(ref), Some(storedRef)) if ref != storedRef =>
                  Future.successful(
                    GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidInteraction, "interact_ref mismatch")
                  )
                case (None, Some(_)) =>
                  Future.successful(
                    GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidInteraction, "interact_ref is required")
                  )
                case _ =>
                  conf.biscuitPrivkey match {
                    case None =>
                      Future.successful(
                        GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, "GNAP AS not configured")
                      )
                    case Some(privkey) =>
                      val access = if (grant.grantedAccess.nonEmpty) grant.grantedAccess else grant.requestedAccess
                      val tokenId = IdGenerator.uuid
                      GnapBiscuitEmitter.emitToken(access, grant.subject, grant.grantId, privkey, conf.tokenTtlSeconds) match {
                        case Left(_) =>
                          Future.successful(
                            GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, "Token emission failed")
                          )
                        case Right((tokenB64, revocationId)) =>
                          val finalized = grant.copy(
                            state = GnapGrantState.Finalized,
                            biscuitRevocationId = Some(revocationId),
                            tokenIds = Seq(tokenId)
                          )
                          val baseUrl   = s"${ctx.request.theProtocol}://${ctx.request.host}"
                          val manageUrl = s"$baseUrl${conf.tokenManagementPath}/$tokenId"
                          for {
                            _ <- GnapDatastore.storeGrant(finalized, conf.tokenTtlSeconds)
                            _ <- GnapDatastore.storeTokenRef(tokenId, grant.grantId, revocationId, access, conf.tokenTtlSeconds)
                          } yield {
                            Results.Ok(GnapResponse.tokenResponseWithSubject(
                              tokenB64, access, conf.tokenTtlSeconds,
                              manageUrl = Some(manageUrl),
                              subject = grant.subject
                            )).as("application/json")
                          }
                      }
                  }
              }
            }
          case Some(_) =>
            Future.successful(
              GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidContinuation, "Invalid grant state")
            )
        }
    }
  }

  // -------------------------------------------------------------------------
  // Grant cancellation (DELETE /.well-known/gnap/continue/:grantId) — §5.4
  // -------------------------------------------------------------------------

  private def handleGrantCancel(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val authHeader    = ctx.request.headers.get("Authorization")
    val continueToken = authHeader.filter(_.startsWith("GNAP ")).map(_.stripPrefix("GNAP "))

    continueToken match {
      case None =>
        Future.successful(
          GnapResponse.errorResult(Results.Unauthorized, GnapErrorCode.InvalidContinuation, "Missing Authorization: GNAP <token> header")
        )
      case Some(token) =>
        val tokenHash = GnapCrypto.hashToken(token)
        GnapDatastore.loadGrantByContinueToken(tokenHash).flatMap {
          case None =>
            Future.successful(
              GnapResponse.errorResult(Results.NotFound, GnapErrorCode.InvalidContinuation, "Grant not found or expired")
            )
          case Some(grant) =>
            // Revoke any issued tokens
            val revokeFutures = grant.biscuitRevocationId.map(rid => GnapDatastore.revoke(rid)).toSeq ++
              grant.tokenIds.map(tid => GnapDatastore.deleteTokenRef(tid))
            for {
              _ <- Future.sequence(revokeFutures)
              _ <- GnapDatastore.deleteGrant(grant)
            } yield {
              Results.NoContent
            }
        }
    }
  }

  // -------------------------------------------------------------------------
  // Token management (POST/DELETE /.well-known/gnap/token/:tokenId) — §6
  // -------------------------------------------------------------------------

  private def handleTokenManagement(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val tokenId = ctx.request.relativeUri.stripPrefix(conf.tokenManagementPath + "/").takeWhile(c => c != '?' && c != '#')

    if (tokenId.isEmpty) {
      return Future.successful(
        GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "Missing token ID")
      )
    }

    // Verify the caller has a valid GNAP token
    val authHeader    = ctx.request.headers.get("Authorization")
    val gnapToken     = authHeader.filter(_.startsWith("GNAP ")).map(_.stripPrefix("GNAP "))

    if (gnapToken.isEmpty) {
      return Future.successful(
        GnapResponse.errorResult(Results.Unauthorized, GnapErrorCode.InvalidRequest, "Missing Authorization: GNAP <token> header")
      )
    }

    GnapDatastore.loadTokenRef(tokenId).flatMap {
      case None =>
        Future.successful(
          GnapResponse.errorResult(Results.NotFound, GnapErrorCode.InvalidRequest, "Token not found or expired")
        )
      case Some(tokenRef) =>
        val grantId      = (tokenRef \ "grant_id").as[String]
        val revocationId = (tokenRef \ "revocation_id").as[String]
        val access       = (tokenRef \ "access").as[Seq[GnapAccessRight]]

        ctx.request.method match {
          // Token rotation (POST) — RFC 9635 Section 6.1
          case "POST" =>
            conf.biscuitPrivkey match {
              case None =>
                Future.successful(
                  GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, "GNAP AS not configured")
                )
              case Some(privkey) =>
                GnapDatastore.loadGrant(grantId).flatMap {
                  case None =>
                    Future.successful(
                      GnapResponse.errorResult(Results.NotFound, GnapErrorCode.InvalidRequest, "Associated grant not found")
                    )
                  case Some(grant) =>
                    GnapBiscuitEmitter.emitToken(access, grant.subject, grantId, privkey, conf.tokenTtlSeconds) match {
                      case Left(_) =>
                        Future.successful(
                          GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, "Token emission failed")
                        )
                      case Right((newTokenB64, newRevocationId)) =>
                        val newTokenId = IdGenerator.uuid
                        val baseUrl    = s"${ctx.request.theProtocol}://${ctx.request.host}"
                        val manageUrl  = s"$baseUrl${conf.tokenManagementPath}/$newTokenId"
                        val updatedGrant = grant.copy(
                          tokenIds = grant.tokenIds.filterNot(_ == tokenId) :+ newTokenId,
                          biscuitRevocationId = Some(newRevocationId)
                        )
                        for {
                          _ <- GnapDatastore.revoke(revocationId)
                          _ <- GnapDatastore.deleteTokenRef(tokenId)
                          _ <- GnapDatastore.storeTokenRef(newTokenId, grantId, newRevocationId, access, conf.tokenTtlSeconds)
                          _ <- GnapDatastore.storeGrant(updatedGrant, conf.tokenTtlSeconds)
                        } yield {
                          Results.Ok(GnapResponse.tokenResponse(
                            newTokenB64, access, conf.tokenTtlSeconds, manageUrl = Some(manageUrl)
                          )).as("application/json")
                        }
                    }
                }
            }

          // Token revocation (DELETE) — RFC 9635 Section 6.2
          case "DELETE" =>
            for {
              _ <- GnapDatastore.revoke(revocationId)
              _ <- GnapDatastore.deleteTokenRef(tokenId)
            } yield {
              Results.NoContent
            }

          case _ =>
            Future.successful(
              GnapResponse.errorResult(Results.MethodNotAllowed, GnapErrorCode.InvalidRequest, "Method not allowed")
            )
        }
    }
  }

  // -------------------------------------------------------------------------
  // Interaction endpoint (GET/POST /.well-known/gnap/interact/:interactRef)
  // -------------------------------------------------------------------------

  private def handleInteraction(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val interactRef = ctx.request.relativeUri.stripPrefix(conf.interactionEndpointPath + "/").takeWhile(c => c != '?' && c != '#')

    if (interactRef.isEmpty) {
      return Future.successful(
        GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidInteraction, "Missing interaction reference")
      )
    }

    GnapDatastore.loadGrantByInteractRef(interactRef).flatMap {
      case None =>
        Future.successful(
          GnapResponse.errorResult(Results.NotFound, GnapErrorCode.UnknownInteraction, "Interaction not found or expired")
        )
      case Some(grant) if grant.state != GnapGrantState.Pending =>
        Future.successful(
          GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidInteraction, "Grant is not in pending state")
        )
      case Some(grant) =>
        ctx.request.method match {
          case "GET"  => renderConsentPage(grant, interactRef, conf, ctx)
          case "POST" => processConsent(grant, interactRef, conf, ctx)
          case _      =>
            Future.successful(
              GnapResponse.errorResult(Results.MethodNotAllowed, GnapErrorCode.InvalidRequest, "Method not allowed")
            )
        }
    }
  }

  private def renderConsentPage(
      grant: GnapGrant,
      interactRef: String,
      conf: GnapPluginConfig,
      ctx: NgRequestSinkContext
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {

    // Check for authenticated user via auth module
    val userInfoFuture: Future[Option[JsObject]] = conf.authModuleRef match {
      case Some(ref) =>
        env.proxyState.authModule(ref) match {
          case None =>
            logger.warn(s"[GNAP] Auth module '$ref' not found")
            Future.successful(None)
          case Some(authConfig) =>
            val cookieSuffix = authConfig.routeCookieSuffix(null) // fallback
            // Look for any private apps session cookie
            val sessionId = ctx.request.cookies
              .filter(c => c.name.startsWith("oto-papps-"))
              .flatMap(c => env.extractPrivateSessionId(c).toSeq)
              .headOption
            sessionId match {
              case Some(sid) =>
                env.datastores.privateAppsUserDataStore.findById(sid).map(_.map { user =>
                  Json.obj(
                    "sub"   -> user.email,
                    "email" -> user.email,
                    "name"  -> user.name
                  )
                })
              case None =>
                // No session — we could redirect to login, but since we're a Sink plugin
                // without a route descriptor, we show the consent page with anonymous user.
                // The admin should protect the interaction path via an Otoroshi route for
                // production use, or use the user_code flow instead.
                Future.successful(None)
            }
        }
      case None => Future.successful(None)
    }

    userInfoFuture.flatMap { userInfo =>
      val csrfToken = GnapCrypto.generateNonce(16)
      GnapDatastore.storeCsrfToken(interactRef, csrfToken, conf.grantTtlSeconds)

      val userDisplay = userInfo match {
        case Some(u) =>
          val name  = (u \ "name").asOpt[String].getOrElse("Unknown")
          val email = (u \ "email").asOpt[String].getOrElse("")
          s"""<p class="user">Logged in as <strong>${escapeHtml(name)}</strong> (${escapeHtml(email)})</p>"""
        case None => ""
      }

      val accessDesc = grant.requestedAccess.map {
        case GnapAccessRight.Reference(ref) => s"<li>Access: <code>${escapeHtml(ref)}</code></li>"
        case GnapAccessRight.Structured(rt, actions, locations, _, _, _) =>
          val parts = Seq(
            s"Type: <strong>${escapeHtml(rt)}</strong>",
            if (actions.nonEmpty) s"Actions: ${actions.map(escapeHtml).mkString(", ")}" else "",
            if (locations.nonEmpty) s"Locations: ${locations.map(escapeHtml).mkString(", ")}" else ""
          ).filter(_.nonEmpty).mkString(" — ")
          s"<li>$parts</li>"
      }.mkString("\n")

      val html =
        s"""<!DOCTYPE html>
           |<html><head><meta charset="utf-8"><title>GNAP Authorization</title>
           |<style>
           |  body { font-family: system-ui, sans-serif; max-width: 600px; margin: 60px auto; padding: 0 20px; }
           |  .card { border: 1px solid #ddd; border-radius: 8px; padding: 24px; }
           |  h1 { font-size: 1.4em; }
           |  ul { padding-left: 20px; }
           |  .user { color: #555; font-size: 0.9em; margin-bottom: 16px; }
           |  .actions { margin-top: 20px; display: flex; gap: 12px; }
           |  button { padding: 10px 24px; border-radius: 6px; border: 1px solid #ccc; cursor: pointer; font-size: 1em; }
           |  .approve { background: #2563eb; color: white; border-color: #2563eb; }
           |  .deny { background: white; }
           |</style></head>
           |<body><div class="card">
           |  <h1>Authorization Request</h1>
           |  $userDisplay
           |  <p>An application is requesting the following access:</p>
           |  <ul>$accessDesc</ul>
           |  <form method="POST" action="${escapeHtml(conf.interactionEndpointPath)}/${escapeHtml(interactRef)}">
           |    <input type="hidden" name="csrf_token" value="${escapeHtml(csrfToken)}" />
           |    <div class="actions">
           |      <button type="submit" name="decision" value="approve" class="approve">Approve</button>
           |      <button type="submit" name="decision" value="deny" class="deny">Deny</button>
           |    </div>
           |  </form>
           |</div></body></html>""".stripMargin

      Future.successful(Results.Ok(html).as("text/html"))
    }
  }

  private def processConsent(
      grant: GnapGrant,
      interactRef: String,
      conf: GnapPluginConfig,
      ctx: NgRequestSinkContext
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat: Materializer = env.otoroshiMaterializer

    ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val bodyStr   = bodyRaw.utf8String
      val params    = play.core.parsers.FormUrlEncodedParser.parse(bodyStr, "UTF-8").map { case (k, v) => k -> v.headOption.getOrElse("") }
      val decision  = params.getOrElse("decision", "deny")
      val csrfToken = params.getOrElse("csrf_token", "")

      GnapDatastore.loadCsrfToken(interactRef).flatMap {
        case Some(storedCsrf) if storedCsrf == csrfToken => processCsrfValidatedConsent(grant, interactRef, conf, ctx, decision)
        case _ => Future.successful(GnapResponse.errorResult(Results.Forbidden, GnapErrorCode.InvalidRequest, "Invalid or missing CSRF token"))
      }
    }
  }

  private def processCsrfValidatedConsent(
      grant: GnapGrant,
      interactRef: String,
      conf: GnapPluginConfig,
      ctx: NgRequestSinkContext,
      decision: String
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {

    // Check for authenticated user to populate subject
    val userSubject: Future[Option[JsObject]] = conf.authModuleRef match {
      case Some(_) =>
        val sessionId = ctx.request.cookies
          .filter(c => c.name.startsWith("oto-papps-"))
          .flatMap(c => env.extractPrivateSessionId(c).toSeq)
          .headOption
        sessionId match {
          case Some(sid) =>
            env.datastores.privateAppsUserDataStore.findById(sid).map(_.map { user =>
              Json.obj("sub" -> user.email, "email" -> user.email, "name" -> user.name)
            })
          case None => Future.successful(None)
        }
      case None => Future.successful(None)
    }

    userSubject.flatMap { authenticatedUser =>
      if (decision == "approve") {
        val subjectInfo = authenticatedUser.orElse(
          Some(Json.obj("sub" -> s"user-${grant.grantId.take(8)}"))
        )
        val approved = grant.copy(
          state = GnapGrantState.Approved,
          grantedAccess = grant.requestedAccess,
          subject = subjectInfo
        )
        GnapDatastore.storeGrant(approved, conf.grantTtlSeconds).flatMap { _ =>
          handleInteractionFinish(approved, interactRef, conf, ctx, approved = true)
        }
      } else {
        val denied = grant.copy(state = GnapGrantState.Denied)
        GnapDatastore.storeGrant(denied, conf.grantTtlSeconds).flatMap { _ =>
          handleInteractionFinish(denied, interactRef, conf, ctx, approved = false)
        }
      }
    }
  }

  // Interaction finish: redirect or push (RFC 9635 Section 4.2)
  private def handleInteractionFinish(
      grant: GnapGrant,
      interactRef: String,
      conf: GnapPluginConfig,
      ctx: NgRequestSinkContext,
      approved: Boolean
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {

    val hashMethod = grant.hashMethod.getOrElse("sha-256")

    grant.finishUri match {
      case Some(finishUri) if approved =>
        val interactHash = (grant.clientNonce, grant.serverNonce) match {
          case (Some(cn), Some(sn)) =>
            val baseUrl = s"${ctx.request.theProtocol}://${ctx.request.host}${conf.grantEndpointPath}"
            GnapCrypto.computeInteractionHash(cn, sn, interactRef, baseUrl, hashMethod)
          case _ => ""
        }
        val separator = if (finishUri.contains("?")) "&" else "?"
        val callbackParams = s"hash=$interactHash&interact_ref=$interactRef"

        grant.finishMethod match {
          // Push-based finish (RFC 9635 Section 4.2.2)
          case Some("push") =>
            val pushUrl = s"$finishUri${separator}$callbackParams"
            env.Ws.url(pushUrl).post("").map { resp =>
              if (resp.status >= 200 && resp.status < 300) {
                Results.Ok(Json.obj("status" -> "approved", "finish" -> "pushed")).as("application/json")
              } else {
                logger.warn(s"[GNAP] Push finish callback failed: ${resp.status}")
                Results.Ok(Json.obj("status" -> "approved", "finish" -> "push_failed")).as("application/json")
              }
            }.recover { case e =>
              logger.error(s"[GNAP] Push finish callback error: ${e.getMessage}")
              Results.Ok(Json.obj("status" -> "approved", "finish" -> "push_failed")).as("application/json")
            }

          // Redirect-based finish (default, RFC 9635 Section 4.2.1)
          case _ =>
            Future.successful(Results.Redirect(s"$finishUri${separator}$callbackParams"))
        }

      case Some(finishUri) if !approved =>
        val separator = if (finishUri.contains("?")) "&" else "?"
        grant.finishMethod match {
          case Some("push") =>
            env.Ws.url(s"$finishUri${separator}error=user_denied").post("").map { _ =>
              Results.Ok(Json.obj("status" -> "denied", "finish" -> "pushed")).as("application/json")
            }.recover { case _ =>
              Results.Ok(Json.obj("status" -> "denied", "finish" -> "push_failed")).as("application/json")
            }
          case _ =>
            Future.successful(Results.Redirect(s"$finishUri${separator}error=user_denied"))
        }

      case None =>
        val status = if (approved) "approved" else "denied"
        Future.successful(Results.Ok(Json.obj("status" -> status)).as("application/json"))
    }
  }

  // -------------------------------------------------------------------------
  // User code endpoint (GET/POST /.well-known/gnap/device) — §2.5.1.4
  // -------------------------------------------------------------------------

  private def handleUserCode(ctx: NgRequestSinkContext, conf: GnapPluginConfig)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    ctx.request.method match {
      case "GET"  => renderUserCodePage(conf, ctx)
      case "POST" => processUserCode(conf, ctx)
      case _      =>
        Future.successful(
          GnapResponse.errorResult(Results.MethodNotAllowed, GnapErrorCode.InvalidRequest, "Method not allowed")
        )
    }
  }

  private def renderUserCodePage(conf: GnapPluginConfig, ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val csrfToken = GnapCrypto.generateNonce(16)
    GnapDatastore.storeCsrfToken("usercode-page", csrfToken, 300)

    val html =
      s"""<!DOCTYPE html>
         |<html><head><meta charset="utf-8"><title>GNAP Device Authorization</title>
         |<style>
         |  body { font-family: system-ui, sans-serif; max-width: 400px; margin: 80px auto; padding: 0 20px; }
         |  .card { border: 1px solid #ddd; border-radius: 8px; padding: 24px; text-align: center; }
         |  h1 { font-size: 1.4em; }
         |  input[name=user_code] { font-size: 1.5em; text-align: center; padding: 12px; width: 200px;
         |    letter-spacing: 4px; font-family: monospace; border: 2px solid #ddd; border-radius: 8px; text-transform: uppercase; }
         |  button { margin-top: 16px; padding: 10px 32px; border-radius: 6px; border: none;
         |    background: #2563eb; color: white; font-size: 1em; cursor: pointer; }
         |</style></head>
         |<body><div class="card">
         |  <h1>Enter Device Code</h1>
         |  <p>Enter the code displayed on your device:</p>
         |  <form method="POST" action="${escapeHtml(conf.userCodePath)}">
         |    <input type="hidden" name="csrf_token" value="${escapeHtml(csrfToken)}" />
         |    <input type="text" name="user_code" maxlength="20" autocomplete="off" autofocus required /><br/>
         |    <button type="submit">Submit</button>
         |  </form>
         |</div></body></html>""".stripMargin

    Future.successful(Results.Ok(html).as("text/html"))
  }

  private def processUserCode(conf: GnapPluginConfig, ctx: NgRequestSinkContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    ctx.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val params    = play.core.parsers.FormUrlEncodedParser.parse(bodyRaw.utf8String, "UTF-8").map { case (k, v) => k -> v.headOption.getOrElse("") }
      val userCode  = params.getOrElse("user_code", "").trim.toUpperCase
      val csrfToken = params.getOrElse("csrf_token", "")

      GnapDatastore.loadCsrfToken("usercode-page").flatMap {
        case Some(stored) if stored == csrfToken =>
          if (userCode.isEmpty) {
            Future.successful(
              GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidRequest, "User code is required")
            )
          } else {
            GnapDatastore.loadGrantByUserCode(userCode).flatMap {
              case None =>
                Future.successful(
                  GnapResponse.errorResult(Results.NotFound, GnapErrorCode.UnknownInteraction, "Invalid or expired code")
                )
              case Some(grant) if grant.state != GnapGrantState.Pending =>
                Future.successful(
                  GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidInteraction, "Grant is no longer pending")
                )
              case Some(grant) =>
                grant.interactRef match {
                  case Some(interactRef) =>
                    val baseUrl = s"${ctx.request.theProtocol}://${ctx.request.host}"
                    // Delete the user code and redirect to the interaction consent page
                    GnapDatastore.deleteUserCode(userCode).map { _ =>
                      Results.Redirect(s"$baseUrl${conf.interactionEndpointPath}/$interactRef")
                    }
                  case None =>
                    Future.successful(
                      GnapResponse.errorResult(Results.BadRequest, GnapErrorCode.InvalidInteraction, "Grant has no interaction reference")
                    )
                }
            }
          }
        case _ =>
          Future.successful(GnapResponse.errorResult(Results.Forbidden, GnapErrorCode.InvalidRequest, "Invalid or missing CSRF token"))
      }
    }
  }

  // -------------------------------------------------------------------------
  // Token emission helpers
  // -------------------------------------------------------------------------

  private def emitTokensForGrant(
      req: GnapGrantRequest,
      grantId: String,
      grantedAccess: Seq[GnapAccessRight],
      subject: Option[JsObject],
      privkey: String,
      conf: GnapPluginConfig,
      ctx: NgRequestSinkContext,
      continueTokenHash: String,
      now: Long
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val baseUrl = s"${ctx.request.theProtocol}://${ctx.request.host}"

    req.accessToken match {
      // Multiple token request (RFC 9635 Section 2.1.1)
      case Some(GnapAccessTokenRequestField.Multiple(requests)) =>
        val tokenResults = requests.map { atr =>
          val label  = atr.label.getOrElse(IdGenerator.token(8))
          val access = if (grantedAccess.nonEmpty) grantedAccess else atr.access
          GnapBiscuitEmitter.emitToken(access, subject, grantId, privkey, conf.tokenTtlSeconds) match {
            case Right((tokenB64, revocationId)) =>
              val tokenId   = IdGenerator.uuid
              val manageUrl = s"$baseUrl${conf.tokenManagementPath}/$tokenId"
              Right((label, tokenB64, access, revocationId, tokenId, manageUrl))
            case Left(err) => Left(err)
          }
        }

        val errors = tokenResults.collect { case Left(e) => e }
        if (errors.nonEmpty) {
          Future.successful(
            GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, errors.head)
          )
        } else {
          val tokens   = tokenResults.collect { case Right(t) => t }
          val tokenIds = tokens.map(_._5)
          val grant = GnapGrant(
            grantId = grantId,
            state = GnapGrantState.Finalized,
            clientInstance = req.client,
            requestedAccess = allAccessRights(req),
            grantedAccess = if (grantedAccess.nonEmpty) grantedAccess else allAccessRights(req),
            interactRef = None, clientNonce = None, serverNonce = None,
            finishUri = None, finishMethod = None, subject = subject,
            continueTokenHash = continueTokenHash,
            biscuitRevocationId = tokens.headOption.map(_._4),
            tokenIds = tokenIds,
            createdAt = now,
            expiresAt = now + (conf.grantTtlSeconds * 1000L),
            tokenExpiresAt = now + (conf.tokenTtlSeconds * 1000L)
          )

          val storeFutures = tokens.map { case (_, _, access, revId, tokenId, _) =>
            GnapDatastore.storeTokenRef(tokenId, grantId, revId, access, conf.tokenTtlSeconds)
          }

          for {
            _ <- GnapDatastore.storeGrant(grant, conf.tokenTtlSeconds)
            _ <- Future.sequence(storeFutures)
          } yield {
            val tokensMap = tokens.map { case (label, tokenB64, access, _, _, manageUrl) =>
              label -> (tokenB64, access, conf.tokenTtlSeconds, Some(manageUrl))
            }.toMap
            Results.Ok(GnapResponse.multiTokenResponse(tokensMap, subject)).as("application/json")
          }
        }

      // Single token request (default)
      case _ =>
        val accessRights = if (grantedAccess.nonEmpty) grantedAccess else allAccessRights(req)
        GnapBiscuitEmitter.emitToken(accessRights, subject, grantId, privkey, conf.tokenTtlSeconds) match {
          case Left(err) =>
            Future.successful(
              GnapResponse.errorResult(Results.InternalServerError, GnapErrorCode.RequestDenied, err)
            )
          case Right((tokenB64, revocationId)) =>
            val tokenId   = IdGenerator.uuid
            val manageUrl = s"$baseUrl${conf.tokenManagementPath}/$tokenId"
            val grant = GnapGrant(
              grantId = grantId,
              state = GnapGrantState.Finalized,
              clientInstance = req.client,
              requestedAccess = accessRights,
              grantedAccess = accessRights,
              interactRef = None, clientNonce = None, serverNonce = None,
              finishUri = None, finishMethod = None, subject = subject,
              continueTokenHash = continueTokenHash,
              biscuitRevocationId = Some(revocationId),
              tokenIds = Seq(tokenId),
              createdAt = now,
              expiresAt = now + (conf.grantTtlSeconds * 1000L),
              tokenExpiresAt = now + (conf.tokenTtlSeconds * 1000L)
            )

            for {
              _ <- GnapDatastore.storeGrant(grant, conf.tokenTtlSeconds)
              _ <- GnapDatastore.storeTokenRef(tokenId, grantId, revocationId, accessRights, conf.tokenTtlSeconds)
            } yield {
              Results.Ok(GnapResponse.tokenResponseWithSubject(
                tokenB64, accessRights, conf.tokenTtlSeconds,
                manageUrl = Some(manageUrl),
                subject = subject
              )).as("application/json")
            }
        }
    }
  }

  private def allAccessRights(req: GnapGrantRequest): Seq[GnapAccessRight] =
    req.accessToken.map(GnapAccessTokenRequestField.allRequests).getOrElse(Seq.empty).flatMap(_.access)

  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
