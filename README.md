# Game Chat Room Service

Production-oriented real-time chat backend for multiplayer games. **V6** runs two Spring Boot chat nodes behind an HTTPS nginx gateway, with short-lived JWTs and rotating refresh cookies, typed rooms, rich presence, moderation, Redis live fan-out, Kafka event logging, and Prometheus/Grafana.

Postgres is the source of truth. Redis is the live bus so a `MESSAGE` on node A reaches sockets on node B. Kafka is a post-commit event log only.

## Prerequisites

Only [Docker Desktop](https://www.docker.com/products/docker-desktop/) is required on the host. Java, Maven, Node, PostgreSQL, Redis, Kafka, Prometheus, and Grafana all run in containers.

## Quick start

```bash
cp .env.example .env
docker compose up --build
```

Wait until the stack is up, then open the chat UI at [https://localhost:8081](https://localhost:8081). The first visit uses a local CA created in `certs/`. Windows may warn about the certificate; optionally import `certs/ca.crt` into Trusted Root Certification Authorities.

```bash
curl -k https://localhost:8080/actuator/health
```

API and WebSocket on port 8080 go through the same TLS gateway. Internal Docker hops (frontend → gateway → chat-a/chat-b, and chat → Postgres/Redis/Kafka) stay HTTP.

Stop with `docker compose down`. Data is kept in the `postgres_data` volume; add `-v` to wipe it.

Load test (optional):

```bash
docker compose --profile loadtest run --rm k6
```

Grafana is at [http://localhost:3000](http://localhost:3000) (anonymous viewer, or admin/admin). Prometheus is bound to [http://127.0.0.1:9090](http://127.0.0.1:9090) only.

## Environment variables

Copy [`.env.example`](.env.example):

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_USER` | `gamechat` | Database user |
| `POSTGRES_PASSWORD` | `gamechat` | Database password |
| `POSTGRES_DB` | `gamechat` | Database name |
| `JWT_SECRET` | (dev placeholder) | HS256 signing key; at least 32 characters |
| `JWT_ACCESS_EXPIRATION` | `900000` | Access token lifetime in milliseconds (15 minutes) |
| `JWT_REFRESH_EXPIRATION` | `604800000` | Refresh cookie lifetime (7 days) |
| `JWT_REFRESH_COOKIE_SECURE` | `true` | Set `Secure` on the refresh cookie (HTTPS) |
| `PROM_USER` | `prom` | HTTP Basic user for `/actuator/prometheus` |
| `PROM_PASSWORD` | `prompass` | HTTP Basic password for Prometheus scrapes |
| `MINIO_ROOT_USER` | `minio` | MinIO root user (internal object store) |
| `MINIO_ROOT_PASSWORD` | `minio12345` | MinIO root password |

Do not commit `.env` or production secrets.

## REST API

Full request/response examples are in [API.md](API.md).

Register and login return `{ accessToken, expiresIn, userId, username }` and set an HttpOnly `refresh_token` cookie (`Path=/api/auth`). Use `Authorization: Bearer <accessToken>` on other REST calls. `POST /api/auth/refresh` rotates tokens. `POST /api/auth/logout` revokes the refresh family and denylists the access `jti`.

## WebSocket protocol

Endpoint: `wss://localhost:8080/ws/chat` (or same-origin `/ws/chat` from the UI).

Do **not** put the JWT in the query string. After the socket opens, send:

```json
{ "type": "AUTH", "token": "<accessToken>" }
```

The server then emits `CONNECTED`. Unauthenticated sockets are closed after 3 seconds.

## Project layout

```
backend/                 Spring Boot 3 / Java 21 application
frontend/                 React + Vite SPA (built in Docker, served by nginx with TLS)
gateway/                  nginx TLS terminator + load balancer for chat-a and chat-b
certs/                    generate.sh (local CA); server certs written at compose start
observability/            Prometheus, Grafana, Loki, Promtail, k6
docker-compose.yml        frontend + gateway + chat-a + chat-b + postgres + redis + kafka + minio + audit-consumer + metrics + logs
```

## Tests

From `backend/` with Docker available (Testcontainers starts PostgreSQL and Redis):

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "%cd%":/app -w /app maven:3.9-eclipse-temurin-21 mvn test
```

On Linux/macOS replace `%cd%` with `$(pwd)`.

## V6 definition of done

V6 remains the baseline: two chat nodes, Redis live fan-out, Kafka post-commit log, short-lived JWTs, room types, presence, mute/kick/report.

## V7 definition of done

- Ban/unban and promote/demote; banned users cannot rejoin; auto-join skips bans
- `MARK_READ` / unread badges (`unreadCount`); idempotent `SEND_MESSAGE` via `requestId`
- Idle presence sweeper sets `AWAY`
- In-room FTS search (`GET /api/rooms/{id}/messages/search?q=`)
- `ADD_REACTION` / `REMOVE_REACTION` with unique (message, user, emoji)
- MinIO (or local disk in tests) attachments; JWT download; muted members cannot attach
- UI: member actions, report inbox, unread, ticks, search, reactions, composer attach

## V8 definition of done

- Loki + Promtail collect logs from chat-a, chat-b, gateway, frontend, audit-consumer
- Grafana Game Chat dashboard includes a Loki logs row
- Dedicated `audit-consumer` (`SPRING_PROFILES_ACTIVE=audit`, group `gamechat-audit`); chat nodes set `KAFKA_LOG_LISTENER=false`
- Prometheus scrapes `/actuator/prometheus` with basic auth and is bound to `127.0.0.1:9090`
- k6 two-user load script under `docker compose --profile loadtest run --rm k6`
