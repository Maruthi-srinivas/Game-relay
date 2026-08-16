<<<<<<< HEAD
# Game Chat Room Service

Production-oriented real-time chat backend for multiplayer games. **V1** covers a single Spring Boot node with JWT auth, room management, raw JSON WebSockets, and PostgreSQL message persistence.

Redis, Kafka, presence, typing indicators, acknowledgements, sequence numbers, and multi-node fan-out are intentionally out of scope until later versions.

## Prerequisites

Only [Docker Desktop](https://www.docker.com/products/docker-desktop/) (or Docker Engine + Compose) is required on the host. Java, Maven, and PostgreSQL run inside containers.

## Quick start

```bash
cp .env.example .env
docker compose up --build
```

Wait until `chat-service` is healthy, then:

```bash
curl http://localhost:8080/actuator/health
```

Stop with `docker compose down`. Data is kept in the `postgres_data` volume; add `-v` to wipe it.

## Environment variables

Copy [`.env.example`](.env.example) and adjust as needed:

| Variable | Default | Purpose |
|---|---|---|
| `POSTGRES_USER` | `gamechat` | Database user |
| `POSTGRES_PASSWORD` | `gamechat` | Database password |
| `POSTGRES_DB` | `gamechat` | Database name |
| `JWT_SECRET` | (dev placeholder) | HS256 signing key; must be at least 32 characters |
| `JWT_EXPIRATION` | `86400000` | Access token lifetime in milliseconds |

Do not commit `.env` or production secrets.

## REST API

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
```

History is newest-first. Non-members receive `403`. Missing rooms receive `404`. Duplicate username/email on register receives `409`.

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
  "roomId": "<room-uuid>"
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
  "requestId": "req-3"
}
```

Errors:

```json
{
  "type": "ERROR",
  "requestId": "req-3",
  "code": "FORBIDDEN",
  "message": "Not a member of this room"
}
```

Unsupported event types return `UNSUPPORTED_TYPE`. Malformed JSON returns `BAD_REQUEST` and does not close the socket.

V1 does not implement heartbeats, typing, ACKs, sequence numbers, or missed-message sync.

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
    chat/                 WebSocket handler, persistence, history
    common/               Errors and shared DTOs
docker-compose.yml        chat-service + postgres
```

## Tests

From `backend/` with Docker available (Testcontainers starts PostgreSQL):

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "%cd%":/app -w /app maven:3.9-eclipse-temurin-21 mvn test
```

On Linux/macOS replace `%cd%` with `$(pwd)`.

Unit tests cover JWT, registration hashing, room membership rules, and message validation. The integration test covers REST auth/rooms/history and a two-client WebSocket broadcast that is persisted.

## V1 definition of done

- `docker compose up --build` starts the stack with no host Java/Maven/Postgres install
- Register/login returns a JWT
- Users can create, join, leave, and list room members
- An authenticated WebSocket can join a room and send a message
- Members connected to this node receive the message in real time
- Messages are stored in PostgreSQL and returned by paginated history
- Unauthorized room access is rejected on REST and WebSocket
=======
# Game-relay
A real-time game communication backend exploring WebSockets, concurrent connections, chat rooms, player presence, message delivery, and scalable networking.
>>>>>>> 48528318bceff2e8c84801d0de6432b4562b18d3
