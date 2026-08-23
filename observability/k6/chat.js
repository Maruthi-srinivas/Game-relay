import http from "k6/http";
import { check, sleep } from "k6";
import ws from "k6/ws";

export const options = {
  insecureSkipTLSVerify: true,
  stages: [
    { duration: "10s", target: 4 },
    { duration: "20s", target: 8 },
    { duration: "10s", target: 0 },
  ],
  thresholds: {
    http_req_failed: ["rate<0.1"],
    checks: ["rate>0.85"],
  },
};

const BASE = __ENV.BASE_URL || "https://gateway";

function register(prefix) {
  const suffix = `${prefix}-${__VU}-${__ITER}-${Date.now()}`.slice(0, 24);
  const res = http.post(
    `${BASE}/api/auth/register`,
    JSON.stringify({
      username: suffix,
      email: `${suffix}@load.test`,
      password: "password123",
    }),
    { headers: { "Content-Type": "application/json" } }
  );
  check(res, { "register 201": (r) => r.status === 201 });
  return { token: res.json("accessToken"), username: suffix };
}

export default function () {
  const owner = register("own");
  const guest = register("gst");
  if (!owner.token || !guest.token) {
    return;
  }
  const room = http.post(
    `${BASE}/api/rooms`,
    JSON.stringify({ name: `load-${__VU}-${__ITER}`, type: "GAME_ROOM" }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${owner.token}`,
      },
    }
  );
  check(room, { "create room 201": (r) => r.status === 201 });
  const roomId = room.json("id");
  if (!roomId) {
    return;
  }
  const joined = http.post(`${BASE}/api/rooms/${roomId}/join`, null, {
    headers: { Authorization: `Bearer ${guest.token}` },
  });
  check(joined, { "guest joined": (r) => r.status === 200 });

  const url = `${BASE.replace("https://", "wss://").replace("http://", "ws://")}/ws/chat`;
  function chatOnce(token, content) {
    ws.connect(url, {}, function (socket) {
      socket.on("open", function () {
        socket.send(JSON.stringify({ type: "AUTH", token }));
      });
      socket.on("message", function (raw) {
        const event = JSON.parse(raw);
        if (event.type === "CONNECTED") {
          socket.send(JSON.stringify({ type: "JOIN_ROOM", roomId, afterSequence: 0 }));
        }
        if (event.type === "JOINED") {
          socket.send(JSON.stringify({ type: "SEND_MESSAGE", roomId, content }));
        }
        if (event.type === "ACK" || event.type === "MESSAGE") {
          socket.close();
        }
      });
      socket.setTimeout(function () {
        socket.close();
      }, 8000);
    });
  }
  chatOnce(owner.token, "k6 ping");
  chatOnce(guest.token, "k6 pong");
  chatOnce(owner.token, "k6 reconnect");
  sleep(1);
}
