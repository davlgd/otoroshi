package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.next.plugins._
import play.api.libs.json._

class GnapSpec extends WordSpec with MustMatchers with OptionValues {

  // -------------------------------------------------------------------------
  // RFC 9635 Section 2 — Grant Request Parsing
  // -------------------------------------------------------------------------

  "GNAP Grant Request parsing" should {

    "parse a minimal M2M grant request" in {
      val json = Json.parse("""{
        "access_token": {
          "access": [{ "type": "api", "actions": ["read"] }]
        },
        "client": {
          "key": {
            "proof": "httpsig",
            "jwk": { "kty": "OKP", "crv": "Ed25519", "x": "test" }
          }
        }
      }""")
      val req = json.as[GnapGrantRequest]
      req.accessToken mustBe defined
      req.interact mustBe empty
      req.client mustBe a[GnapClientInstance.Inline]
    }

    "parse a grant request with interaction" in {
      val json = Json.parse("""{
        "access_token": {
          "access": [{ "type": "api", "actions": ["read", "write"], "locations": ["https://api.example.com"] }]
        },
        "client": {
          "key": {
            "proof": "httpsig",
            "jwk": { "kty": "OKP", "crv": "Ed25519", "x": "test" }
          }
        },
        "interact": {
          "start": ["redirect"],
          "finish": {
            "method": "redirect",
            "uri": "https://client.example.com/callback",
            "nonce": "LKLTI25DK82FX4T4QFZC"
          }
        }
      }""")
      val req = json.as[GnapGrantRequest]
      req.interact mustBe defined
      req.interact.get.start must contain("redirect")
      req.interact.get.finish mustBe defined
      req.interact.get.finish.get.nonce mustBe "LKLTI25DK82FX4T4QFZC"
    }

    "parse a client reference (pre-registered)" in {
      val json = Json.parse("""{
        "access_token": { "access": ["read_only"] },
        "client": "client-id-12345"
      }""")
      val req = json.as[GnapGrantRequest]
      req.client mustBe GnapClientInstance.Reference("client-id-12345")
    }

    "parse multiple token requests" in {
      val json = Json.parse("""{
        "access_token": [
          { "access": [{ "type": "api", "actions": ["read"] }], "label": "token1" },
          { "access": [{ "type": "api", "actions": ["write"] }], "label": "token2" }
        ],
        "client": "client-ref"
      }""")
      val req = json.as[GnapGrantRequest]
      req.accessToken mustBe defined
      req.accessToken.get mustBe a[GnapAccessTokenRequestField.Multiple]
      val multi = req.accessToken.get.asInstanceOf[GnapAccessTokenRequestField.Multiple]
      multi.requests must have size 2
      multi.requests.head.label mustBe Some("token1")
    }

    "parse string access rights (references)" in {
      val json = Json.parse("""{
        "access_token": { "access": ["read_only", "admin"] },
        "client": "client-ref"
      }""")
      val req = json.as[GnapGrantRequest]
      val requests = GnapAccessTokenRequestField.allRequests(req.accessToken.get)
      requests.head.access(0) mustBe GnapAccessRight.Reference("read_only")
      requests.head.access(1) mustBe GnapAccessRight.Reference("admin")
    }

    "parse structured access rights with all fields" in {
      val json = Json.parse("""{
        "access_token": {
          "access": [{
            "type": "api",
            "actions": ["read", "write"],
            "locations": ["https://api.example.com/billing"],
            "datatypes": ["financial"],
            "identifier": "billing-api",
            "privileges": ["admin"]
          }]
        },
        "client": "client-ref"
      }""")
      val req     = json.as[GnapGrantRequest]
      val access  = GnapAccessTokenRequestField.allRequests(req.accessToken.get).head.access.head
      access mustBe a[GnapAccessRight.Structured]
      val s = access.asInstanceOf[GnapAccessRight.Structured]
      s.resourceType mustBe "api"
      s.actions mustBe Seq("read", "write")
      s.locations mustBe Seq("https://api.example.com/billing")
      s.datatypes mustBe Seq("financial")
      s.identifier mustBe Some("billing-api")
      s.privileges mustBe Seq("admin")
    }

    "parse proof method as string" in {
      val json = Json.parse("""{ "proof": "httpsig", "jwk": {} }""")
      val key  = json.as[GnapKey]
      key.proof.methodName mustBe "httpsig"
    }

    "parse proof method as object" in {
      val json = Json.parse("""{ "proof": { "method": "mtls" }, "cert": "PEM..." }""")
      val key  = json.as[GnapKey]
      key.proof.methodName mustBe "mtls"
    }

    "parse subject request" in {
      val json = Json.parse("""{
        "access_token": { "access": ["read"] },
        "client": "ref",
        "subject": {
          "sub_id_formats": ["opaque", "iss_sub"],
          "assertion_formats": ["id_token"]
        }
      }""")
      val req = json.as[GnapGrantRequest]
      req.subject mustBe defined
      req.subject.get.subIdFormats mustBe Seq("opaque", "iss_sub")
      req.subject.get.assertionFormats mustBe Seq("id_token")
    }
  }

