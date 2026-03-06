package otoroshi.next.plugins

import akka.util.ByteString
import org.biscuitsec.biscuit.crypto.{KeyPair => BiscuitKeyPair}
import org.biscuitsec.biscuit.datalog.SymbolTable
import org.biscuitsec.biscuit.token.Biscuit
import org.biscuitsec.biscuit.token.builder.Utils._
import org.biscuitsec.biscuit.token.builder.parser.Parser
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

// ---------------------------------------------------------------------------
// GNAP Models — RFC 9635
// ---------------------------------------------------------------------------

sealed trait GnapAccessRight
object GnapAccessRight {
  case class Reference(value: String)                     extends GnapAccessRight
  case class Structured(
      resourceType: String,
      actions: Seq[String] = Seq.empty,
      locations: Seq[String] = Seq.empty,
      datatypes: Seq[String] = Seq.empty,
      identifier: Option[String] = None,
      privileges: Seq[String] = Seq.empty
  ) extends GnapAccessRight

  implicit val reads: Reads[GnapAccessRight] = Reads { json =>
    json.validate[JsObject].flatMap { obj =>
      (obj \ "type").asOpt[String] match {
        case Some(rt) =>
          JsSuccess(Structured(
            resourceType = rt,
            actions = (obj \ "actions").asOpt[Seq[String]].getOrElse(Seq.empty),
            locations = (obj \ "locations").asOpt[Seq[String]].getOrElse(Seq.empty),
            datatypes = (obj \ "datatypes").asOpt[Seq[String]].getOrElse(Seq.empty),
            identifier = (obj \ "identifier").asOpt[String],
            privileges = (obj \ "privileges").asOpt[Seq[String]].getOrElse(Seq.empty)
          ))
        case None => JsError("Missing 'type' field in structured access right")
      }
    }.orElse(json.validate[String].map(Reference(_)))
  }

  implicit val writes: Writes[GnapAccessRight] = Writes {
    case Reference(v) => JsString(v)
    case Structured(rt, actions, locations, datatypes, identifier, privileges) =>
      Json.obj("type" -> rt) ++
        (if (actions.nonEmpty) Json.obj("actions" -> actions) else Json.obj()) ++
        (if (locations.nonEmpty) Json.obj("locations" -> locations) else Json.obj()) ++
        (if (datatypes.nonEmpty) Json.obj("datatypes" -> datatypes) else Json.obj()) ++
        identifier.map(i => Json.obj("identifier" -> i)).getOrElse(Json.obj()) ++
        (if (privileges.nonEmpty) Json.obj("privileges" -> privileges) else Json.obj())
  }
}

case class GnapAccessTokenRequest(
    access: Seq[GnapAccessRight],
    label: Option[String] = None,
    flags: Seq[String] = Seq.empty
)
object GnapAccessTokenRequest {
  implicit val format: Format[GnapAccessTokenRequest] = new Format[GnapAccessTokenRequest] {
    override def reads(json: JsValue): JsResult[GnapAccessTokenRequest] = Try {
      GnapAccessTokenRequest(
        access = (json \ "access").as[Seq[GnapAccessRight]],
        label = (json \ "label").asOpt[String],
        flags = (json \ "flags").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapAccessTokenRequest): JsValue = {
      var obj = Json.obj("access" -> Json.toJson(o.access)(Writes.seq(GnapAccessRight.writes)))
      o.label.foreach(l => obj = obj ++ Json.obj("label" -> l))
      if (o.flags.nonEmpty) obj = obj ++ Json.obj("flags" -> o.flags)
      obj
    }
  }
}

sealed trait GnapAccessTokenRequestField
object GnapAccessTokenRequestField {
  case class Single(request: GnapAccessTokenRequest)     extends GnapAccessTokenRequestField
  case class Multiple(requests: Seq[GnapAccessTokenRequest]) extends GnapAccessTokenRequestField

  implicit val reads: Reads[GnapAccessTokenRequestField] = Reads { json =>
    json.validate[JsArray].map(arr => Multiple(arr.as[Seq[GnapAccessTokenRequest]]))
      .orElse(json.validate[JsObject].map(obj => Single(obj.as[GnapAccessTokenRequest])))
  }

  implicit val writes: Writes[GnapAccessTokenRequestField] = Writes {
    case Single(r)    => Json.toJson(r)
    case Multiple(rs) => Json.toJson(rs)
  }

  def allRequests(field: GnapAccessTokenRequestField): Seq[GnapAccessTokenRequest] = field match {
    case Single(r)    => Seq(r)
    case Multiple(rs) => rs
  }
}

sealed trait GnapProofMethod {
  def methodName: String
}
object GnapProofMethod {
  case class Name(value: String) extends GnapProofMethod {
    def methodName: String = value
  }
  case class Obj(method: String) extends GnapProofMethod {
    def methodName: String = method
  }

