# ScriptPing — Agent Quick Reference

## Stack
- Java 21, Spring Boot 3.5.7, Maven, PostgreSQL (H2 in tests)
- Go collector: gRPC + protobuf, ICMP ping engine
- Frontend: Thymeleaf + HTMX + vanilla JS (Node.js for JS tests)

## Commands

```sh
# Run locally (PostgreSQL in Docker, Java locally)
docker compose up -d --wait postgres
mvn spring-boot:run

# Full Docker stack (app + collector + postgres)
docker compose up -d --build --wait

# Build and package JAR (from directory with .env)
mvn clean package
java -jar target/scriptping-1.0.0.jar

# Java tests (H2 in-memory, Zabbix mocked, collector disabled)
mvn test -Dcollector.enabled=false

# JS tests (Node.js 18+)
node --test src/test/js/*.test.cjs
```

## Architecture

- **Entrypoint**: `ScriptPingApplication.java` — Spring Boot app, two `ThreadPoolTaskScheduler` beans (indicator + monitor)
- **Go collector**: `collector/cmd/collector/main.go` — ICMP engine, gRPC server (streaming `Connect` + unary `Health`)
- **gRPC proto**: `src/main/proto/collector.proto` — generates Java stubs (protobuf-maven-plugin) and Go code (`collector/gen/`)
- **DB schema**: `src/main/resources/schema.sql` — idempotent DDL (create + alter + drop retired tables)
- **Config loading**: `.env` via `dotenv-java` (loaded before Spring boot), env vars override `.env`
- **Three Docker services**: `app` (Spring), `collector` (Go), `postgres` (PostgreSQL 17 Alpine)

## Key files

| Area | File |
|---|---|
| Java config | `src/main/resources/application.properties` |
| YAML overrides | `src/main/resources/application.yml` |
| Docker compose | `compose.yaml` |
| Java Dockerfile | `Dockerfile` |
| Collector Dockerfile | `collector/Dockerfile` |
| Collector Go module | `collector/go.mod` (Go 1.24.0) |
| .env template | `.env.example` |

## Environment variables (commonly changed)

- `ZABBIX_URL` / `ZABBIX_USERNAME` / `ZABBIX_PASSWORD` — Zabbix API integration
- `POSTGRES_*` — database connection (overridden in compose for container-to-container)
- `PING_INTERVAL_MS` (default 1000) — ping cadence per host
- `PING_TIMEOUT_MS` (default 1500) — per-ping timeout
- `COLLECTOR_ADDRESS` — gRPC endpoint (Unix socket by default)
- `SERVER_ADDRESS` / `PORT` — local bind; `APP_BIND_ADDRESS` / `APP_PORT` — Docker publish

## Gotchas

- `.env` is `.gitignore`d; copy from `.env.example`. Use single quotes for passwords containing `#`, `$`, or spaces.
- `mvn test` sets `collector.enabled=false` — the collector is not available in tests.
- JS tests use `.cjs` extension and Node.js 18+. No npm/pnpm — they run directly with `node --test`.
- Protobuf compilation is automatic via Maven plugin. Do not manually run `protoc`.
- `schema.sql` runs `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` — safe to run repeatedly. Retired checkpoint tables are dropped.
- In Docker Compose, `POSTGRES_HOST` is overridden to `postgres` (service name). Local `.env` values only apply to local Java runs.
- `docker compose down -v` destroys the `postgres_data` volume. Use `down` (without `-v`) to preserve data.
- Changing `.env` requires `docker compose up -d` (not just `restart`) to pick up new values.
- `collector_socket` volume maps to `/run/routiva` in both app and collector containers for Unix socket sharing.

## Code style (from .editorconfig)

- JS: 2-space indent
- Java: 4-space indent
- Go: tab indent, 4-space
- SQL / Dockerfile: 4-space indent

## Prettier (JS)

```sh
# Prettier config at .prettierrc.json — 2 spaces, 100 char printWidth, single quotes
```

## Running the collector health check

```sh
docker compose exec -T collector routiva-collector --health
# Formatted:
docker compose exec -T collector routiva-collector --health | jq '{coletor: .collector_id, hosts_monitorados: .metrics.active_hosts, pings_pendentes: .metrics.pending}'
```

## Logs

- Aggregation: `logs/indicators.log` (local) or `/var/log/routiva/indicators.log` (Compose)
- Collector events: `/var/log/routiva/collector.log` (inside collector container)

## Zabbix integration notes

- Auto-detects Zabbix version: 6.0/6.2 uses JSON-RPC `auth` field; 6.4+ uses Bearer header.
- Inventory syncs every 60s. Failed sync keeps last inventory and shows error on dashboard.
- No API token needed — session-based auth via `user.login` / `user.logout`.