  // -------------------------------------------------------------------------
  // RFC 9635 Section 2 — Grant Request Validation
  // -------------------------------------------------------------------------

  "GNAP Grant Request validation" should {

    "accept a valid M2M request" in {
      val req = GnapGrantRequest(
        accessToken = Some(GnapAccessTokenRequestField.Single(
          GnapAccessTokenRequest(access = Seq(GnapAccessRight.Reference("read")))
        )),
        client = GnapClientInstance.Inline(GnapClientInstanceInfo(
          key = GnapKeyRef.Inline(GnapKey(
            proof = GnapProofMethod.Name("httpsig"),
            jwk = Some(Json.obj("kty" -> "OKP"))
          ))
        ))
      )
      GnapRequestValidator.validate(req) mustBe Right(())
    }

    "reject empty access array" in {
      val req = GnapGrantRequest(
        accessToken = Some(GnapAccessTokenRequestField.Single(
          GnapAccessTokenRequest(access = Seq.empty)
        )),
        client = GnapClientInstance.Reference("ref")
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject empty client reference" in {
      val req = GnapGrantRequest(
        accessToken = Some(GnapAccessTokenRequestField.Single(
          GnapAccessTokenRequest(access = Seq(GnapAccessRight.Reference("r")))
        )),
        client = GnapClientInstance.Reference("")
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject client key without jwk, cert, or cert#S256" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Inline(GnapClientInstanceInfo(
          key = GnapKeyRef.Inline(GnapKey(proof = GnapProofMethod.Name("httpsig")))
        ))
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject multi-token without labels" in {
      val req = GnapGrantRequest(
        accessToken = Some(GnapAccessTokenRequestField.Multiple(Seq(
          GnapAccessTokenRequest(access = Seq(GnapAccessRight.Reference("r"))),
          GnapAccessTokenRequest(access = Seq(GnapAccessRight.Reference("w")))
        ))),
        client = GnapClientInstance.Reference("ref")
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject duplicate labels in multi-token" in {
      val req = GnapGrantRequest(
        accessToken = Some(GnapAccessTokenRequestField.Multiple(Seq(
          GnapAccessTokenRequest(access = Seq(GnapAccessRight.Reference("r")), label = Some("tok")),
          GnapAccessTokenRequest(access = Seq(GnapAccessRight.Reference("w")), label = Some("tok"))
        ))),
        client = GnapClientInstance.Reference("ref")
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject interact with no start and no finish" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Reference("ref"),
        interact = Some(GnapInteractRequest(start = Seq.empty, finish = None))
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject interact.finish with invalid method" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Reference("ref"),
        interact = Some(GnapInteractRequest(
          start = Seq("redirect"),
          finish = Some(GnapInteractFinish(method = "invalid", uri = "https://cb.com", nonce = "abc"))
        ))
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject interact.finish with empty nonce" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Reference("ref"),
        interact = Some(GnapInteractRequest(
          start = Seq("redirect"),
          finish = Some(GnapInteractFinish(method = "redirect", uri = "https://cb.com", nonce = ""))
        ))
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject interact.finish with empty uri" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Reference("ref"),
        interact = Some(GnapInteractRequest(
          start = Seq("redirect"),
          finish = Some(GnapInteractFinish(method = "redirect", uri = "", nonce = "abc"))
        ))
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "reject interact.finish with non-HTTP URI (open redirect prevention)" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Reference("ref"),
        interact = Some(GnapInteractRequest(
          start = Seq("redirect"),
          finish = Some(GnapInteractFinish(method = "redirect", uri = "javascript:alert(1)", nonce = "abc"))
        ))
      )
      GnapRequestValidator.validate(req) mustBe a[Left[_, _]]
    }

    "accept subject-only request (no access_token)" in {
      val req = GnapGrantRequest(
        accessToken = None,
        client = GnapClientInstance.Reference("ref"),
        subject = Some(GnapSubjectRequest(assertionFormats = Seq("id_token")))
      )
      GnapRequestValidator.validate(req) mustBe Right(())
    }
  }

  // -------------------------------------------------------------------------
  // JSON Roundtrip Tests
  // -------------------------------------------------------------------------

  "GNAP JSON roundtrip" should {

    "roundtrip structured access right" in {
      val original: GnapAccessRight = GnapAccessRight.Structured("api", Seq("read", "write"), Seq("https://ex.com"), Seq("financial"))
      val json     = Json.toJson(original)(GnapAccessRight.writes)
      val parsed   = json.as[GnapAccessRight]
      parsed mustBe original
    }

    "roundtrip reference access right" in {
      val original: GnapAccessRight = GnapAccessRight.Reference("read_only")
      val json = Json.toJson(original)(GnapAccessRight.writes)
      val parsed = json.as[GnapAccessRight]
      parsed mustBe original
    }

    "roundtrip grant request" in {
      val original = GnapGrantRequest(
        accessToken = Some(GnapAccessTokenRequestField.Single(
          GnapAccessTokenRequest(access = Seq(
            GnapAccessRight.Structured("api", Seq("read"), Seq("https://ex.com"))
          ))
        )),
        client = GnapClientInstance.Inline(GnapClientInstanceInfo(
          key = GnapKeyRef.Inline(GnapKey(
            proof = GnapProofMethod.Name("httpsig"),
            jwk = Some(Json.obj("kty" -> "OKP"))
          ))
        )),
        interact = Some(GnapInteractRequest(
          start = Seq("redirect"),
          finish = Some(GnapInteractFinish("redirect", "https://cb.com", "nonce123"))
        ))
      )
      val json   = Json.toJson(original)
      val parsed = json.as[GnapGrantRequest]
      parsed.interact.get.finish.get.nonce mustBe "nonce123"
    }

    "roundtrip grant state" in {
      val grant = GnapGrant(
        grantId = "test-grant",
        state = GnapGrantState.Pending,
        clientInstance = GnapClientInstance.Reference("client-ref"),
        requestedAccess = Seq(GnapAccessRight.Reference("read")),
        grantedAccess = Seq.empty,
        interactRef = Some("int-ref"),
        clientNonce = Some("cn"),
        serverNonce = Some("sn"),
        finishUri = Some("https://cb.com"),
        finishMethod = Some("redirect"),
        subject = None,
        continueTokenHash = "hash123",
        biscuitRevocationId = None,
        createdAt = 1000L,
        expiresAt = 2000L,
        tokenExpiresAt = 5000L
      )
      val json   = Json.toJson(grant)
      val parsed = json.as[GnapGrant]
      parsed.grantId mustBe "test-grant"
      parsed.state mustBe GnapGrantState.Pending
      parsed.interactRef mustBe Some("int-ref")
    }

    "roundtrip plugin config" in {
      val config = GnapPluginConfig(
        grantEndpointPath = "/gnap/tx",
        biscuitPrivkey = Some("deadbeef"),
        tokenTtlSeconds = 7200L,
        autoApproveM2m = false
      )
      val json   = Json.toJson(config)(GnapPluginConfig.format)
      val parsed = json.as[GnapPluginConfig](GnapPluginConfig.format)
      parsed.grantEndpointPath mustBe "/gnap/tx"
      parsed.biscuitPrivkey mustBe Some("deadbeef")
      parsed.tokenTtlSeconds mustBe 7200L
      parsed.autoApproveM2m mustBe false
    }
  }

  // -------------------------------------------------------------------------
  // Crypto Tests — RFC 9635 Section 4.2.3
  // -------------------------------------------------------------------------

  "GNAP Crypto" should {

    "generate non-empty nonces" in {
      val nonce1 = GnapCrypto.generateNonce()
      val nonce2 = GnapCrypto.generateNonce()
      nonce1 must not be empty
      nonce2 must not be empty
      nonce1 must not equal nonce2
    }

    "compute deterministic interaction hash" in {
      val hash1 = GnapCrypto.computeInteractionHash("cn", "sn", "ref", "https://as.example.com/gnap")
      val hash2 = GnapCrypto.computeInteractionHash("cn", "sn", "ref", "https://as.example.com/gnap")
      hash1 mustBe hash2
    }

    "compute different hashes for different inputs" in {
      val hash1 = GnapCrypto.computeInteractionHash("cn", "sn", "ref1", "https://as.example.com/gnap")
      val hash2 = GnapCrypto.computeInteractionHash("cn", "sn", "ref2", "https://as.example.com/gnap")
      hash1 must not equal hash2
    }

    "match RFC 9635 Section 4.2.3 test vector" in {
      val hash = GnapCrypto.computeInteractionHash(
        "VJLO6A4CATR0KRO",
        "MBDOFXG4Y5CVJCX821LH",
        "4IFWWIKYB2PQ6U56NL1",
        "https://server.example.com/tx"
      )
      hash mustBe "x-gguKWTj8rQf7d7i3w3UhzvuJ5bpOlKyAlVpLxBffY"
    }

    "hash token deterministically" in {
      val h1 = GnapCrypto.hashToken("my-token")
      val h2 = GnapCrypto.hashToken("my-token")
      h1 mustBe h2
    }

    "produce different hashes for different tokens" in {
      val h1 = GnapCrypto.hashToken("token-a")
      val h2 = GnapCrypto.hashToken("token-b")
      h1 must not equal h2
    }

    "support sha-512 hash method" in {
      val sha256 = GnapCrypto.computeInteractionHash("cn", "sn", "ref", "https://ex.com", "sha-256")
      val sha512 = GnapCrypto.computeInteractionHash("cn", "sn", "ref", "https://ex.com", "sha-512")
      sha256 must not equal sha512
      sha512.length must be > sha256.length
    }
  }

  // -------------------------------------------------------------------------
  // Biscuit Token Emission
  // -------------------------------------------------------------------------

  "GNAP Biscuit Emitter" should {

    "emit a valid Biscuit token for structured access rights" in {
      val keypair = new org.biscuitsec.biscuit.crypto.KeyPair(new java.security.SecureRandom())
      val privkey = keypair.toHex

      val result = GnapBiscuitEmitter.emitToken(
        accessRights = Seq(
          GnapAccessRight.Structured("api", Seq("read", "write"), Seq("https://billing.example.com"))
        ),
        subject = Some(Json.obj("sub" -> "user-123", "email" -> "d@clever.cloud", "roles" -> Json.arr("admin"))),
        grantId = "grant-abc",
        privateKeyHex = privkey,
        tokenTtlSeconds = 3600L
      )

      result mustBe a[Right[_, _]]
      val (tokenB64, revocationId) = result.right.get
      tokenB64 must not be empty
      revocationId must not be empty

      // Verify the token is deserializable
      val verifyPubkey = new org.biscuitsec.biscuit.crypto.PublicKey(
        biscuit.format.schema.Schema.PublicKey.Algorithm.Ed25519,
        keypair.public_key().toHex
      )
      val parsedBiscuit = org.biscuitsec.biscuit.token.Biscuit.from_b64url(tokenB64, verifyPubkey)
      parsedBiscuit must not be null
    }

    "emit a valid Biscuit token for reference access rights" in {
      val keypair = new org.biscuitsec.biscuit.crypto.KeyPair(new java.security.SecureRandom())

      val result = GnapBiscuitEmitter.emitToken(
        accessRights = Seq(GnapAccessRight.Reference("read_only")),
        subject = None,
        grantId = "grant-ref",
        privateKeyHex = keypair.toHex,
        tokenTtlSeconds = 600L
      )

      result mustBe a[Right[_, _]]
    }

    "fail with invalid private key" in {
      val result = GnapBiscuitEmitter.emitToken(
        accessRights = Seq(GnapAccessRight.Reference("r")),
        subject = None,
        grantId = "grant-bad",
        privateKeyHex = "not-a-valid-key",
        tokenTtlSeconds = 60L
      )

      result mustBe a[Left[_, _]]
    }
  }

  // -------------------------------------------------------------------------
  // Error Response Formatting — RFC 9635 Section 3.6
  // -------------------------------------------------------------------------

  "GNAP Error Responses" should {

    "format error with code only per RFC 9635 Section 3.6" in {
      val json = GnapResponse.error(GnapErrorCode.InvalidRequest)
      (json \ "error").as[String] mustBe "invalid_request"
    }

    "format error with description per RFC 9635 Section 3.6" in {
      val json = GnapResponse.error(GnapErrorCode.UserDenied, Some("User clicked deny"))
      (json \ "error").as[String] mustBe "user_denied"
      (json \ "description").as[String] mustBe "User clicked deny"
    }

    "format all standard error codes" in {
      val codes = Seq(
        GnapErrorCode.InvalidRequest,
        GnapErrorCode.InvalidClient,
        GnapErrorCode.InvalidInteraction,
        GnapErrorCode.InvalidContinuation,
        GnapErrorCode.UserDenied,
        GnapErrorCode.RequestDenied,
        GnapErrorCode.UnknownInteraction,
        GnapErrorCode.TooFast,
        GnapErrorCode.TooManyAttempts
      )
      codes.foreach { code =>
        val json = GnapResponse.error(code)
        (json \ "error").as[String] mustBe code.code
      }
    }

    "parse unknown error codes" in {
      GnapErrorCode.fromString("custom_error") mustBe GnapErrorCode.Unknown("custom_error")
    }

    "format token response" in {
      val json = GnapResponse.tokenResponse(
        "token-value-123",
        Seq(GnapAccessRight.Reference("read")),
        3600L
      )
      (json \ "access_token" \ "value").as[String] mustBe "token-value-123"
      (json \ "access_token" \ "expires_in").as[Long] mustBe 3600L
      (json \ "access_token" \ "access").as[JsArray].value must have size 1
    }

    "format interaction response" in {
      val json = GnapResponse.interactionResponse(
        "continue-tok",
        "https://as.example.com/continue/123",
        "https://as.example.com/interact/456",
        "server-nonce-abc"
      )
      (json \ "continue" \ "access_token" \ "value").as[String] mustBe "continue-tok"
      (json \ "continue" \ "uri").as[String] mustBe "https://as.example.com/continue/123"
      (json \ "interact" \ "redirect").as[String] mustBe "https://as.example.com/interact/456"
      (json \ "interact" \ "finish").as[String] mustBe "server-nonce-abc"
    }
  }

  // -------------------------------------------------------------------------
  // Grant State Tests
  // -------------------------------------------------------------------------

  "GNAP Grant State" should {

    "parse all states" in {
      GnapGrantState.fromString("pending") mustBe GnapGrantState.Pending
      GnapGrantState.fromString("approved") mustBe GnapGrantState.Approved
      GnapGrantState.fromString("finalized") mustBe GnapGrantState.Finalized
      GnapGrantState.fromString("denied") mustBe GnapGrantState.Denied
    }

    "default unknown states to Denied" in {
      GnapGrantState.fromString("unknown") mustBe GnapGrantState.Denied
    }
  }

  // -------------------------------------------------------------------------
  // Plugin Configuration Tests
  // -------------------------------------------------------------------------

  "GNAP Plugin Config" should {

    "use sensible defaults" in {
      val config = GnapPluginConfig.default
      config.grantEndpointPath mustBe "/.well-known/gnap/tx"
      config.continueEndpointPath mustBe "/.well-known/gnap/continue"
      config.interactionEndpointPath mustBe "/.well-known/gnap/interact"
      config.tokenManagementPath mustBe "/.well-known/gnap/token"
      config.discoveryPath mustBe "/.well-known/gnap-as-rs"
      config.userCodePath mustBe "/.well-known/gnap/device"
      config.userCodeLength mustBe 8
      config.grantTtlSeconds mustBe 600L
      config.tokenTtlSeconds mustBe 3600L
      config.autoApproveM2m mustBe false
      config.secure mustBe true
      config.revocationCheckEnabled mustBe true
    }

    "parse from JSON with overrides" in {
      val json = Json.obj(
        "grant_endpoint_path"    -> "/gnap/tx",
        "token_management_path"  -> "/gnap/token",
        "token_ttl_seconds"      -> 7200,
        "auto_approve_m2m"       -> false,
        "biscuit_privkey"        -> "abc123"
      )
      val config = json.as[GnapPluginConfig](GnapPluginConfig.format)
      config.grantEndpointPath mustBe "/gnap/tx"
      config.tokenManagementPath mustBe "/gnap/token"
      config.tokenTtlSeconds mustBe 7200L
      config.autoApproveM2m mustBe false
      config.biscuitPrivkey mustBe Some("abc123")
    }

    "parse from empty JSON with all defaults" in {
      val config = Json.obj().as[GnapPluginConfig](GnapPluginConfig.format)
      config mustBe GnapPluginConfig.default
    }
  }

  // -------------------------------------------------------------------------
  // Access Validator Config Tests
  // -------------------------------------------------------------------------

  "GNAP Access Validator Config" should {

    "use sensible defaults" in {
      val config = GnapAccessValidatorConfig.default
      config.extractorType mustBe "header"
      config.extractorName mustBe "Authorization"
      config.revocationCheckEnabled mustBe true
      config.checks mustBe empty
      config.facts mustBe empty
      config.rules mustBe empty
    }

    "parse with custom checks and facts" in {
      val json = Json.obj(
        "biscuit_pubkey" -> "ed25519pub",
        "checks"         -> Json.arr("""check if action("read")"""),
        "facts"          -> Json.arr("""resource("/api")"""),
        "rules"          -> Json.arr("""allow if true""")
      )
      val config = json.as[GnapAccessValidatorConfig](GnapAccessValidatorConfig.format)
      config.biscuitPubkey mustBe Some("ed25519pub")
      config.checks must have size 1
      config.facts must have size 1
      config.rules must have size 1
    }
  }

  // -------------------------------------------------------------------------
  // Discovery Document — RFC 9635 Section 9
  // -------------------------------------------------------------------------

  "GNAP Discovery Document" should {

    "format a valid discovery document" in {
      val doc = GnapResponse.discoveryDocument(
        "https://as.example.com/.well-known/gnap/tx",
        "https://as.example.com/.well-known/gnap/token"
      )
      (doc \ "grant_request_endpoint").as[String] mustBe "https://as.example.com/.well-known/gnap/tx"
      (doc \ "token_management_endpoint").as[String] mustBe "https://as.example.com/.well-known/gnap/token"
      (doc \ "interaction_start_modes_supported").as[Seq[String]] must contain("redirect")
      (doc \ "interaction_finish_methods_supported").as[Seq[String]] must contain allOf("redirect", "push")
      (doc \ "token_formats_supported").as[Seq[String]] must contain("biscuit")
    }

    "include custom modes and formats" in {
      val doc = GnapResponse.discoveryDocument(
        "https://as.example.com/tx",
        "https://as.example.com/token",
        interactionStartModes = Seq("redirect", "user_code"),
        keyProofs = Seq("httpsig", "mtls"),
        subjectFormats = Seq("opaque", "iss_sub")
      )
      (doc \ "interaction_start_modes_supported").as[Seq[String]] must contain("user_code")
      (doc \ "key_proofs_supported").as[Seq[String]] must contain allOf("httpsig", "mtls")
      (doc \ "sub_id_formats_supported").as[Seq[String]] must contain("iss_sub")
    }
  }

  // -------------------------------------------------------------------------
  // Token Response with Subject — RFC 9635 Section 3.4
  // -------------------------------------------------------------------------

  "GNAP Token Response with Subject" should {

    "include subject info when provided" in {
      val subject = Json.obj("sub" -> "user-123", "email" -> "test@example.com")
      val json = GnapResponse.tokenResponseWithSubject(
        "tok-value", Seq(GnapAccessRight.Reference("read")), 3600L,
        subject = Some(subject)
      )
      (json \ "access_token" \ "value").as[String] mustBe "tok-value"
      (json \ "subject" \ "sub").as[String] mustBe "user-123"
      (json \ "subject" \ "email").as[String] mustBe "test@example.com"
    }

    "omit subject when not provided" in {
      val json = GnapResponse.tokenResponseWithSubject(
        "tok-value", Seq(GnapAccessRight.Reference("read")), 3600L
      )
      (json \ "access_token" \ "value").as[String] mustBe "tok-value"
      (json \ "subject").asOpt[JsObject] mustBe None
    }

    "include manage URL" in {
      val json = GnapResponse.tokenResponse(
        "tok-value", Seq(GnapAccessRight.Reference("read")), 3600L,
        manageUrl = Some("https://as.example.com/token/abc")
      )
      (json \ "access_token" \ "manage").as[String] mustBe "https://as.example.com/token/abc"
    }

    "include token flags" in {
      val json = GnapResponse.tokenResponse(
        "tok-value", Seq(GnapAccessRight.Reference("read")), 3600L,
        flags = Seq("bearer")
      )
      (json \ "access_token" \ "flags").as[Seq[String]] must contain("bearer")
    }
  }

  // -------------------------------------------------------------------------
  // Multi-Token Response — RFC 9635 Section 3.2.1
  // -------------------------------------------------------------------------

  "GNAP Multi-Token Response" should {

    "format multiple labeled tokens" in {
      val tokens = Map(
        "token1" -> ("val1", Seq[GnapAccessRight](GnapAccessRight.Reference("read")), 3600L, Some("https://as.example.com/token/t1")),
        "token2" -> ("val2", Seq[GnapAccessRight](GnapAccessRight.Reference("write")), 7200L, None)
      )
      val json = GnapResponse.multiTokenResponse(tokens)
      (json \ "access_token" \ "token1" \ "value").as[String] mustBe "val1"
      (json \ "access_token" \ "token1" \ "expires_in").as[Long] mustBe 3600L
      (json \ "access_token" \ "token1" \ "manage").as[String] mustBe "https://as.example.com/token/t1"
      (json \ "access_token" \ "token2" \ "value").as[String] mustBe "val2"
      (json \ "access_token" \ "token2" \ "expires_in").as[Long] mustBe 7200L
      (json \ "access_token" \ "token2" \ "manage").asOpt[String] mustBe None
    }

    "include subject info with multi-token" in {
      val tokens = Map(
        "tok" -> ("val", Seq[GnapAccessRight](GnapAccessRight.Reference("r")), 60L, None)
      )
      val subject = Json.obj("sub" -> "user-abc")
      val json = GnapResponse.multiTokenResponse(tokens, Some(subject))
      (json \ "subject" \ "sub").as[String] mustBe "user-abc"
    }
  }

  // -------------------------------------------------------------------------
  // User Code Generation
  // -------------------------------------------------------------------------

  "GNAP User Code" should {

    "generate codes of correct length with separator" in {
      val code = GnapCrypto.generateUserCode(8)
      code must have length 9 // 8 chars + 1 separator
      code(4) mustBe '-'
    }

    "generate unique codes" in {
      val codes = (1 to 10).map(_ => GnapCrypto.generateUserCode())
      codes.distinct must have size 10
    }

    "use only consonants (easy to read)" in {
      val code = GnapCrypto.generateUserCode(8)
      val chars = code.replace("-", "")
      chars.foreach { c =>
        "BCDFGHJKLMNPQRSTVWXZ" must include(c.toString)
      }
    }
  }

  // -------------------------------------------------------------------------
  // Grant with hashMethod
  // -------------------------------------------------------------------------

  "GNAP Grant with hashMethod" should {

    "roundtrip hashMethod and tokenIds" in {
      val grant = GnapGrant(
        grantId = "test-grant",
        state = GnapGrantState.Pending,
        clientInstance = GnapClientInstance.Reference("client-ref"),
        requestedAccess = Seq(GnapAccessRight.Reference("read")),
        grantedAccess = Seq.empty,
        interactRef = Some("int-ref"),
        clientNonce = Some("cn"),
        serverNonce = Some("sn"),
        finishUri = Some("https://cb.com"),
        finishMethod = Some("redirect"),
        hashMethod = Some("sha-512"),
        subject = None,
        continueTokenHash = "hash123",
        biscuitRevocationId = None,
        tokenIds = Seq("tid-1", "tid-2"),
        createdAt = 1000L,
        expiresAt = 2000L,
        tokenExpiresAt = 5000L
      )
      val json   = Json.toJson(grant)
      val parsed = json.as[GnapGrant]
      parsed.hashMethod mustBe Some("sha-512")
      parsed.tokenIds mustBe Seq("tid-1", "tid-2")
    }

    "default hashMethod to None and tokenIds to empty" in {
      val json = Json.obj(
        "grant_id"              -> "g1",
        "state"                 -> "pending",
        "client_instance"       -> "ref",
        "requested_access"      -> Json.arr("read"),
        "continue_token_hash"   -> "hash",
        "created_at"            -> 1000,
        "expires_at"            -> 2000,
        "token_expires_at"      -> 5000
      )
      val grant = json.as[GnapGrant]
      grant.hashMethod mustBe None
      grant.tokenIds mustBe Seq.empty
    }
  }
}
