# Messaging Platform

Java 27 and Spring Boot 4.1.2. The root POM manages versions and aggregates three independently deployable Java services. The Angular frontend is a separate Node module.

- **Frontend App (Relay)** on port 3000 provides signup, Keycloak sign-in and direct messaging. Its Node server serves Angular and proxies browser API requests.
- **User Service** on port 8081 provisions Keycloak identities, sends account setup emails through Keycloak and stores application profiles in PostgreSQL.
- **Messaging Service** on port 8082 stores direct conversations and messages in its own PostgreSQL database using Spring Data JPA.
- **Realtime Gateway** on port 8083 exposes authenticated `GET /api/realtime/status` and a WebSocket `/ws` that answers `ping` with `pong`.

Realtime message delivery and group conversations are future work. The edge proxy can route the current services directly. Add an API gateway or a shared event-contract module when routing or event consumers need one.

## Run locally

Use Docker with Compose and BuildKit. From the repository root, create an untracked `.env` once with a separate random value for each key.

```sh
umask 077
for key in KEYCLOAK_DB_PASSWORD PROFILE_DB_PASSWORD MESSAGING_DB_PASSWORD KEYCLOAK_BOOTSTRAP_ADMIN_PASSWORD KEYCLOAK_ADMIN_CLIENT_SECRET; do
  printf '%s=%s\n' "$key" "$(openssl rand -hex 24)"
done > .env
printf 'FRONTEND_COOKIE_KEY=%s\n' "$(openssl rand -hex 32)" >> .env
docker compose up --build -d
```

If you already have a local `.env`, add any missing settings once, then rerun `docker compose up --build -d`.

```sh
if ! grep -q '^MESSAGING_DB_PASSWORD=.' .env; then
  printf '\nMESSAGING_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> .env
fi
if ! grep -q '^FRONTEND_COOKIE_KEY=.' .env; then
  printf '\nFRONTEND_COOKIE_KEY=%s\n' "$(openssl rand -hex 32)" >> .env
fi
```

