import http from "k6/http";
import { check, sleep } from "k6";
import ws from "k6/ws";

export const options = {
  vus: 5,
  duration: "20s",
  insecureSkipTLSVerify: true,
};

const BASE = __ENV.BASE_URL || "https://gateway";

export default function () {
  const suffix = `${__VU}-${__ITER}-${Date.now()}`;
  const register = http.post(
    `${BASE}/api/auth/register`,
    JSON.stringify({
      username: `u${suffix}`.slice(0, 20),
      email: `u${suffix}@load.test`,
      password: "password123",
    }),
    { headers: { "Content-Type": "application/json" } }
  );
  check(register, { "register 201": (r) => r.status === 201 });
  const access = register.json("accessToken");
  if (!access) {
    return;
  }

  const room = http.post(
    `${BASE}/api/rooms`,
    JSON.stringify({ name: `load-${suffix}`, type: "GAME_ROOM" }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${access}`,
      },
    }
  );
  check(room, { "create room 201": (r) => r.status === 201 });
  const roomId = room.json("id");
  if (!roomId) {
    return;
  }

  const url = `${BASE.replace("https://", "wss://").replace("http://", "ws://")}/ws/chat`;
  ws.connect(url, {}, function (socket) {
    socket.on("open", function () {
      socket.send(JSON.stringify({ type: "AUTH", token: access }));
    });
    socket.on("message", function (raw) {
      const event = JSON.parse(raw);
      if (event.type === "CONNECTED") {
        socket.send(JSON.stringify({ type: "JOIN_ROOM", roomId, afterSequence: 0 }));
      }
      if (event.type === "JOINED") {
        socket.send(
          JSON.stringify({
            type: "SEND_MESSAGE",
            roomId,
            content: "k6 ping",
          })
        );
      }
      if (event.type === "ACK" || event.type === "MESSAGE") {
        socket.close();
      }
    });
    socket.setTimeout(function () {
      socket.close();
    }, 8000);
  });
  sleep(1);
}