  implicit val reads: Reads[GnapProofMethod] = Reads { json =>
    json.validate[String].map(Name(_))
      .orElse(json.validate[JsObject].flatMap { obj =>
        (obj \ "method").validate[String].map(Obj(_))
      })
  }
  implicit val writes: Writes[GnapProofMethod] = Writes {
    case Name(v)   => JsString(v)
    case Obj(m)    => Json.obj("method" -> m)
  }
}

case class GnapKey(
    proof: GnapProofMethod,
    jwk: Option[JsValue] = None,
    cert: Option[String] = None,
    certS256: Option[String] = None
)
object GnapKey {
  implicit val format: Format[GnapKey] = new Format[GnapKey] {
    override def reads(json: JsValue): JsResult[GnapKey] = Try {
      GnapKey(
        proof = (json \ "proof").as[GnapProofMethod],
        jwk = (json \ "jwk").asOpt[JsValue],
        cert = (json \ "cert").asOpt[String],
        certS256 = (json \ "cert#S256").asOpt[String]
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapKey): JsValue = {
      var obj = Json.obj("proof" -> Json.toJson(o.proof))
      o.jwk.foreach(j => obj = obj ++ Json.obj("jwk" -> j))
      o.cert.foreach(c => obj = obj ++ Json.obj("cert" -> c))
      o.certS256.foreach(c => obj = obj ++ Json.obj("cert#S256" -> c))
      obj
    }
  }
}

sealed trait GnapKeyRef
object GnapKeyRef {
  case class Inline(key: GnapKey) extends GnapKeyRef
  case class Reference(id: String) extends GnapKeyRef

  implicit val reads: Reads[GnapKeyRef] = Reads { json =>
    json.validate[JsObject].flatMap(obj => GnapKey.format.reads(obj).map(Inline(_)))
      .orElse(json.validate[String].map(Reference(_)))
  }
  implicit val writes: Writes[GnapKeyRef] = Writes {
    case Inline(k)    => Json.toJson(k)(GnapKey.format)
    case Reference(r) => JsString(r)
  }
}

case class GnapClientDisplay(
    name: Option[String] = None,
    uri: Option[String] = None,
    logoUri: Option[String] = None
)
object GnapClientDisplay {
  implicit val format: Format[GnapClientDisplay] = new Format[GnapClientDisplay] {
    override def reads(json: JsValue): JsResult[GnapClientDisplay] =
      JsSuccess(GnapClientDisplay(
        name = (json \ "name").asOpt[String],
        uri = (json \ "uri").asOpt[String],
        logoUri = (json \ "logo_uri").asOpt[String]
      ))
    override def writes(o: GnapClientDisplay): JsValue = {
      var obj = Json.obj()
      o.name.foreach(n => obj = obj ++ Json.obj("name" -> n))
      o.uri.foreach(u => obj = obj ++ Json.obj("uri" -> u))
      o.logoUri.foreach(l => obj = obj ++ Json.obj("logo_uri" -> l))
      obj
    }
  }
}

case class GnapClientInstanceInfo(
    key: GnapKeyRef,
    classId: Option[String] = None,
    display: Option[GnapClientDisplay] = None
)
object GnapClientInstanceInfo {
  implicit val format: Format[GnapClientInstanceInfo] = new Format[GnapClientInstanceInfo] {
    override def reads(json: JsValue): JsResult[GnapClientInstanceInfo] = Try {
      GnapClientInstanceInfo(
        key = (json \ "key").as[GnapKeyRef],
        classId = (json \ "class_id").asOpt[String],
        display = (json \ "display").asOpt[GnapClientDisplay]
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapClientInstanceInfo): JsValue = {
      var obj = Json.obj("key" -> Json.toJson(o.key))
      o.classId.foreach(c => obj = obj ++ Json.obj("class_id" -> c))
      o.display.foreach(d => obj = obj ++ Json.obj("display" -> Json.toJson(d)))
      obj
    }
  }
}

sealed trait GnapClientInstance
object GnapClientInstance {
  case class Reference(id: String) extends GnapClientInstance
  case class Inline(info: GnapClientInstanceInfo) extends GnapClientInstance

