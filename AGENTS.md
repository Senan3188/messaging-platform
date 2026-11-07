# Messaging Platform

## Project layout

- Use Java 27 and Maven for the Spring Boot services. The root POM aggregates `user-service`, `messaging-service` and `realtime-gateway`.
- Build the Angular frontend and its Node server from `frontend-app/` with Node 24. The frontend has its own Dockerfile and workflow.
- Keep each service independently buildable. Update the relevant path filters when adding a shared build dependency.

## Application boundaries

- The User Service orchestrates signup and stores profiles. Keycloak owns password setup, email verification and sign-in. Keep passwords out of application request models and storage.
- Preserve registration retries across partial failures. Keep profile migrations in the User Service and messaging migrations in the Messaging Service. Add a new Flyway migration for schema changes.
- Use Spring Data JPA in the Messaging Service. Keep the database constraints that enforce one direct conversation per pair and one message per client message ID within its sender and conversation scope.
- Take the sender identity from the validated JWT subject. Verify conversation membership on each message read and write.
- Route Angular API calls through the same-origin Node server. Keep tokens in encrypted HttpOnly cookies and preserve origin checks on writes.

## Checks

- Run `mvn -B -ntp verify` from the root, or `mvn -B -ntp -pl messaging-service -am verify` for a focused build.
- Run `npm ci` and `npm run build` in `frontend-app/` for frontend changes.
- Use the README to start the local Compose stack and exercise signup and messaging. Keep local secrets in the ignored `.env` and document new variables in `.env.example` with empty example values.
