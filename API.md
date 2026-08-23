# Game Chat Room Service — API Guide (V7/V8)

Base URL: `https://localhost:8080` (TLS nginx gateway in front of `chat-a` and `chat-b`). The UI is `https://localhost:8081`.

Use `-k` with curl until you trust `certs/ca.crt`.

Start the stack first:

```powershell
cd c:\Users\kavur\OneDrive\Desktop\BECHATROOM
docker compose up --build
```

Wait until the service is up, then:

```powershell
curl.exe -k https://localhost:8080/actuator/health
```

Expected:

```json
{"status":"UP"}
```

---

## Authentication

There is no shared/static API key. Register or login returns a short-lived **access token** plus an HttpOnly **refresh cookie**.

```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9....
```

Rules:

- Copy `accessToken` from the register/login JSON.
- Put a space after `Bearer`.
- Default access lifetime is 15 minutes (`JWT_ACCESS_EXPIRATION=900000`). Call `POST /api/auth/refresh` (cookie) before it expires.
- `POST /api/auth/logout` revokes the refresh family and denylists the access `jti`.
- WebSocket must send `{ "type": "AUTH", "token": "<accessToken>" }` as the first frame. Query `?token=` is not used.

In Postman: Authorization → Bearer Token → paste `accessToken` only. For refresh, enable cookies.

---

## Error envelope (REST)

Failed REST calls return JSON:

```json
{
  "code": "UNAUTHORIZED",
  "message": "Authentication required",
  "status": 401
}
```

| HTTP | code | When |
|---|---|---|
| 400 | `BAD_REQUEST` or `VALIDATION_ERROR` | Missing/invalid fields, malformed JSON |
| 401 | `UNAUTHORIZED` | Missing/invalid/expired JWT, bad login |
| 403 | `FORBIDDEN` | Authenticated but not allowed (not a room member, inactive account) |
| 404 | `NOT_FOUND` | Room (or user) does not exist |
| 409 | `CONFLICT` | Duplicate username/email, or room is full |

---

## Health

### GET `/actuator/health`

No auth.

**Response `200`**

```json
{"status":"UP"}
```

```powershell
curl.exe -k -s https://localhost:8080/actuator/health
```

---

## Auth

### POST `/api/auth/register`

No auth. Creates a user and returns a JWT.

**Request body**

| Field | Rules |
|---|---|
| `username` | required, 3–64 chars, letters/digits/underscore only |
| `email` | required, valid email, max 255 |
| `password` | required, 8–72 chars |

**Request example**

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "password123"
}
```

**Response `201 Created`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3YzE5Zj...signature",
  "expiresIn": 900000,
  "userId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
  "username": "alice"
}
```

Save `accessToken`. A `refresh_token` cookie is also set (`HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api/auth`).

**Errors**

- `400 VALIDATION_ERROR` — username/email/password fail validation
- `409 CONFLICT` — `"Username already taken"` or `"Email already registered"`

```powershell
curl.exe -s -X POST https://localhost:8080/api/auth/register `
  -H "Content-Type: application/json" `
  -d "{\"username\":\"alice\",\"email\":\"alice@example.com\",\"password\":\"password123\"}"
```

---

### POST `/api/auth/login`

No auth. Returns a new JWT for an existing user.

**Request body**

```json
{
  "username": "alice",
  "password": "password123"
}
```

Login is by **username**, not email.

**Response `200 OK`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3YzE5Zj...signature",
  "expiresIn": 900000,
  "userId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
  "username": "alice"
}
```

**Errors**

- `400 VALIDATION_ERROR` — blank username or password
- `401 UNAUTHORIZED` — `"Invalid username or password"`
- `403 FORBIDDEN` — `"Account is not active"`

```powershell
curl.exe -s -X POST https://localhost:8080/api/auth/login `
  -H "Content-Type: application/json" `
  -d "{\"username\":\"alice\",\"password\":\"password123\"}"
```

Set the token in PowerShell:

```powershell
$TOKEN = "paste-accessToken-from-register-or-login"
```