Keycloak is at [localhost:8080](http://localhost:8080). Its local admin username is `admin`. The realm import creates the public `messaging-client` and the confidential `user-provisioner`. The latter has the realm-local `manage-users` role and the `kc-admin-api` client scope. The profile, messaging and Keycloak databases each have their own credentials and volumes. The development ports bind to loopback.

```sh
curl -fsS http://localhost:8080/realms/messaging/.well-known/openid-configuration >/dev/null
curl -fsS http://localhost:8081/actuator/health
curl -fsS http://localhost:8082/actuator/health
curl -fsS http://localhost:8083/actuator/health

curl -i http://localhost:8081/api/users/register \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 0c75f79e-51a0-4bbc-b387-beb6d4c969aa' \
  -d '{"email":"ada@example.com","firstName":"Ada","lastName":"Lovelace"}'
```

Open the setup message in [Mailpit](http://localhost:8025). Follow the Keycloak link to verify the email and set the password. For interactive login, use Authorization Code with PKCE (S256), client `messaging-client` and a registered redirect URI. The example realm allows `http://localhost:3000/callback` for a local client. Send the resulting access token in the `Authorization: Bearer` header to call `GET /api/users/me`, the status endpoints or the WebSocket upgrade. Native WebSocket clients can set this header. Browser WebSocket clients will need a separate authenticated handshake design.

The services verify JWT signature, timestamps, issuer and the configured `messaging-platform` audience. The realm maps that audience to public-client access tokens. The issuer is the public Keycloak URL, while Compose uses the internal Keycloak URL for signing keys and administrative calls.

`docker compose down` stops the stack and preserves the databases. Keep the same local secrets across restarts. Keycloak imports the realm on first startup, so update an existing realm through Keycloak administration if you change its configuration.

## Relay browser app

Open [Relay](http://localhost:3000). Create an account, follow the setup link in [Mailpit](http://localhost:8025), then sign in through Keycloak. To try a direct message, sign in with a second account in a private window or separate browser profile. Copy that account’s ID from **Copy my ID** and paste it into **New chat** in the first window. Open the conversation in both windows to exchange messages. Relay refreshes the conversation every few seconds.

The frontend module is in `frontend-app/`. Use Node 24.15 or later within Node 24 and run `npm ci && npm run build` there to compile it. Its production Dockerfile builds Angular and serves it with the same-origin Node API.

The Node server exchanges the Keycloak code using PKCE, state and nonce. It puts the access and refresh tokens in separate AES-GCM-encrypted, HttpOnly, SameSite=Lax cookies. The server forwards access tokens to the Java APIs and refreshes them as needed. Browser write requests must have the configured app origin. Production cookies add Secure and the `__Host-` prefix. Keep the cookie key stable across replicas and rotate it when you intend to invalidate existing browser sessions.

For Dokploy, deploy the frontend image as a separate Docker application on port 3000 with `/health` as its health check. Set `APP_ORIGIN` to its public HTTPS origin, `OIDC_ISSUER_URI` to the public HTTPS realm URL and optionally `OIDC_BACKCHANNEL_URI` to an HTTPS URL for that same realm. Set `USER_SERVICE_BASE_URL`, `MESSAGING_SERVICE_BASE_URL` and the 32-byte hex `COOKIE_ENCRYPTION_KEY` as environment settings. Use separate public hostnames for the frontend and Keycloak. Set the client’s exact redirect URI to `<APP_ORIGIN>/callback`, require PKCE S256 and map the existing `messaging-platform` API audience into its access tokens. `OIDC_CLIENT_ID` defaults to `messaging-client`. Set `OIDC_CLIENT_SECRET` if you use a confidential frontend client. The path-filtered frontend workflow builds the image and publishes it to GHCR on `main`.

## Registration retries

- Generate a fresh random UUID for each signup and retain it with the request until the operation completes. Replay the same normalized email and names with the same key after a timeout or a `503` response. The API returns `409` for a reused key with changed input and `400` for invalid or unknown fields.
- The User Service commits a reservation before contacting Keycloak. A retry searches for the reservation's generated Keycloak username and verifies the matching email before saving the profile. PostgreSQL uniqueness and row locking prevent competing requests from creating duplicate profiles. An email already reserved under another key, or already owned by an unrelated Keycloak identity, receives the same generic `202` as a successful signup.
- The profile commits before the setup email is sent. A retry can resume that email step. If Keycloak sends mail and the response or final database commit is lost, a retry may send a second link. After a completed signup, use Keycloak's password recovery if the setup link has expired.
- Configure rate limits and request size limits at the edge before exposing public signup. Use TLS, production SMTP and a dedicated realm-scoped provisioner secret. The User Service accepts name and email fields only. Credentials are entered directly in Keycloak.

## Direct messaging

Send a Keycloak access token as `Authorization: Bearer <token>`. Use the recipient's `subject` from their `GET /api/users/me` response.

- `POST /api/conversations` takes `{"recipientSubject":"<subject>"}` and returns the conversation for that pair. New conversations require a provisioned recipient profile. The Messaging Service forwards the caller's bearer token to the User Service's authenticated `GET /api/users/exists?subject=…` endpoint, which returns only whether the profile exists.
- `GET /api/conversations?page=0&limit=50` lists your conversations, newest first.
- `POST /api/conversations/{id}/messages` takes `{"clientMessageId":"<uuid>","body":"Hello"}` and returns the stored message. Reuse the same UUID on retries. Reusing it in the same conversation with different text returns `409`.
- `GET /api/conversations/{id}/messages?limit=50` returns newest-first history and a `nextBefore` value. Pass that value as the `before` parameter to retrieve older messages. Only the two participants can send or read messages.

Spring Data JPA maps both entities and reads history. PostgreSQL uniqueness constraints and native inserts handle concurrent conversation creation and message retries. Flyway owns the schema.

## Build and deploy

```sh
mvn -B -ntp verify
mvn -B -ntp -pl user-service -am verify
docker build -f user-service/Dockerfile -t messaging-user-service .
```

The GitHub workflows select a service when its directory changes. A root POM, Docker context configuration or shared workflow change selects all three. Each selected workflow verifies its own Maven module and builds its image. On `main`, it publishes `ghcr.io/<owner>/<repository>/<service>` with the commit SHA and `main` tags. Infrastructure changes are managed separately.

For Dokploy, create one Docker-source application per service and configure private GHCR pull credentials if needed. Point each app at its `:main` image, route to the service port above and use `/actuator/health` for HTTP health checks. CI publishes Linux amd64 images by default. If your Dokploy host uses ARM64, configure the image build platform and CI runner accordingly.

To enable deployment for a service, add its Dokploy application ID as `DOKPLOY_USER_SERVICE_APP_ID`, `DOKPLOY_MESSAGING_SERVICE_APP_ID` or `DOKPLOY_REALTIME_GATEWAY_APP_ID` in GitHub Actions variables. Set `DOKPLOY_URL` to the HTTPS base URL and store the deployment API key in `DOKPLOY_API_KEY`. The selected workflow requests deployment through `application.deploy` after publishing the image. Keep repository-wide Dokploy push triggers disabled. Use a commit SHA image tag for a manual rollback. See the [Dokploy external-registry setup](https://docs.dokploy.com/docs/core/applications/going-production#external-registry).

Set `OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI` and, if changed, `OIDC_AUDIENCE` in every service. In the User Service also set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `KEYCLOAK_ADMIN_BASE_URL` and `KEYCLOAK_ADMIN_CLIENT_SECRET`. `KEYCLOAK_REALM` and `KEYCLOAK_ADMIN_CLIENT_ID` default to `messaging` and `user-provisioner`. Keep the databases and management paths private, configure the reverse proxy for WebSocket upgrades, and use a PostgreSQL profile database role with permission to run Flyway migrations. The local Compose file uses Keycloak development mode and Mailpit.

For the Messaging Service, also set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` and `USER_SERVICE_BASE_URL`. Deploy the User Service version with the profile lookup before the Messaging Service. Use HTTPS or a trusted private network for this internal request.
