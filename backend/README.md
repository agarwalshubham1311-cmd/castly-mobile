# Castly Java API — implementation foundation

This directory is the initial Java 21 Spring Boot API skeleton, **not** the completed Milestone 1 migration or Milestone 2 feature. It includes JWT resource-server security, JDBC configuration and participant-scoped booking reads. Booking creation, transitions, messaging, API integration and tests remain to be implemented. Do not point production clients at this service.

Configure environment variables from .env.example (never commit credentials). Use a dedicated restricted database user. Run with Maven and Java 21: `mvn spring-boot:run`. The API expects Supabase-issued JWTs; verify the project's signing-key/JWKS configuration before deploying. Do not run the pending SQL migration without review. The existing mobile UI continues to use Supabase until an explicitly tested feature-by-feature switch.
