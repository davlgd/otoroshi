package otoroshi.next.plugins

import org.biscuitsec.biscuit.token.Biscuit
import org.biscuitsec.biscuit.token.builder.Utils._
import org.biscuitsec.biscuit.token.builder.parser.Parser
import org.biscuitsec.biscuit.crypto.PublicKey
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Results}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

// ---------------------------------------------------------------------------
// GNAP Access Validator Configuration
// ---------------------------------------------------------------------------

case class GnapAccessValidatorConfig(
    biscuitPubkey: Option[String] = None,
    checks: Seq[String] = Seq.empty,
    facts: Seq[String] = Seq.empty,
    rules: Seq[String] = Seq.empty,
    revocationCheckEnabled: Boolean = true,
    extractorType: String = "header",
    extractorName: String = "Authorization"
) extends NgPluginConfig {
  override def json: JsValue = GnapAccessValidatorConfig.format.writes(this)
}

object GnapAccessValidatorConfig {
  val default: GnapAccessValidatorConfig = GnapAccessValidatorConfig()

  val configFlow: Seq[String] = Seq(
    "biscuit_pubkey",
    "extractor_type",
    "extractor_name",
    "revocation_check_enabled",
    "checks",
    "facts",
    "rules"
  )

  val configSchema: JsObject = Json.obj(
    "biscuit_pubkey"          -> Json.obj("type" -> "string", "label" -> "Biscuit public key (hex)"),
    "extractor_type"          -> Json.obj("type" -> "string", "label" -> "Token extractor type", "default" -> "header",
      "props" -> Json.obj("options" -> Json.arr(
        Json.obj("label" -> "Header", "value" -> "header"),
        Json.obj("label" -> "Query parameter", "value" -> "query"),
        Json.obj("label" -> "Cookie", "value" -> "cookie")
      ))
    ),
    "extractor_name"          -> Json.obj("type" -> "string", "label" -> "Token extractor name", "default" -> "Authorization"),
    "revocation_check_enabled" -> Json.obj("type" -> "bool", "label" -> "Enable revocation checking"),
    "checks"                  -> Json.obj("type" -> "array", "label" -> "Biscuit checks (Datalog)"),
    "facts"                   -> Json.obj("type" -> "array", "label" -> "Biscuit facts (Datalog)"),
    "rules"                   -> Json.obj("type" -> "array", "label" -> "Biscuit rules (Datalog)")
  )

  val format: Format[GnapAccessValidatorConfig] = new Format[GnapAccessValidatorConfig] {
    override def reads(json: JsValue): JsResult[GnapAccessValidatorConfig] = Try {
      GnapAccessValidatorConfig(
        biscuitPubkey = (json \ "biscuit_pubkey").asOpt[String],
        checks = (json \ "checks").asOpt[Seq[String]].getOrElse(Seq.empty),
        facts = (json \ "facts").asOpt[Seq[String]].getOrElse(Seq.empty),
        rules = (json \ "rules").asOpt[Seq[String]].getOrElse(Seq.empty),
        revocationCheckEnabled = (json \ "revocation_check_enabled").asOpt[Boolean].getOrElse(true),
        extractorType = (json \ "extractor_type").asOpt[String].getOrElse("header"),
        extractorName = (json \ "extractor_name").asOpt[String].getOrElse("Authorization")
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapAccessValidatorConfig): JsValue = Json.obj(
      "biscuit_pubkey"          -> o.biscuitPubkey,
      "checks"                  -> o.checks,
      "facts"                   -> o.facts,
      "rules"                   -> o.rules,
      "revocation_check_enabled" -> o.revocationCheckEnabled,
      "extractor_type"          -> o.extractorType,
      "extractor_name"          -> o.extractorName
    )
  }
}

// ---------------------------------------------------------------------------
// GNAP Access Validator Plugin (NgAccessValidator)
// ---------------------------------------------------------------------------

class GnapAccessValidator extends NgAccessValidator {

  private val logger = play.api.Logger("otoroshi-plugins-gnap-validator")