  implicit val reads: Reads[GnapClientInstance] = Reads { json =>
    json.validate[JsObject].flatMap(obj => GnapClientInstanceInfo.format.reads(obj).map(Inline(_)))
      .orElse(json.validate[String].map(Reference(_)))
  }
  implicit val writes: Writes[GnapClientInstance] = Writes {
    case Reference(r) => JsString(r)
    case Inline(info) => Json.toJson(info)
  }
}

case class GnapInteractFinish(
    method: String,
    uri: String,
    nonce: String,
    hashMethod: Option[String] = None
)
object GnapInteractFinish {
  implicit val format: Format[GnapInteractFinish] = new Format[GnapInteractFinish] {
    override def reads(json: JsValue): JsResult[GnapInteractFinish] = Try {
      GnapInteractFinish(
        method = (json \ "method").as[String],
        uri = (json \ "uri").as[String],
        nonce = (json \ "nonce").as[String],
        hashMethod = (json \ "hash_method").asOpt[String]
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapInteractFinish): JsValue = {
      var obj = Json.obj("method" -> o.method, "uri" -> o.uri, "nonce" -> o.nonce)
      o.hashMethod.foreach(h => obj = obj ++ Json.obj("hash_method" -> h))
      obj
    }
  }
}

case class GnapInteractRequest(
    start: Seq[String] = Seq.empty,
    finish: Option[GnapInteractFinish] = None
)
object GnapInteractRequest {
  implicit val format: Format[GnapInteractRequest] = new Format[GnapInteractRequest] {
    override def reads(json: JsValue): JsResult[GnapInteractRequest] =
      JsSuccess(GnapInteractRequest(
        start = (json \ "start").asOpt[Seq[String]].getOrElse(Seq.empty),
        finish = (json \ "finish").asOpt[GnapInteractFinish]
      ))
    override def writes(o: GnapInteractRequest): JsValue = {
      var obj = Json.obj("start" -> o.start)
      o.finish.foreach(f => obj = obj ++ Json.obj("finish" -> Json.toJson(f)))
      obj
    }
  }
}

case class GnapSubjectRequest(
    subIdFormats: Seq[String] = Seq.empty,
    assertionFormats: Seq[String] = Seq.empty
)
object GnapSubjectRequest {
  implicit val format: Format[GnapSubjectRequest] = new Format[GnapSubjectRequest] {
    override def reads(json: JsValue): JsResult[GnapSubjectRequest] =
      JsSuccess(GnapSubjectRequest(
        subIdFormats = (json \ "sub_id_formats").asOpt[Seq[String]].getOrElse(Seq.empty),
        assertionFormats = (json \ "assertion_formats").asOpt[Seq[String]].getOrElse(Seq.empty)
      ))
    override def writes(o: GnapSubjectRequest): JsValue =
      Json.obj("sub_id_formats" -> o.subIdFormats, "assertion_formats" -> o.assertionFormats)
  }
}

case class GnapGrantRequest(
    accessToken: Option[GnapAccessTokenRequestField],
    client: GnapClientInstance,
    interact: Option[GnapInteractRequest] = None,
    subject: Option[GnapSubjectRequest] = None
)
object GnapGrantRequest {
  implicit val format: Format[GnapGrantRequest] = new Format[GnapGrantRequest] {
    override def reads(json: JsValue): JsResult[GnapGrantRequest] = Try {
      GnapGrantRequest(
        accessToken = (json \ "access_token").asOpt[GnapAccessTokenRequestField],
        client = (json \ "client").as[GnapClientInstance],
        interact = (json \ "interact").asOpt[GnapInteractRequest],
        subject = (json \ "subject").asOpt[GnapSubjectRequest]
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapGrantRequest): JsValue = {
      var obj = Json.obj("client" -> Json.toJson(o.client))
      o.accessToken.foreach(at => obj = obj ++ Json.obj("access_token" -> Json.toJson(at)))
      o.interact.foreach(i => obj = obj ++ Json.obj("interact" -> Json.toJson(i)))
      o.subject.foreach(s => obj = obj ++ Json.obj("subject" -> Json.toJson(s)))
      obj
    }
  }
}

sealed trait GnapErrorCode { def code: String }
object GnapErrorCode {
  case object InvalidRequest         extends GnapErrorCode { val code = "invalid_request" }
  case object InvalidClient          extends GnapErrorCode { val code = "invalid_client" }
  case object InvalidInteraction     extends GnapErrorCode { val code = "invalid_interaction" }
  case object InvalidContinuation    extends GnapErrorCode { val code = "invalid_continuation" }
  case object UserDenied             extends GnapErrorCode { val code = "user_denied" }
  case object RequestDenied          extends GnapErrorCode { val code = "request_denied" }
  case object UnknownInteraction     extends GnapErrorCode { val code = "unknown_interaction" }
  case object TooFast                extends GnapErrorCode { val code = "too_fast" }
  case object TooManyAttempts        extends GnapErrorCode { val code = "too_many_attempts" }
  case class Unknown(code: String)   extends GnapErrorCode

  def fromString(s: String): GnapErrorCode = s match {
    case "invalid_request"      => InvalidRequest
    case "invalid_client"       => InvalidClient
    case "invalid_interaction"  => InvalidInteraction
    case "invalid_continuation" => InvalidContinuation
    case "user_denied"          => UserDenied
    case "request_denied"       => RequestDenied
    case "unknown_interaction"  => UnknownInteraction
    case "too_fast"             => TooFast
    case "too_many_attempts"    => TooManyAttempts
    case other                  => Unknown(other)
  }
}

// ---------------------------------------------------------------------------
// GNAP Grant State (persisted in datastore)
// ---------------------------------------------------------------------------

sealed trait GnapGrantState { def name: String }
object GnapGrantState {
  case object Pending   extends GnapGrantState { val name = "pending" }
  case object Approved  extends GnapGrantState { val name = "approved" }
  case object Finalized extends GnapGrantState { val name = "finalized" }
  case object Denied    extends GnapGrantState { val name = "denied" }

