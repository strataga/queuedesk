# Verification — 4 October 2026

Executed at `/Users/jason/projects/queuedesk` using JDK 25.0.4.1, custom Maven wrapper 3.9.11 and Spring Boot 4.0.8.

- `./mvnw test`: **5 tests passed**, zero failures/errors/skips. Plain domain rules; concurrent repeated admission; capacity and fresh-store loss; HTTP admission/read/repeat/conflict and invalid inputs.
- `./mvnw package`: **passed**, executable `target/queuedesk-0.1.0.jar` built.
- `./scripts/smoke`: **passed**. Deterministic dry-run output; JSON payloads for all four scenarios; rejection of external URLs and over-cap settings; live loopback normal/duplicate/invalid/burst scenarios; GET, repeat identity, conflict, unknown-field validation, limits and 404; restart loss demonstrated. Both spawned test servers stopped.
- `./mvnw spring-boot:run`: **passed** on 127.0.0.1:8080. README first synthetic submission returned 202, GET found RECEIVED / NOT_CONFIGURED, exact repeat returned 200. Server stopped after verification.
- Source Markdown, PDF and original ZIP match recorded byte sizes and SHA-256 hashes at the final path. All seven extracted diagrams match the provenance manifest. Library identity/version extended attributes are retained on original source files.

These tests cover in-memory admission and local HTTP behavior. They provide no evidence of database durability, MQ delivery, worker processing, simulated settlement or recovery from distributed-system failures. Those milestones remain unimplemented.
