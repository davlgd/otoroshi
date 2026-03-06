# GNAP Authorization Server for Otoroshi

Implementation of the [Grant Negotiation and Authorization Protocol (RFC 9635)](https://www.rfc-editor.org/rfc/rfc9635) as an Otoroshi plugin, using [Biscuit tokens](https://www.biscuitsec.org/) for access token issuance.

## Architecture

The GNAP integration consists of two plugins:

| Plugin | Type | Location | Purpose |
|--------|------|----------|---------|
| **GNAP Authorization Server** | `NgRequestSink` | Global Plugins | Exposes GNAP protocol endpoints (grant, continue, interaction, token management, user code, discovery) |
| **GNAP Biscuit Access Validator** | `NgAccessValidator` | Route Plugins | Validates Biscuit tokens on protected routes |

### Why two plugins?

- The **Authorization Server** runs as a Sink plugin (Global Plugins) because it handles requests on paths that don't match any existing route (e.g., `/.well-known/gnap/tx`).
- The **Access Validator** runs per-route to enforce Biscuit token verification on protected API routes.

## Setup

### 1. Generate Biscuit key pair

```bash
# Using the Biscuit CLI
biscuit keypair

# Or via OpenSSL (Ed25519)
openssl genpkey -algorithm ed25519 -outform DER | xxd -p -c 256
```

You need an Ed25519 key pair in hexadecimal format.

### 2. Configure the Authorization Server

Go to **Global Plugins** in the Otoroshi admin, and add the `GNAP Authorization Server` plugin.

| Setting | Description | Default |
|---------|-------------|---------|
| `domain` | Domain to serve GNAP endpoints on (`*` for all) | `*` |
| `secure` | Require HTTPS | `true` |
| `biscuit_privkey` | Biscuit Ed25519 private key (hex) | — (required) |
| `biscuit_pubkey` | Biscuit Ed25519 public key (hex) | — |
| `grant_endpoint_path` | Grant negotiation endpoint | `/.well-known/gnap/tx` |
| `continue_endpoint_path` | Continuation endpoint | `/.well-known/gnap/continue` |
| `interaction_endpoint_path` | User interaction endpoint | `/.well-known/gnap/interact` |
| `token_management_path` | Token management endpoint | `/.well-known/gnap/token` |
| `discovery_path` | AS discovery document | `/.well-known/gnap-as-rs` |
| `user_code_path` | User code (device) endpoint | `/.well-known/gnap/device` |
| `grant_ttl_seconds` | How long a grant lives before expiring | `600` |
| `token_ttl_seconds` | How long an access token lives | `3600` |
| `user_code_length` | Length of user codes (device flow) | `8` |
| `auto_approve_m2m` | Auto-approve grants without interaction (M2M) | `false` |
| `auth_module_ref` | Otoroshi auth module for user identification | — |
| `revocation_check_enabled` | Check token revocation on validation | `true` |

### 3. Configure the Access Validator

On each route you want to protect, add the `GNAP Biscuit Access Validator` plugin.

| Setting | Description | Default |
|---------|-------------|---------|
| `biscuit_pubkey` | Biscuit Ed25519 public key (hex) — must match the AS | — (required) |
| `extractor_type` | Where to find the token: `header`, `query`, `cookie` | `header` |
| `extractor_name` | Header name / query param / cookie name | `Authorization` |
| `revocation_check_enabled` | Check token revocation | `true` |
| `checks` | Additional Biscuit Datalog checks | `[]` |
| `facts` | Additional Biscuit Datalog facts | `[]` |
| `rules` | Additional Biscuit Datalog rules | `[]` |

## Protocol Flows

### Machine-to-Machine (M2M) — No Interaction

When `auto_approve_m2m` is `true`, a client can obtain a token directly:

```
Client                              GNAP AS
  |                                    |
  |  POST /.well-known/gnap/tx        |
  |  { access_token, client }         |
  |----------------------------------->|
  |                                    |
  |  200 OK                            |
  |  { access_token: { value, manage } }
  |<-----------------------------------|
```

```bash
curl -X POST https://otoroshi.example.com/.well-known/gnap/tx \
  -H "Content-Type: application/json" \
  -d '{
    "access_token": {
      "access": [{ "type": "api", "actions": ["read"], "locations": ["https://api.example.com"] }]
    },
    "client": {
      "key": { "proof": "httpsig", "jwk": { "kty": "OKP", "crv": "Ed25519", "x": "..." } }
    }
  }'
```

### Interactive — Redirect Flow

For user-facing applications:

```
Client                  GNAP AS                 User Agent
  |                        |                        |
  | POST /gnap/tx          |                        |
  | { access_token,        |                        |
  |   client, interact }   |                        |
  |----------------------->|                        |
  |                        |                        |
  | 200 { continue,        |                        |
  |   interact.redirect }  |                        |
  |<-----------------------|                        |
  |                        |                        |
  | Redirect user -------->|  GET /gnap/interact/:ref
  |                        |----------------------->|
  |                        |                        |
  |                        |  Consent page          |
  |                        |<-----------------------|
  |                        |                        |
  |                        |  POST (approve/deny)   |
  |                        |<-----------------------|
  |                        |                        |
  |                        |  Redirect to finish URI|
  |                        |  ?hash=...&interact_ref=...
  |<-----------------------------------------------|
  |                        |                        |
  | POST /gnap/continue/:id                        |
  | Authorization: GNAP <token>                     |
  | { interact_ref }       |                        |
  |----------------------->|                        |
  |                        |                        |
  | 200 { access_token,    |                        |
  |   subject }            |                        |
  |<-----------------------|                        |
```

### Interactive — User Code Flow (Device)

For devices without a browser (IoT, CLI, TV apps):

```
Device                  GNAP AS                 User (Browser)
  |                        |                        |
  | POST /gnap/tx          |                        |
  | { interact.start:      |                        |
  |   ["user_code"] }      |                        |
  |----------------------->|                        |
  |                        |                        |
  | 200 { continue,        |                        |
  |   interact.user_code:  |                        |
  |   { code, url } }      |                        |
  |<-----------------------|                        |
  |                        |                        |
  | Display code to user   |                        |
  | "Enter BKDF-WLNM at   |                        |
  |  https://example.com   |                        |
  |  /.well-known/gnap/    |                        |
  |  device"               |                        |
  |                        |  GET /gnap/device      |
  |                        |<-----------------------|
  |                        |                        |
  |                        |  Enter code form       |
  |                        |----------------------->|
  |                        |                        |
  |                        |  POST code             |
  |                        |<-----------------------|
  |                        |                        |
  |                        |  Redirect to consent   |
  |                        |----------------------->|
  |                        |                        |
  |                        |  User approves         |
  |                        |<-----------------------|
  |                        |                        |
  | Poll: POST /continue/:id                       |
  | Authorization: GNAP <token>                     |
  |----------------------->|                        |
  |                        |                        |
  | 200 { access_token }   |                        |
  |<-----------------------|                        |
```

### Interactive — Push Finish

When `interact.finish.method` is `"push"`, the AS calls back the client instead of redirecting the user:

```json
{
  "interact": {
    "start": ["redirect"],
    "finish": {
      "method": "push",
      "uri": "https://client.example.com/callback",
      "nonce": "LKLTI25DK82FX4T4QFZC"
    }
  }
}
```

After user consent, the AS POSTs to the client's `finish.uri` with the interaction hash and `interact_ref`, instead of redirecting the user's browser.

## Token Management (RFC 9635 Section 6)

Access token responses include a `manage` URL:

```json
{
  "access_token": {
    "value": "Biscuit_base64url...",
    "access": [{ "type": "api", "actions": ["read"] }],
    "expires_in": 3600,
    "manage": "https://otoroshi.example.com/.well-known/gnap/token/abc-123"
  }
}
```

### Token Rotation (POST)

Revokes the current token and issues a new one:

```bash
curl -X POST https://otoroshi.example.com/.well-known/gnap/token/abc-123 \
  -H "Authorization: GNAP <current-token>"
```

Returns a new access token with a new `manage` URL.

### Token Revocation (DELETE)

```bash
curl -X DELETE https://otoroshi.example.com/.well-known/gnap/token/abc-123 \
  -H "Authorization: GNAP <current-token>"
```

Returns `204 No Content`.

## Grant Cancellation (RFC 9635 Section 5.4)

A client can cancel an ongoing grant by sending DELETE to the continue URI:

```bash
curl -X DELETE https://otoroshi.example.com/.well-known/gnap/continue/grant-id \
  -H "Authorization: GNAP <continue-token>"
```

This revokes any issued tokens and removes the grant. Returns `204 No Content`.

## Multiple Access Tokens (RFC 9635 Section 2.1.1)

Request multiple labeled tokens in a single grant:

```json
{
  "access_token": [
    { "access": [{ "type": "api", "actions": ["read"] }], "label": "read_token" },
    { "access": [{ "type": "api", "actions": ["write"] }], "label": "write_token" }
  ],
  "client": "client-ref"
}
```

Response:

```json
{
  "access_token": {
    "read_token": {
      "value": "Biscuit...",
      "access": [{ "type": "api", "actions": ["read"] }],
      "expires_in": 3600,
      "manage": "https://..."
    },
    "write_token": {
      "value": "Biscuit...",
      "access": [{ "type": "api", "actions": ["write"] }],
      "expires_in": 3600,
      "manage": "https://..."
    }
  }
}
```

## Discovery Document (RFC 9635 Section 9)

```bash
curl https://otoroshi.example.com/.well-known/gnap-as-rs
```

```json
{
  "grant_request_endpoint": "https://otoroshi.example.com/.well-known/gnap/tx",
  "token_management_endpoint": "https://otoroshi.example.com/.well-known/gnap/token",
  "interaction_start_modes_supported": ["redirect", "user_code"],
  "interaction_finish_methods_supported": ["redirect", "push"],
  "key_proofs_supported": [],
  "sub_id_formats_supported": ["opaque"],
  "token_formats_supported": ["biscuit"]
}
```

## Subject Information (RFC 9635 Section 3.4)

When a grant is approved through user interaction, the token response includes subject information:

```json
{
  "access_token": { "value": "...", "..." },
  "subject": {
    "sub": "user@example.com",
    "email": "user@example.com",
    "name": "John Doe"
  }
}
```

If an Otoroshi auth module is configured (`auth_module_ref`), the subject info is populated from the authenticated user's session. Otherwise, a synthetic subject ID is generated.

## Biscuit Token Structure

Each issued Biscuit token contains:

### Authority block facts
- `grant_id("authority", "<grant-id>")` — the GNAP grant that issued this token
- `issued_at("authority", <timestamp>)` — issuance time
- `user("authority", "<sub>")` — subject identifier (if available)
- `email("authority", "<email>")` — user email (if available)
- `username("authority", "<name>")` — user name (if available)
- `role("authority", "<role>")` — user roles (if available)
- `resource_type("authority", "<type>")` — structured access right type
- `action("authority", "<action>")` — permitted actions
- `location("authority", "<url>")` — permitted locations
- `access_ref("authority", "<ref>")` — reference access rights

### Authority block checks
- Expiration check: `check if time($t), $t < <expiration-timestamp>`

### Validator-injected facts (at validation time)
- `resource("<request-path>")`
- `operation("<HTTP-method>")`
- `req_method("<method>")`
- `req_path("<path>")`
- `req_domain("<domain>")`
- Current time via `set_time()`

### Custom Datalog policies

The Access Validator plugin supports adding custom checks, facts, and rules via configuration. For example:

```json
{
  "checks": ["check if action(\"read\")"],
  "facts": ["allowed_domain(\"api.example.com\")"],
  "rules": ["allow if resource_type($t), action($a), $a == \"read\""]
}
```

## Error Handling (RFC 9635 Section 3.6)

All errors follow the RFC format:

```json
{
  "error": "invalid_request",
  "description": "Content-Type must be application/json"
}
```

Supported error codes:
- `invalid_request` — malformed request
- `invalid_client` — unknown or invalid client
- `invalid_interaction` — interaction-related error
- `invalid_continuation` — continuation token error
- `user_denied` — user denied the grant
- `request_denied` — AS policy denied the request
- `unknown_interaction` — interaction reference not found
- `too_fast` — client is polling too quickly
- `too_many_attempts` — too many failed attempts

## Security Considerations

### Implemented
- **CSRF protection** on consent form (server-side token validation)
- **XSS prevention** via HTML escaping on all rendered values
- **Continue token rotation** — tokens are rotated on each poll to prevent replay
- **Interaction hash** (RFC 9635 §4.2.3) — SHA-256/SHA-512 hash of client nonce, server nonce, interact_ref, and grant endpoint
- **Token revocation** tracking in the Otoroshi datastore
- **Open redirect prevention** — finish URIs must be absolute HTTP(S) URLs
- **interact_ref validation** — the interact_ref in the continue request must match the stored value
- **Biscuit cryptographic verification** — Ed25519 signature verification on every token
- **Datalog-based authorization** — fine-grained access control via Biscuit policies

### Not Implemented (Future Enhancements)
- **Key proofing enforcement** (httpsig, mtls, dpop) — RFC 9635 §7.3. The client's key proof method is parsed but not verified. Biscuit tokens provide strong cryptographic guarantees at the token level, but request-level proof of possession is not enforced. For production use, consider combining with Otoroshi's mTLS or HMAC validation plugins.
- **Grant modification** (PATCH on continue URI) — RFC 9635 §5.3. Biscuit tokens are immutable once issued; clients should cancel and re-request.
- **Instance ID rotation** — RFC 9635 §5.1. Client instance identifiers are not rotated.
- **Interaction hints** — RFC 9635 §2.5.3.

## File Structure

```
otoroshi/app/next/plugins/
├── gnap.scala            # Domain models, crypto, Biscuit emitter, config, datastore, validation, responses
├── gnapendpoints.scala   # GnapAuthorizationServer (NgRequestSink) — all GNAP endpoints
└── gnapvalidator.scala   # GnapAccessValidator (NgAccessValidator) — per-route token validation

otoroshi/test/functional/
└── GnapSpec.scala         # Unit tests (62 tests)
```

## RFC 9635 Coverage

| Section | Feature | Status |
|---------|---------|--------|
| §2 | Grant request | ✅ |
| §2.1 | Access token request (single + multiple) | ✅ |
| §2.1.1 | Access rights (structured + reference) | ✅ |
| §2.1.2 | Token flags | ✅ (model) |
| §2.2 | Subject information request | ✅ |
| §2.3 | Client instance (inline + reference) | ✅ |
| §2.5 | Interaction request | ✅ |
| §2.5.1 | Interaction start modes (redirect, user_code) | ✅ |
| §2.5.2 | Interaction finish (redirect, push) | ✅ |
| §3.1 | Grant response with continuation | ✅ |
| §3.2 | Access token response | ✅ |
| §3.2.1 | Multiple access tokens | ✅ |
| §3.4 | Subject information in response | ✅ |
| §3.6 | Error responses | ✅ |
| §4.2.3 | Interaction hash | ✅ |
| §5.1 | Continuation (polling + token rotation) | ✅ |
| §5.4 | Grant cancellation (DELETE) | ✅ |
| §6.1 | Token rotation (POST) | ✅ |
| §6.2 | Token revocation (DELETE) | ✅ |
| §7.1 | Client key (JWK, cert, cert#S256) | ✅ (model) |
| §7.3 | Key proofing methods | ⚠️ Parsed, not enforced |
| §9 | Discovery document | ✅ |
| §5.3 | Grant modification (PATCH) | ❌ N/A with Biscuit |