  def fromString(s: String): GnapGrantState = s match {
    case "pending"   => Pending
    case "approved"  => Approved
    case "finalized" => Finalized
    case "denied"    => Denied
    case _           => Denied
  }
}

case class GnapGrant(
    grantId: String,
    state: GnapGrantState,
    clientInstance: GnapClientInstance,
    requestedAccess: Seq[GnapAccessRight],
    grantedAccess: Seq[GnapAccessRight],
    interactRef: Option[String],
    clientNonce: Option[String],
    serverNonce: Option[String],
    finishUri: Option[String],
    finishMethod: Option[String],
    hashMethod: Option[String] = None,
    subject: Option[JsObject],
    continueTokenHash: String,
    biscuitRevocationId: Option[String],
    tokenIds: Seq[String] = Seq.empty,
    createdAt: Long,
    expiresAt: Long,
    tokenExpiresAt: Long
)
object GnapGrant {
  implicit val format: Format[GnapGrant] = new Format[GnapGrant] {
    override def reads(json: JsValue): JsResult[GnapGrant] = Try {
      GnapGrant(
        grantId = (json \ "grant_id").as[String],
        state = GnapGrantState.fromString((json \ "state").as[String]),
        clientInstance = (json \ "client_instance").as[GnapClientInstance],
        requestedAccess = (json \ "requested_access").as[Seq[GnapAccessRight]],
        grantedAccess = (json \ "granted_access").asOpt[Seq[GnapAccessRight]].getOrElse(Seq.empty),
        interactRef = (json \ "interact_ref").asOpt[String],
        clientNonce = (json \ "client_nonce").asOpt[String],
        serverNonce = (json \ "server_nonce").asOpt[String],
        finishUri = (json \ "finish_uri").asOpt[String],
        finishMethod = (json \ "finish_method").asOpt[String],
        hashMethod = (json \ "hash_method").asOpt[String],
        subject = (json \ "subject").asOpt[JsObject],
        continueTokenHash = (json \ "continue_token_hash").as[String],
        biscuitRevocationId = (json \ "biscuit_revocation_id").asOpt[String],
        tokenIds = (json \ "token_ids").asOpt[Seq[String]].getOrElse(Seq.empty),
        createdAt = (json \ "created_at").as[Long],
        expiresAt = (json \ "expires_at").as[Long],
        tokenExpiresAt = (json \ "token_expires_at").as[Long]
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapGrant): JsValue = Json.obj(
      "grant_id"              -> o.grantId,
      "state"                 -> o.state.name,
      "client_instance"       -> Json.toJson(o.clientInstance),
      "requested_access"      -> Json.toJson(o.requestedAccess)(Writes.seq(GnapAccessRight.writes)),
      "granted_access"        -> Json.toJson(o.grantedAccess)(Writes.seq(GnapAccessRight.writes)),
      "interact_ref"          -> o.interactRef,
      "client_nonce"          -> o.clientNonce,
      "server_nonce"          -> o.serverNonce,
      "finish_uri"            -> o.finishUri,
      "finish_method"         -> o.finishMethod,
      "hash_method"           -> o.hashMethod,
      "subject"               -> o.subject,
      "continue_token_hash"   -> o.continueTokenHash,
      "biscuit_revocation_id" -> o.biscuitRevocationId,
      "token_ids"             -> o.tokenIds,
      "created_at"            -> o.createdAt,
      "expires_at"            -> o.expiresAt,
      "token_expires_at"      -> o.tokenExpiresAt
    )
  }
}

// ---------------------------------------------------------------------------
// GNAP Crypto utilities
// ---------------------------------------------------------------------------

object GnapCrypto {

  private val rng = new SecureRandom()

  def generateNonce(length: Int = 32): String = {
    val bytes = new Array[Byte](length)
    rng.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  def sha256(input: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))

  def hashToken(token: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(sha256(token))

  def generateUserCode(length: Int = 8): String = {
    val chars = "BCDFGHJKLMNPQRSTVWXZ"
    val sb    = new StringBuilder(length + 1)
    (0 until length).foreach { i =>
      if (i == length / 2) sb.append('-')
      sb.append(chars.charAt(rng.nextInt(chars.length)))
    }
    sb.toString()
  }

  def computeInteractionHash(
      clientNonce: String,
      serverNonce: String,
      interactRef: String,
      grantEndpoint: String,
      hashMethod: String = "sha-256"
  ): String = {
    val input = s"$clientNonce\n$serverNonce\n$interactRef\n$grantEndpoint"
    val digest = hashMethod match {
      case "sha-512" =>
        MessageDigest.getInstance("SHA-512").digest(input.getBytes(StandardCharsets.UTF_8))
      case _ =>
        sha256(input)
    }
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest)
  }
}

// ---------------------------------------------------------------------------
// GNAP Biscuit Token Emitter
// ---------------------------------------------------------------------------

object GnapBiscuitEmitter {

  private val logger = play.api.Logger("otoroshi-plugins-gnap")

