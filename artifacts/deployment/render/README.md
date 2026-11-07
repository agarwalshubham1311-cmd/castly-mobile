# Local Render release evidence

See [validation report](../../../docs/deployment/VALIDATION_REPORT.md) and [deployment runbook](../../../docs/deployment/RENDER_RELEASE.md).

`validation.json` records counts and explicit limitations. Logs use only local fixtures/synthetic data; trailing whitespace and HTTP line endings are normalized for review. `docker-clean-build-blocked.log` preserves the Maven Central DNS failure; `docker-final.log` records the same Dockerfile built with an offline cache-seeded official Maven builder. The official Java 21 runtime, injected PORT, non-root user, minimal health, private 401, read-only database probe and expected negative TLS failures are recorded separately. This is not evidence of a hosted deployment or a clean network build on Render.

`screenshots-manifest.json` describes the 30 fresh local UI captures that were decoded during this run. It is verification metadata, not a new hosted image gallery; original tracked screenshots and all mobile UI source were preserved.