  override def name: String                                = "GNAP Biscuit Access Validator"
  override def description: Option[String]                 =
    "Validates Biscuit tokens issued by the GNAP grant endpoint, with revocation checking via the GNAP datastore".some
  override def defaultConfigObject: Option[NgPluginConfig] = GnapAccessValidatorConfig.default.some
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = GnapAccessValidatorConfig.configFlow
  override def configSchema: Option[JsObject]              = GnapAccessValidatorConfig.configSchema.some

  private def forbidden(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] =
    Errors
      .craftResponseResult(
        "forbidden",
        Results.Forbidden,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(GnapAccessValidatorConfig.format)
      .getOrElse(GnapAccessValidatorConfig.default)

    config.biscuitPubkey match {
      case None => forbidden(ctx)
      case Some(pubkeyHex) =>
        extractToken(ctx.request, config) match {
          case None => forbidden(ctx)
          case Some(tokenStr) =>
            val pubkey = new PublicKey(biscuit.format.schema.Schema.PublicKey.Algorithm.Ed25519, pubkeyHex)
            Try(Biscuit.from_b64url(tokenStr, pubkey)) match {
              case Failure(_) => forbidden(ctx)
              case Success(biscuitToken) =>
                Try(biscuitToken.verify(pubkey)) match {
                  case Failure(_) => forbidden(ctx)
                  case Success(verifiedBiscuit) =>
                    val authorizer    = verifiedBiscuit.authorizer()
                    val revocationIds = authorizer.get_revocation_ids().asScala
                    val revocationCheck: Future[Boolean] =
                      if (config.revocationCheckEnabled && revocationIds.nonEmpty) {
                        Future.traverse(revocationIds.toSeq) { ridStr =>
                          GnapDatastore.isRevoked(ridStr)
                        }.map(_.exists(identity))
                      } else {
                        Future.successful(false)
                      }

                    revocationCheck.flatMap { isRevoked =>
                      if (isRevoked) {
                        forbidden(ctx)
                      } else {
                        verifyBiscuit(authorizer, config, ctx)
                      }
                    }
                }
            }
        }
    }
  }

  private def extractToken(req: RequestHeader, config: GnapAccessValidatorConfig): Option[String] =
    (config.extractorType match {
      case "header" => req.headers.get(config.extractorName)
      case "query"  => req.getQueryString(config.extractorName)
      case "cookie" => req.cookies.get(config.extractorName).map(_.value)
      case _        => None
    }).map(_.trim.stripPrefix("Bearer ").stripPrefix("Biscuit ").stripPrefix("GNAP ").trim)

  private def verifyBiscuit(
      authorizer: org.biscuitsec.biscuit.token.Authorizer,
      config: GnapAccessValidatorConfig,
      ctx: NgAccessContext
  )(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    authorizer.set_time()
    authorizer.add_fact(fact("resource", Seq(string(ctx.request.thePath)).asJava))
    authorizer.add_fact(fact("operation", Seq(string(ctx.request.method)).asJava))
    authorizer.add_fact(fact("req_method", Seq(string(ctx.request.method.toLowerCase())).asJava))
    authorizer.add_fact(fact("req_path", Seq(string(ctx.request.thePath)).asJava))
    authorizer.add_fact(fact("req_domain", Seq(string(ctx.request.theDomain)).asJava))

    config.facts.foreach { f =>
      Try(Parser.fact(f)) match {
        case Success(either) if either.isRight => authorizer.add_fact(either.get()._2)
        case _                                => logger.warn(s"[GNAP] Failed to parse Biscuit fact: $f")
      }
    }
    config.checks.foreach { c =>
      Try(Parser.check(c)) match {
        case Success(either) if either.isRight => authorizer.add_check(either.get()._2)
        case _                                => logger.warn(s"[GNAP] Failed to parse Biscuit check: $c")
      }
    }
    config.rules.foreach { r =>
      Try(Parser.rule(r)) match {
        case Success(either) if either.isRight => authorizer.add_rule(either.get()._2)
        case _                                => logger.warn(s"[GNAP] Failed to parse Biscuit rule: $r")
      }
    }

    Try(authorizer.allow().authorize()) match {
      case Success(_) => NgAccess.NgAllowed.vfuture
      case Failure(_) => forbidden(ctx)
    }
  }
}