  def emitToken(
      accessRights: Seq[GnapAccessRight],
      subject: Option[JsObject],
      grantId: String,
      privateKeyHex: String,
      tokenTtlSeconds: Long
  ): Either[String, (String, String)] = {
    Try {
      val keypair          = new BiscuitKeyPair(privateKeyHex)
      val rng              = new SecureRandom()
      val symbols          = new SymbolTable()
      val authorityBuilder = new org.biscuitsec.biscuit.token.builder.Block()

      authorityBuilder.add_fact(
        fact("grant_id", Seq(s("authority"), string(grantId)).asJava)
      )
      authorityBuilder.add_fact(
        fact("issued_at", Seq(s("authority"), date(DateTime.now().toDate)).asJava)
      )

      subject.foreach { subj =>
        (subj \ "sub").asOpt[String].foreach { sub =>
          authorityBuilder.add_fact(fact("user", Seq(s("authority"), string(sub)).asJava))
        }
        (subj \ "email").asOpt[String].foreach { email =>
          authorityBuilder.add_fact(fact("email", Seq(s("authority"), string(email)).asJava))
        }
        (subj \ "name").asOpt[String].foreach { name =>
          authorityBuilder.add_fact(fact("username", Seq(s("authority"), string(name)).asJava))
        }
        (subj \ "roles").asOpt[Seq[String]].getOrElse(Seq.empty).foreach { role =>
          authorityBuilder.add_fact(fact("role", Seq(s("authority"), string(role)).asJava))
        }
      }

      accessRights.foreach {
        case GnapAccessRight.Reference(ref) =>
          authorityBuilder.add_fact(fact("access_ref", Seq(s("authority"), string(ref)).asJava))
        case GnapAccessRight.Structured(rt, actions, locations, datatypes, identifier, privileges) =>
          authorityBuilder.add_fact(
            fact("resource_type", Seq(s("authority"), string(rt)).asJava)
          )
          actions.foreach { a =>
            authorityBuilder.add_fact(fact("action", Seq(s("authority"), string(a)).asJava))
          }
          locations.foreach { l =>
            authorityBuilder.add_fact(fact("location", Seq(s("authority"), string(l)).asJava))
          }
          datatypes.foreach { d =>
            authorityBuilder.add_fact(fact("datatype", Seq(s("authority"), string(d)).asJava))
          }
          identifier.foreach { id =>
            authorityBuilder.add_fact(fact("identifier", Seq(s("authority"), string(id)).asJava))
          }
          privileges.foreach { p =>
            authorityBuilder.add_fact(fact("privilege", Seq(s("authority"), string(p)).asJava))
          }
      }

      val ttlSeconds = math.min(tokenTtlSeconds, Int.MaxValue.toLong).toInt
      val expirationCheck = s"""check if time($$t), $$t < ${DateTime.now().plusSeconds(ttlSeconds).toString("yyyy-MM-dd'T'HH:mm:ss'Z'")}"""
      Parser.check(expirationCheck) match {
        case either if either.isRight => authorityBuilder.add_check(either.get()._2)
        case either                   => throw new IllegalStateException(s"Failed to parse expiration check: ${either.getLeft}")
      }

      val biscuit      = Biscuit.make(rng, keypair, authorityBuilder.build(symbols))
      val tokenB64     = biscuit.serialize_b64url()
      val pubkey       = keypair.public_key()
      val authorizer   = biscuit.verify(pubkey).authorizer()
      val revocationId = authorizer.get_revocation_ids().asScala.headOption.getOrElse(grantId)

      (tokenB64, revocationId)
    } match {
      case Success((t, r)) => Right((t, r))
      case Failure(e)      =>
        logger.error(s"[GNAP] Biscuit token emission failed: ${e.getMessage}")
        Left("Token emission failed")
    }
  }
}

// ---------------------------------------------------------------------------
// GNAP Plugin Configuration
// ---------------------------------------------------------------------------

case class GnapPluginConfig(
    grantEndpointPath: String = "/.well-known/gnap/tx",
    continueEndpointPath: String = "/.well-known/gnap/continue",
    interactionEndpointPath: String = "/.well-known/gnap/interact",
    tokenManagementPath: String = "/.well-known/gnap/token",
    discoveryPath: String = "/.well-known/gnap-as-rs",
    userCodePath: String = "/.well-known/gnap/device",
    biscuitPrivkey: Option[String] = None,
    biscuitPubkey: Option[String] = None,
    grantTtlSeconds: Long = 600,
    tokenTtlSeconds: Long = 3600,
    autoApproveM2m: Boolean = false,
    userCodeLength: Int = 8,
    authModuleRef: Option[String] = None,
    domain: String = "*",
    secure: Boolean = true,
    revocationCheckEnabled: Boolean = true
) extends NgPluginConfig {
  override def json: JsValue = GnapPluginConfig.format.writes(this)
}

object GnapPluginConfig {
  val default: GnapPluginConfig = GnapPluginConfig()

  val configFlow: Seq[String] = Seq(
    "domain",
    "secure",
    "biscuit_privkey",
    "biscuit_pubkey",
    "grant_endpoint_path",
    "continue_endpoint_path",
    "interaction_endpoint_path",
    "token_management_path",
    "discovery_path",
    "user_code_path",
    "grant_ttl_seconds",
    "token_ttl_seconds",
    "user_code_length",
    "auto_approve_m2m",
    "auth_module_ref",
    "revocation_check_enabled"
  )