### POST `/api/auth/refresh`

No Bearer required. Sends the `refresh_token` cookie, rotates it, returns a new access token.

### POST `/api/auth/logout`

Optional Bearer. Revokes the refresh family and denylists the access `jti`. Clears the cookie.

---

## Rooms

All room endpoints require `Authorization: Bearer <token>`.

Allowed `type` values: `GLOBAL`, `GAME_ROOM`, `TEAM`, `PARTY`, `PRIVATE`.

- `GLOBAL` is seeded (`Global Lobby`) and auto-joined on register/login. You cannot create or leave it.
- `GAME_ROOM` is join-by-id until full.
- `PARTY` / `TEAM` require an invite code (`POST /api/rooms/{id}/invites`, then join with `{ "inviteCode": "..." }`).
- `PRIVATE` is get-or-create via `POST /api/rooms/private` `{ "userId": "<peer>" }`. Strangers cannot join by id.

### GET `/api/rooms`

Lists rooms the caller belongs to. Newest rooms first. Empty list if none.

No query params.

**Response `200 OK`**

```json
[
  {
    "id": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
    "name": "Arena",
    "type": "GAME_ROOM",
    "ownerId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
    "maxMembers": 50,
    "createdAt": "2026-08-16T17:30:10Z",
    "updatedAt": "2026-08-16T17:30:10Z"
  }
]
```

**Errors**

- `401` — missing/invalid token

```powershell
curl.exe -s https://localhost:8080/api/rooms `
  -H "Authorization: Bearer $TOKEN"
```

---

### POST `/api/rooms`

Creates a room. The caller becomes `OWNER` and a member.

**Request body**

| Field | Rules |
|---|---|
| `name` | required, max 100 chars |
| `type` | optional, one of the types above, default `GAME_ROOM` |
| `maxMembers` | optional integer 2–1000, default `50` |

**Request example**

```json
{
  "name": "Arena",
  "type": "GAME_ROOM",
  "maxMembers": 50
}
```

**Response `201 Created`**

```json
{
  "id": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "name": "Arena",
  "type": "GAME_ROOM",
  "ownerId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
  "maxMembers": 50,
  "createdAt": "2026-08-16T17:30:10Z",
  "updatedAt": "2026-08-16T17:30:10Z"
}
```

Save `id` as the room id.

**Errors**

- `401` — missing/invalid token
- `400 BAD_REQUEST` — `"Unsupported room type"`
- `400 VALIDATION_ERROR` — blank name, `maxMembers` out of range

```powershell
curl.exe -s -X POST https://localhost:8080/api/rooms `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"name\":\"Arena\",\"type\":\"GAME_ROOM\"}"
```

```powershell
$ROOM = "paste-room-uuid"
```

---

### GET `/api/rooms/{roomId}`

Returns room details. **Members only.**

**Response `200 OK`** — same shape as create.

**Errors**

- `401` — no/invalid token
- `403 FORBIDDEN` — `"Not a member of this room"`
- `404 NOT_FOUND` — `"Room not found"`

```powershell
curl.exe -s https://localhost:8080/api/rooms/$ROOM `
  -H "Authorization: Bearer $TOKEN"
```

---

### POST `/api/rooms/{roomId}/join`

Adds the caller as `MEMBER`. Idempotent: if already a member, returns `200` without duplicating.

No request body.

**Response `200 OK`** — same room JSON as create/get.

**Errors**

- `401` — no/invalid token
- `404 NOT_FOUND` — `"Room not found"`
- `409 CONFLICT` — `"Room is full"`

```powershell
curl.exe -s -X POST https://localhost:8080/api/rooms/$ROOM/join `
  -H "Authorization: Bearer $BOB"
