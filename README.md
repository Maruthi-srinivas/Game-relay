# Game Chat Room Service

Production-oriented real-time chat backend for multiplayer games. **V3** runs two Spring Boot chat nodes behind nginx, with JWT auth, room management, JSON WebSockets, PostgreSQL persistence, per-room sequence numbers, missed-message sync, heartbeats, typing, persist ACKs, Redis Pub/Sub fan-out, and Redis presence.

Kafka is intentionally out of scope. Postgres is the source of truth; Redis is the live bus so a `MESSAGE` on node A reaches sockets on node B.

## Prerequisites

Only [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose) is required on the host. Java, Maven, Node, npm, PostgreSQL, and Redis all run inside containers. The frontend image installs npm packages at **build time** only; nothing is installed on the host.

## Quick start

```bash
cp .env.example .env
docker compose up --build
```

Wait until the stack is up, then open the chat UI at [http://localhost:8081](http://localhost:8081) or:

```bash
curl http://localhost:8080/actuator/health
```

The UI is a React SPA built inside Docker (no Node/npm on the host). nginx on port 8081 serves it and proxies `/api` and `/ws` to the **gateway**, which load-balances `chat-a` and `chat-b`. Direct API/WS calls on port 8080 go through the same gateway. Each browser tab has its own session (`sessionStorage`), so two tabs can be two users and may land on different replicas; live frames still arrive.

Demo path: register in tab A → create a room → copy the room id → register in tab B → join that id. Send from A while B is disconnected, then open the room in B: missed messages sync. Keep both connected for live frames, presence dots, and typing.

Stop with `docker compose down`. Data is kept in the `postgres_data` volume; add `-v` to wipe it.

## Environment variables

Copy [`.env.example`](.env.example) and adjust as needed:

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_USER` | `gamechat` | Database user |
| `POSTGRES_PASSWORD` | `gamechat` | Database password |
| `POSTGRES_DB` | `gamechat` | Database name |
| `REDIS_HOST` | `localhost` | Redis host when running the backend outside Compose (`redis` inside Compose) |
| `REDIS_PORT` | `6379` | Redis port |
| `JWT_SECRET` | (dev placeholder) | HS256 signing key; must be at least 32 characters |
| `JWT_EXPIRATION` | `86400000` | Access token lifetime in milliseconds |

Do not commit `.env` or production secrets.

## REST API

Full request/response examples for every REST and WebSocket call are in [API.md](API.md).

All endpoints except register/login require `Authorization: Bearer <token>`. Identity is taken from the JWT, never from a client-supplied sender id.

### Register

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"
```

`201`:

```json
{
  "token": "<jwt>",
  "userId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "username": "alice"
}
```

### Login

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"alice\",\"password\":\"password123\"}"
```

### Rooms

```bash
TOKEN="<jwt>"

curl -s -X POST http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Arena\",\"type\":\"GAME_ROOM\"}"

curl -s http://localhost:8080/api/rooms \
  -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:8080/api/rooms/<roomId> \
  -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:8080/api/rooms/<roomId>/join \
  -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:8080/api/rooms/<roomId>/leave \
  -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:8080/api/rooms/<roomId>/members \
  -H "Authorization: Bearer $TOKEN"

curl -s "http://localhost:8080/api/rooms/<roomId>/messages?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"

curl -s "http://localhost:8080/api/rooms/<roomId>/messages?afterSequence=0&size=50" \
  -H "Authorization: Bearer $TOKEN"
```

`GET /api/rooms` lists rooms the caller belongs to. History is newest-first unless you pass `afterSequence` (oldest-first sync). Each message includes a per-room `sequenceNumber`. Non-members receive `403`. Missing rooms receive `404`. Duplicate username/email on register receives `409`.

## WebSocket protocol

Endpoint: `ws://localhost:8080/ws/chat`

Authenticate with `Authorization: Bearer <token>` or `?token=<jwt>`.

Envelope: JSON object with a `type` field and optional `requestId`.

### Client → server

Join (must already be a REST member of the room):

```json
{
  "type": "JOIN_ROOM",
  "requestId": "req-1",
  "roomId": "<room-uuid>",
  "afterSequence": 0
}
```

Leave the socket's in-memory subscription (does not change REST membership):

```json
{
  "type": "LEAVE_ROOM",
  "requestId": "req-2",
  "roomId": "<room-uuid>"
}
```

Send:

```json
{
  "type": "SEND_MESSAGE",
  "requestId": "req-3",
  "roomId": "<room-uuid>",
  "content": "Enemy approaching"
}
```

### Server → client

On connect:

```json
{
  "type": "CONNECTED",
  "userId": "<user-uuid>",
  "username": "alice"
}
```

Broadcast after persist:

```json
{
  "type": "MESSAGE",
  "messageId": "<message-uuid>",
  "roomId": "<room-uuid>",
  "senderId": "<user-uuid>",
  "content": "Enemy approaching",
  "timestamp": "2026-08-16T09:30:10Z",
  "sequenceNumber": 1,
  "requestId": "req-3"
}
```

The sender also receives `ACK` with the same `messageId` / `sequenceNumber` before the room broadcast. `JOIN_ROOM` with `afterSequence` returns `HISTORY_SYNC` for missed rows. Presence, typing, and `PING`/`PONG` are documented in [API.md](API.md).

Errors:

```json
{
  "type": "ERROR",
  "requestId": "req-3",
  "code": "FORBIDDEN",
  "message": "Not a member of this room"
}
```

Unsupported event types return `UNSUPPORTED_TYPE`. Malformed JSON returns `BAD_REQUEST` and does not close the socket. Idle sockets are closed after 60s with no inbound frames.

Example with [websocat](https://github.com/vi/websocat):

```bash
websocat "ws://localhost:8080/ws/chat?token=$TOKEN"
```

## Project layout

```
backend/                 Spring Boot 3 / Java 21 application
  Dockerfile              Multi-stage Maven build + JRE runtime
  src/main/java/com/example/gamechat/
    auth/                 Register, login, JWT, Spring Security
    room/                 Room REST + membership
    chat/                 WebSocket handler, persistence, history, Redis bus, presence
    common/               Errors and shared DTOs
frontend/                 React + Vite SPA (built in Docker, served by nginx)
  Dockerfile              Multi-stage Node build + nginx:alpine reverse proxy to gateway
  src/                    Auth, rooms, WebSocket chat, presence, typing
gateway/                  nginx load balancer for chat-a and chat-b
docker-compose.yml        frontend + gateway + chat-a + chat-b + postgres + redis
```

## Tests

From `backend/` with Docker available (Testcontainers starts PostgreSQL and Redis):

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "%cd%":/app -w /app maven:3.9-eclipse-temurin-21 mvn test
```

On Linux/macOS replace `%cd%` with `$(pwd)`.

Unit tests cover JWT, registration hashing, room membership, sequence allocation, Redis presence join/leave, and message validation. The integration tests cover REST auth/rooms/history, two-client live broadcast, missed-message `HISTORY_SYNC`, and Redis fan-out to local sockets.

## V3 definition of done

- `docker compose up --build` starts postgres, redis, chat-a, chat-b, gateway, and frontend
- Chat UI is available at `http://localhost:8081`; API/WS at `http://localhost:8080` through the gateway
- Register/login returns a JWT
- Users can list their rooms, create, join, leave, and list room members
- Messages have a per-room `sequenceNumber`; REST supports `afterSequence`
- An authenticated WebSocket can `JOIN_ROOM` with `afterSequence` and receive `HISTORY_SYNC`
- Owner can send while a member is disconnected; the member sees the message on connect
- Members on **either** chat node receive live `MESSAGE` frames (Redis Pub/Sub)
- Sender receives persist `ACK`; presence (Redis) and typing work across nodes
- REST leave drops that user's sockets on both nodes
- Idle sockets are dropped; the UI pings every 20s and reconnects with catch-up
- Unauthorized room access is rejected on REST and WebSocket
- No Kafka / Zookeeper containers