  val configSchema: JsObject = Json.obj(
    "domain"                    -> Json.obj("type" -> "string", "label" -> "Domain", "default" -> "*"),
    "secure"                    -> Json.obj("type" -> "bool", "label" -> "HTTPS only"),
    "biscuit_privkey"           -> Json.obj("type" -> "string", "label" -> "Biscuit private key (hex)"),
    "biscuit_pubkey"            -> Json.obj("type" -> "string", "label" -> "Biscuit public key (hex)"),
    "grant_endpoint_path"       -> Json.obj("type" -> "string", "label" -> "Grant endpoint path", "default" -> "/.well-known/gnap/tx"),
    "continue_endpoint_path"    -> Json.obj("type" -> "string", "label" -> "Continue endpoint path", "default" -> "/.well-known/gnap/continue"),
    "interaction_endpoint_path" -> Json.obj("type" -> "string", "label" -> "Interaction endpoint path", "default" -> "/.well-known/gnap/interact"),
    "token_management_path"     -> Json.obj("type" -> "string", "label" -> "Token management path", "default" -> "/.well-known/gnap/token"),
    "discovery_path"            -> Json.obj("type" -> "string", "label" -> "Discovery document path", "default" -> "/.well-known/gnap-as-rs"),
    "user_code_path"            -> Json.obj("type" -> "string", "label" -> "User code endpoint path", "default" -> "/.well-known/gnap/device"),
    "grant_ttl_seconds"         -> Json.obj("type" -> "number", "label" -> "Grant TTL", "default" -> 600, "props" -> Json.obj("suffix" -> "seconds")),
    "token_ttl_seconds"         -> Json.obj("type" -> "number", "label" -> "Token TTL", "default" -> 3600, "props" -> Json.obj("suffix" -> "seconds")),
    "user_code_length"          -> Json.obj("type" -> "number", "label" -> "User code length", "default" -> 8),
    "auto_approve_m2m"          -> Json.obj("type" -> "bool", "label" -> "Auto-approve M2M grants (no interaction)"),
    "auth_module_ref"           -> Json.obj("type" -> "select", "label" -> "Auth. module reference",
      "props" -> Json.obj("optionsFrom" -> "/bo/api/proxy/api/auths", "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id"))
    ),
    "revocation_check_enabled"  -> Json.obj("type" -> "bool", "label" -> "Enable revocation checking")
  )

  val format: Format[GnapPluginConfig] = new Format[GnapPluginConfig] {
    override def reads(json: JsValue): JsResult[GnapPluginConfig] = Try {
      GnapPluginConfig(
        grantEndpointPath = (json \ "grant_endpoint_path").asOpt[String].getOrElse("/.well-known/gnap/tx"),
        continueEndpointPath = (json \ "continue_endpoint_path").asOpt[String].getOrElse("/.well-known/gnap/continue"),
        interactionEndpointPath = (json \ "interaction_endpoint_path").asOpt[String].getOrElse("/.well-known/gnap/interact"),
        tokenManagementPath = (json \ "token_management_path").asOpt[String].getOrElse("/.well-known/gnap/token"),
        discoveryPath = (json \ "discovery_path").asOpt[String].getOrElse("/.well-known/gnap-as-rs"),
        userCodePath = (json \ "user_code_path").asOpt[String].getOrElse("/.well-known/gnap/device"),
        biscuitPrivkey = (json \ "biscuit_privkey").asOpt[String],
        biscuitPubkey = (json \ "biscuit_pubkey").asOpt[String],
        grantTtlSeconds = (json \ "grant_ttl_seconds").asOpt[Long].getOrElse(600L),
        tokenTtlSeconds = (json \ "token_ttl_seconds").asOpt[Long].getOrElse(3600L),
        autoApproveM2m = (json \ "auto_approve_m2m").asOpt[Boolean].getOrElse(false),
        userCodeLength = (json \ "user_code_length").asOpt[Int].getOrElse(8),
        authModuleRef = (json \ "auth_module_ref").asOpt[String],
        domain = (json \ "domain").asOpt[String].getOrElse("*"),
        secure = (json \ "secure").asOpt[Boolean].getOrElse(true),
        revocationCheckEnabled = (json \ "revocation_check_enabled").asOpt[Boolean].getOrElse(true)
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: GnapPluginConfig): JsValue = Json.obj(
      "grant_endpoint_path"       -> o.grantEndpointPath,
      "continue_endpoint_path"    -> o.continueEndpointPath,
      "interaction_endpoint_path" -> o.interactionEndpointPath,
      "token_management_path"     -> o.tokenManagementPath,
      "discovery_path"            -> o.discoveryPath,
      "user_code_path"            -> o.userCodePath,
      "biscuit_privkey"           -> o.biscuitPrivkey,
      "biscuit_pubkey"            -> o.biscuitPubkey,
      "grant_ttl_seconds"         -> o.grantTtlSeconds,
      "token_ttl_seconds"         -> o.tokenTtlSeconds,
      "auto_approve_m2m"          -> o.autoApproveM2m,
      "user_code_length"          -> o.userCodeLength,
      "auth_module_ref"           -> o.authModuleRef,
      "domain"                    -> o.domain,
      "secure"                    -> o.secure,
      "revocation_check_enabled"  -> o.revocationCheckEnabled
    )
  }
}

// ---------------------------------------------------------------------------
// GNAP Datastore Helper
// ---------------------------------------------------------------------------

object GnapDatastore {

  private def prefix(implicit env: Env): String = s"${env.storageRoot}:gnap"

  def storeGrant(grant: GnapGrant, ttl: Long)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val json  = Json.toJson(grant).toString()
    val bytes = ByteString(json)
    val ttlMs = Some(ttl * 1000L)
    for {
      _ <- env.datastores.rawDataStore.set(s"${prefix}:grant:${grant.grantId}", bytes, ttlMs)
      _ <- grant.interactRef.map { ref =>
        env.datastores.rawDataStore.set(
          s"${prefix}:interact:$ref",
          ByteString(grant.grantId),
          ttlMs
        )
      }.getOrElse(Future.successful(true))
      r <- env.datastores.rawDataStore.set(
        s"${prefix}:continue:${grant.continueTokenHash}",
        ByteString(grant.grantId),
        ttlMs
      )
    } yield r
  }