```

---

### POST `/api/rooms/{roomId}/leave`

Removes the caller from the room. Owner may leave in V1; the room stays and `ownerId` is unchanged.

No request body.

**Response `204 No Content`** — empty body.

Also drops that user's WebSocket sessions from the room's in-memory broadcast list.

**Errors**

- `401` — no/invalid token
- `403 FORBIDDEN` — `"Not a member of this room"`
- `404 NOT_FOUND` — `"Room not found"`

```powershell
curl.exe -s -i -X POST https://localhost:8080/api/rooms/$ROOM/leave `
  -H "Authorization: Bearer $BOB"
```

---

### GET `/api/rooms/{roomId}/members`

Lists members. **Members only.**

**Response `200 OK`**

```json
[
  {
    "userId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
    "username": "alice",
    "role": "OWNER",
    "joinedAt": "2026-08-16T17:30:10Z"
  },
  {
    "userId": "11111111-2222-3333-4444-555555555555",
    "username": "bob",
    "role": "MEMBER",
    "joinedAt": "2026-08-16T17:31:00Z"
  }
]
```

Roles: `OWNER` (creator) or `MEMBER`.

**Errors**

- `401` / `403` / `404` — same as get room

```powershell
curl.exe -s https://localhost:8080/api/rooms/$ROOM/members `
  -H "Authorization: Bearer $TOKEN"