  def loadGrant(grantId: String)(implicit env: Env, ec: ExecutionContext): Future[Option[GnapGrant]] =
    env.datastores.rawDataStore.get(s"${prefix}:grant:$grantId").map(_.flatMap { bs =>
      Try(Json.parse(bs.utf8String).as[GnapGrant]).toOption
    })

  def loadGrantByContinueToken(tokenHash: String)(implicit env: Env, ec: ExecutionContext): Future[Option[GnapGrant]] =
    env.datastores.rawDataStore.get(s"${prefix}:continue:$tokenHash").flatMap {
      case Some(bs) => loadGrant(bs.utf8String)
      case None     => Future.successful(None)
    }

  def loadGrantByInteractRef(interactRef: String)(implicit env: Env, ec: ExecutionContext): Future[Option[GnapGrant]] =
    env.datastores.rawDataStore.get(s"${prefix}:interact:$interactRef").flatMap {
      case Some(bs) => loadGrant(bs.utf8String)
      case None     => Future.successful(None)
    }

  def isRevoked(revocationId: String)(implicit env: Env, ec: ExecutionContext): Future[Boolean] =
    env.datastores.rawDataStore.exists(s"${prefix}:revoked:$revocationId")

  def revoke(revocationId: String, ttl: Long = 86400L)(implicit env: Env, ec: ExecutionContext): Future[Boolean] =
    env.datastores.rawDataStore.set(
      s"${prefix}:revoked:$revocationId",
      ByteString(System.currentTimeMillis().toString),
      Some(ttl * 1000L)
    )

  def deleteGrant(grant: GnapGrant)(implicit env: Env, ec: ExecutionContext): Future[Long] = {
    val keys = Seq(s"${prefix}:grant:${grant.grantId}", s"${prefix}:continue:${grant.continueTokenHash}") ++
      grant.interactRef.map(ref => s"${prefix}:interact:$ref").toSeq
    env.datastores.rawDataStore.del(keys)
  }

  def storeCsrfToken(interactRef: String, token: String, ttl: Long)(implicit env: Env, ec: ExecutionContext): Future[Boolean] =
    env.datastores.rawDataStore.set(s"${prefix}:csrf:$interactRef", ByteString(token), Some(ttl * 1000L))

  def loadCsrfToken(interactRef: String)(implicit env: Env, ec: ExecutionContext): Future[Option[String]] =
    env.datastores.rawDataStore.get(s"${prefix}:csrf:$interactRef").map(_.map(_.utf8String))

  // Token management (RFC 9635 Section 6)

  def storeTokenRef(tokenId: String, grantId: String, revocationId: String, access: Seq[GnapAccessRight], ttl: Long)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val json = Json.obj(
      "grant_id"      -> grantId,
      "revocation_id" -> revocationId,
      "access"        -> Json.toJson(access)(Writes.seq(GnapAccessRight.writes))
    ).toString()
    env.datastores.rawDataStore.set(s"${prefix}:token:$tokenId", ByteString(json), Some(ttl * 1000L))
  }

  def loadTokenRef(tokenId: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsObject]] =
    env.datastores.rawDataStore.get(s"${prefix}:token:$tokenId").map(_.flatMap { bs =>
      Try(Json.parse(bs.utf8String).as[JsObject]).toOption
    })

  def deleteTokenRef(tokenId: String)(implicit env: Env, ec: ExecutionContext): Future[Long] =
    env.datastores.rawDataStore.del(Seq(s"${prefix}:token:$tokenId"))

  // User code interaction (RFC 9635 Section 2.5.1)

  def storeUserCode(code: String, grantId: String, ttl: Long)(implicit env: Env, ec: ExecutionContext): Future[Boolean] =
    env.datastores.rawDataStore.set(s"${prefix}:usercode:$code", ByteString(grantId), Some(ttl * 1000L))

  def loadGrantByUserCode(code: String)(implicit env: Env, ec: ExecutionContext): Future[Option[GnapGrant]] =
    env.datastores.rawDataStore.get(s"${prefix}:usercode:$code").flatMap {
      case Some(bs) => loadGrant(bs.utf8String)
      case None     => Future.successful(None)
    }

  def deleteUserCode(code: String)(implicit env: Env, ec: ExecutionContext): Future[Long] =
    env.datastores.rawDataStore.del(Seq(s"${prefix}:usercode:$code"))
}

// ---------------------------------------------------------------------------
// GNAP Grant Request Validator (RFC 9635 Section 2)
// ---------------------------------------------------------------------------

object GnapRequestValidator {

  def validate(req: GnapGrantRequest): Either[String, Unit] = {
    req.accessToken match {
      case None => // subject-only request is valid
      case Some(field) =>
        GnapAccessTokenRequestField.allRequests(field).foreach { atr =>
          if (atr.access.isEmpty) return Left("access_token.access must not be empty (Section 2.1)")
        }
        field match {
          case GnapAccessTokenRequestField.Multiple(reqs) =>
            if (reqs.isEmpty) return Left("access_token array must not be empty (Section 2.1)")
            if (reqs.exists(_.label.isEmpty)) return Left("label is required for multi-token requests (Section 2.1)")
            val labels = reqs.flatMap(_.label)
            if (labels.size != labels.distinct.size) return Left("access_token labels must be unique (Section 2.1)")
          case _ =>
        }
    }

    req.client match {
      case GnapClientInstance.Reference(id) if id.isEmpty =>
        return Left("client reference must not be empty (Section 2.3)")
      case GnapClientInstance.Inline(info) =>
        info.key match {
          case GnapKeyRef.Inline(k) if k.jwk.isEmpty && k.cert.isEmpty && k.certS256.isEmpty =>
            return Left("client key must contain jwk, cert, or cert#S256 (Section 7.1)")
          case GnapKeyRef.Reference(r) if r.isEmpty =>
            return Left("client key reference must not be empty (Section 7.1)")
          case _ =>
        }
      case _ =>
    }

    req.interact.foreach { interact =>
      if (interact.start.isEmpty && interact.finish.isEmpty)
        return Left("interact must have start modes or a finish method (Section 2.5)")
      interact.finish.foreach { finish =>
        if (finish.nonce.isEmpty) return Left("interact.finish.nonce must not be empty (Section 2.5.2)")
        if (finish.uri.isEmpty)   return Left("interact.finish.uri must not be empty (Section 2.5.2)")
        if (!finish.uri.startsWith("https://") && !finish.uri.startsWith("http://"))
          return Left("interact.finish.uri must be an absolute HTTP(S) URI (Section 2.5.2)")
        if (!Seq("redirect", "push").contains(finish.method))
          return Left(s"""interact.finish.method must be "redirect" or "push" (Section 2.5.2)""")
      }
    }

    Right(())
  }
}

// ---------------------------------------------------------------------------
// GNAP Response Builders
// ---------------------------------------------------------------------------

object GnapResponse {

  def error(errorCode: GnapErrorCode, description: Option[String] = None): JsValue = {
    val base = Json.obj("error" -> errorCode.code)
    description match {
      case Some(d) => base ++ Json.obj("description" -> d)
      case None    => base
    }
  }

  def errorResult(status: Results.Status, errorCode: GnapErrorCode, description: String): Result =
    status(error(errorCode, Some(description))).as("application/json")

  def tokenResponse(
      tokenValue: String,
      accessRights: Seq[GnapAccessRight],
      expiresIn: Long,
      manageUrl: Option[String] = None,
      flags: Seq[String] = Seq.empty
  ): JsValue = {
    var tok = Json.obj(
      "value"      -> tokenValue,
      "access"     -> Json.toJson(accessRights)(Writes.seq(GnapAccessRight.writes)),
      "expires_in" -> expiresIn
    )
    manageUrl.foreach(u => tok = tok ++ Json.obj("manage" -> u))
    if (flags.nonEmpty) tok = tok ++ Json.obj("flags" -> flags)
    Json.obj("access_token" -> tok)
  }

  def tokenResponseWithSubject(
      tokenValue: String,
      accessRights: Seq[GnapAccessRight],
      expiresIn: Long,
      manageUrl: Option[String] = None,
      subject: Option[JsObject] = None,
      flags: Seq[String] = Seq.empty
  ): JsValue = {
    val base = tokenResponse(tokenValue, accessRights, expiresIn, manageUrl, flags)
    subject match {
      case Some(s) => base.as[JsObject] ++ Json.obj("subject" -> s)
      case None    => base
    }
  }

  def multiTokenResponse(
      tokens: Map[String, (String, Seq[GnapAccessRight], Long, Option[String])],
      subject: Option[JsObject] = None
  ): JsValue = {
    val tokensObj = tokens.foldLeft(Json.obj()) { case (obj, (label, (value, access, expiresIn, manageUrl))) =>
      var tok = Json.obj(
        "value"      -> value,
        "access"     -> Json.toJson(access)(Writes.seq(GnapAccessRight.writes)),
        "expires_in" -> expiresIn
      )
      manageUrl.foreach(u => tok = tok ++ Json.obj("manage" -> u))
      obj ++ Json.obj(label -> tok)
    }
    var result = Json.obj("access_token" -> tokensObj)
    subject.foreach(s => result = result ++ Json.obj("subject" -> s))
    result
  }

  def interactionResponse(
      continueToken: String,
      continueUri: String,
      redirectUri: String,
      serverNonce: String,
      waitSeconds: Long = 30
  ): JsValue = Json.obj(
    "continue" -> Json.obj(
      "access_token" -> Json.obj("value" -> continueToken),
      "uri"          -> continueUri,
      "wait"         -> waitSeconds
    ),
    "interact" -> Json.obj(
      "redirect" -> redirectUri,
      "finish"   -> serverNonce
    )
  )

  def discoveryDocument(
      grantEndpoint: String,
      tokenManagementEndpoint: String,
      interactionStartModes: Seq[String] = Seq("redirect"),
      interactionFinishMethods: Seq[String] = Seq("redirect", "push"),
      keyProofs: Seq[String] = Seq.empty,
      subjectFormats: Seq[String] = Seq("opaque"),
      tokenFormats: Seq[String] = Seq("biscuit")
  ): JsValue = Json.obj(
    "grant_request_endpoint"                  -> grantEndpoint,
    "token_management_endpoint"               -> tokenManagementEndpoint,
    "interaction_start_modes_supported"        -> interactionStartModes,
    "interaction_finish_methods_supported"     -> interactionFinishMethods,
    "key_proofs_supported"                     -> keyProofs,
    "sub_id_formats_supported"                 -> subjectFormats,
    "token_formats_supported"                  -> tokenFormats
  )
}