```

---

## Message history (REST)

Chat **send** is WebSocket-only. REST only reads history.

### GET `/api/rooms/{roomId}/messages`

**Members only.**

Query params:

| Param | Default | Notes |
|---|---|---|
| `page` | `0` | Used when `afterSequence` is omitted. Negative values treated as `0` |
| `size` | `20` | Clamped to 1–100 |
| `afterSequence` | omitted | When set, returns messages with `sequenceNumber > afterSequence`, **oldest-first** (sync order). `page` is ignored. |

Without `afterSequence`, history is **newest-first**.

**Response `200 OK`**

```json
{
  "content": [
    {
      "messageId": "9d0e1f20-aaaa-bbbb-cccc-ddddeeeeffff",
      "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
      "senderId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
      "content": "Enemy approaching",
      "timestamp": "2026-08-16T17:32:00Z",
      "sequenceNumber": 1
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Empty room:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

**Errors**

- `401` / `403` / `404` — same as get room

```powershell
curl.exe -s "https://localhost:8080/api/rooms/$ROOM/messages?page=0&size=20" `
  -H "Authorization: Bearer $TOKEN"

curl.exe -s "https://localhost:8080/api/rooms/$ROOM/messages?afterSequence=0&size=50" `
  -H "Authorization: Bearer $TOKEN"
```

### GET `/api/rooms/{roomId}/messages/search?q=`

**Members only.** Postgres full-text search over message content (`simple` config).

| Param | Default | Notes |
|---|---|---|
| `q` | required | Search query |
| `size` | `20` | Clamped to 1–50 |

**Response `200 OK`** — same page envelope as history.

### POST `/api/rooms/{roomId}/attachments`

**Members only.** Multipart field `file` (optional `caption`). Images (`png`/`jpeg`/`webp`/`gif`) or `pdf`, max 5MB. Creates a message whose `attachments` array is populated. Muted members receive `403`. MinIO is internal; the browser downloads through `GET /api/attachments/{id}` with a Bearer token.

**Response `200 OK`** — a `MessageResponse`.

### GET `/api/attachments/{id}`

**Members of the attachment's room.** Streams bytes (`Content-Disposition: inline`).

---

## Moderation extras (V7)

Owner can `POST /api/rooms/{roomId}/members/{userId}/promote` (`MODERATOR`) and `/demote`. Moderators can `/ban` and `/unban`. Banned users cannot `join`. `GET /api/rooms/{roomId}/reports` lists reports; `POST /api/rooms/{roomId}/reports/{reportId}/resolve` marks one resolved.

Room list objects include `unreadCount`.

---

## WebSocket chat

There is **no** REST endpoint to send a message. Use the socket.

**URL:** `wss://localhost:8080/ws/chat`

Handshake does not require a token. First frame:

```json
{ "type": "AUTH", "token": "<accessToken>" }
```

Then `CONNECTED`. Other types before AUTH return `ERROR UNAUTHORIZED`.

You must **join the room via REST first**, then `JOIN_ROOM` on the socket to receive broadcasts.

### Server → client events

**On connect**

```json
{
  "type": "CONNECTED",
  "userId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
  "username": "alice"
}
```

**After `JOIN_ROOM`** (in order): `HISTORY_SYNC`, `JOINED`, `PRESENCE_SNAPSHOT`, then `PRESENCE` `ONLINE` if this is the user's first socket in the room.

```json
{
  "type": "HISTORY_SYNC",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "messages": [
    {
      "type": "MESSAGE",
      "messageId": "9d0e1f20-aaaa-bbbb-cccc-ddddeeeeffff",
      "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
      "senderId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
      "content": "Enemy approaching",
      "timestamp": "2026-08-16T17:32:00Z",
      "sequenceNumber": 1
    }
  ],
  "fromSequence": 1,
  "toSequence": 1,
  "truncated": false,
  "requestId": "req-1"
}
```

`afterSequence` omitted or `0` means the client has nothing. If more than `app.chat.sync-batch-size` (default 100) messages remain, `truncated` is `true`; continue with REST `afterSequence`.

```json
{
  "type": "JOINED",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "requestId": "req-1"
}
```

```json
{
  "type": "PRESENCE_SNAPSHOT",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "online": [
    { "userId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f", "username": "alice", "status": "ONLINE" }
  ]
}
```

```json
{
  "type": "PRESENCE",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "userId": "11111111-2222-3333-4444-555555555555",
  "username": "bob",
  "status": "ONLINE"
}
```

`status` is `ONLINE`, `AWAY`, `IN_GAME`, or `OFFLINE`. Clients send `SET_PRESENCE` with ONLINE / AWAY / IN_GAME. Offline is emitted when the user's last socket dies, they leave a room, or they are kicked. `lastSeenAt` is included when known.

**After `LEAVE_ROOM`**

```json
{
  "type": "LEFT",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "requestId": "req-2"
}
```

**Persist ACK** (sender only, after save)

```json
{
  "type": "ACK",
  "requestId": "req-3",
  "messageId": "9d0e1f20-aaaa-bbbb-cccc-ddddeeeeffff",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "sequenceNumber": 1
}
```

**Chat message** (broadcast to every socket currently joined to that room, including the sender)

```json
{
  "type": "MESSAGE",
  "messageId": "9d0e1f20-aaaa-bbbb-cccc-ddddeeeeffff",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "senderId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
  "content": "Enemy approaching",
  "timestamp": "2026-08-16T17:32:00Z",
  "sequenceNumber": 1,
  "requestId": "req-3"
}
```

`requestId` is echoed only if the client sent one. `senderId` is taken from the JWT, never from the client body. `sequenceNumber` is per-room and monotonic.

**PONG** (reply to `PING`)

```json
{ "type": "PONG", "requestId": "req-ping" }
```

**Typing** (other sockets in the room only)

```json
{
  "type": "TYPING",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "userId": "7c19f8a2-4d3e-4b1a-9c22-1a2b3c4d5e6f",
  "username": "alice",
  "isTyping": true
}
```

**Error** (socket stays open)

```json
{
  "type": "ERROR",
  "requestId": "req-1",
  "code": "FORBIDDEN",
  "message": "Not a member of this room"
}
```

| code | When |
|---|---|
| `BAD_REQUEST` | Malformed JSON, missing `type`/`roomId`, blank/oversized content (max 2000), invalid UUID, bad `afterSequence` |
| `FORBIDDEN` | `JOIN_ROOM` / `SEND_MESSAGE` for a room you are not a REST member of |
| `NOT_FOUND` | Room does not exist |
| `UNSUPPORTED_TYPE` | Unknown `type` |
| `INTERNAL_ERROR` | Unexpected server failure |

Idle sockets with no inbound frames for `app.chat.heartbeat-timeout-ms` (default 60s) are closed.

### Client → server events

**AUTH** — first frame after handshake. Required within ~3 seconds.

```json
{ "type": "AUTH", "token": "<accessToken>" }
```

**JOIN_ROOM** — subscribe this socket to broadcasts and receive missed messages. Caller must already be a REST member.

```json
{
  "type": "JOIN_ROOM",
  "requestId": "req-1",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "afterSequence": 0
}
```

`afterSequence` is optional (default `0`). The server replies with `HISTORY_SYNC` for `sequenceNumber > afterSequence`, then `JOINED`.

**LEAVE_ROOM** — unsubscribe this socket only. Does **not** change REST membership.

```json
{
  "type": "LEAVE_ROOM",
  "requestId": "req-2",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa"
}
```

**SEND_MESSAGE** — persist, `ACK` the sender, then broadcast `MESSAGE`. Max content length 2000 after trim. Repeat the same `requestId` from the same sender in the same room to retry safely (one row).

```json
{
  "type": "SEND_MESSAGE",
  "requestId": "req-3",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "content": "Enemy approaching"
}
```

**ADD_REACTION** / **REMOVE_REACTION**

```json
{
  "type": "ADD_REACTION",
  "requestId": "req-re",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "messageId": "9d0e1f20-aaaa-bbbb-cccc-ddddeeeeffff",
  "emoji": "👍"
}
```

Server broadcasts `REACTION` with `action` `ADD` or `REMOVE`. The pair `(messageId, userId, emoji)` is unique.

**MARK_READ** — persist the caller's read cursor and fan out `READ`.

```json
{
  "type": "MARK_READ",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "sequenceNumber": 12
}
```

If this socket had not `JOIN_ROOM` yet, a successful send still subscribes it so the sender receives the `MESSAGE` echo.

**PING** — liveness. Any inbound frame also refreshes idle timeout.

```json
{ "type": "PING", "requestId": "req-ping" }
```

**TYPING** — ephemeral. Ignored if this socket is not joined to the room. Not persisted.

```json
{
  "type": "TYPING",
  "requestId": "req-t",
  "roomId": "b81c9d10-2222-4aaa-8f00-aaaaaaaaaaaa",
  "isTyping": true
}
```

**SET_PRESENCE** — `ONLINE`, `AWAY`, or `IN_GAME`. Broadcasts `PRESENCE` to rooms this user currently occupies.

```json
{ "type": "SET_PRESENCE", "status": "AWAY" }
```

**DELETE_MESSAGE** / **EDIT_MESSAGE** — sender or moderator. Edit is allowed for 5 minutes after send. Broadcasts `MESSAGE_DELETED` / `MESSAGE_EDITED`.

```json
{ "type": "DELETE_MESSAGE", "roomId": "<room-uuid>", "messageId": "<message-uuid>" }
```

**MESSAGE_ACK** — client delivery ack after inbound `MESSAGE`. Ephemeral; not stored.

```json
{ "type": "MESSAGE_ACK", "messageId": "<message-uuid>", "roomId": "<room-uuid>" }
```

### Example with websocat

```powershell
websocat -k "wss://localhost:8080/ws/chat"
```

Then:

```json
{"type":"AUTH","token":"<accessToken>"}
{"type":"JOIN_ROOM","requestId":"req-1","roomId":"<room-uuid>","afterSequence":0}
{"type":"SET_PRESENCE","status":"IN_GAME"}
{"type":"SEND_MESSAGE","requestId":"req-2","roomId":"<room-uuid>","content":"Enemy approaching"}
```

After a successful send, `GET /api/rooms/{roomId}/messages` returns that row with a `sequenceNumber`.

---

## Typical V6 flow

1. `POST /api/auth/register` as alice → save `token` and `userId`
2. `POST /api/rooms` with alice's token → save room `id`
3. `GET /api/rooms` as alice → contains that room; as a stranger → `[]`
4. `POST /api/auth/register` as bob → save bob's token
5. `POST /api/rooms/{roomId}/join` as bob
6. Alice opens a WebSocket, `JOIN_ROOM`, `SEND_MESSAGE` while Bob is disconnected
7. Bob connects and `JOIN_ROOM` with `afterSequence: 0` → `HISTORY_SYNC` contains Alice's message
8. Alice sends again → both sockets get live `MESSAGE` (even if they are on different chat nodes)
9. `GET /api/rooms/{roomId}/messages` as alice or bob → history contains both messages
10. Same history call as a third user who never joined → `403`

Live `MESSAGE`, `PRESENCE`, and `TYPING` frames go through Redis Pub/Sub. Persist `ACK`, `HISTORY_SYNC`, `JOINED`, `LEFT`, `PONG`, and `ERROR` stay on the node that handled the socket. After Postgres commit, the origin node also publishes to Kafka topics `chat.message.persisted`, `chat.user.joined`, `chat.user.left`, and `chat.moderation`.

---

## Endpoint cheat sheet

| Method | Path | Auth | Success |
|---|---|---|---|
| GET | `/actuator/health` | no | `200` `{ "status": "UP" }` |
| POST | `/api/auth/register` | no | `201` `{ accessToken, expiresIn, userId, username }` + refresh cookie |
| POST | `/api/auth/login` | no | `200` same as register |
| POST | `/api/auth/refresh` | refresh cookie | `200` new access token |
| POST | `/api/auth/logout` | cookie / optional Bearer | `204` |
| POST | `/api/rooms` | Bearer | `201` room object |
| POST | `/api/rooms/private` | Bearer | `201` unique DM |
| POST | `/api/rooms/join` | Bearer | `200` join by invite code |
| GET | `/api/rooms` | Bearer | `200` room array (memberships) |
| GET | `/api/rooms/{roomId}` | Bearer, member | `200` room object |
| POST | `/api/rooms/{roomId}/join` | Bearer | `200` room object (invite required for PARTY/TEAM) |
| POST | `/api/rooms/{roomId}/leave` | Bearer, member | `204` empty |
| POST | `/api/rooms/{roomId}/invites` | Bearer, owner/mod | `201` `{ code, roomId, expiresAt }` |
| POST | `/api/rooms/{roomId}/members/{userId}/kick` | Bearer, owner/mod | `204` |
| POST | `/api/rooms/{roomId}/members/{userId}/mute` | Bearer, owner/mod | `204` |
| POST | `/api/rooms/{roomId}/members/{userId}/unmute` | Bearer, owner/mod | `204` |
| POST | `/api/rooms/{roomId}/reports` | Bearer, member | `201` `{ id }` |
| GET | `/api/rooms/{roomId}/members` | Bearer, member | `200` member array |
| GET | `/api/rooms/{roomId}/messages` | Bearer, member | `200` paged messages |
| GET | `/api/users/{userId}/presence` | Bearer | `200` `{ userId, status, lastSeenAt }` |
| POST | `/api/rooms/{roomId}/members/{userId}/ban` | Bearer, owner/mod | `204` |
| POST | `/api/rooms/{roomId}/members/{userId}/unban` | Bearer, owner/mod | `204` |
| POST | `/api/rooms/{roomId}/members/{userId}/promote` | Bearer, owner | `204` |
| POST | `/api/rooms/{roomId}/members/{userId}/demote` | Bearer, owner | `204` |
| GET | `/api/rooms/{roomId}/reports` | Bearer, owner/mod | `200` report array |
| POST | `/api/rooms/{roomId}/reports/{reportId}/resolve` | Bearer, owner/mod | `200` report |
| GET | `/api/rooms/{roomId}/messages/search` | Bearer, member | `200` paged hits |
| POST | `/api/rooms/{roomId}/attachments` | Bearer, member | `200` message with attachments |
| GET | `/api/attachments/{id}` | Bearer, member | `200` file stream |
| WS | `/ws/chat` | first frame `AUTH` | `CONNECTED`, `HISTORY_SYNC`, `JOINED`, `ACK`, `MESSAGE`, `REACTION`, `READ`, presence, typing, delete/edit |
