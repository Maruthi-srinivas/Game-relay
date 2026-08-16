export function connectChat({ token, onEvent, onLog, onState }) {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const url = `${proto}://${location.host}/ws/chat?token=${encodeURIComponent(token)}`;
  const socket = new WebSocket(url);
  let requestSeq = 0;

  function nextId() {
    requestSeq += 1;
    return `req-${requestSeq}`;
  }

  socket.addEventListener("open", () => {
    onLog({ kind: "ws", dir: "open", payload: { url: "/ws/chat" } });
    onState("open");
  });
  socket.addEventListener("close", (ev) => {
    onLog({ kind: "ws", dir: "close", payload: { code: ev.code, reason: ev.reason || "" } });
    onState("closed");
  });
  socket.addEventListener("error", () => {
    onLog({ kind: "ws", dir: "error", payload: { message: "WebSocket error" } });
    onState("error");
  });
  socket.addEventListener("message", (ev) => {
    let parsed = ev.data;
    try {
      parsed = JSON.parse(ev.data);
    } catch {
      // keep raw string
    }
    onLog({ kind: "ws", dir: "in", payload: parsed });
    onEvent(parsed);
  });

  function send(payload) {
    if (!payload.requestId) {
      payload.requestId = nextId();
    }
    onLog({ kind: "ws", dir: "out", payload });
    socket.send(JSON.stringify(payload));
    return payload.requestId;
  }

  return {
    joinRoom(roomId) {
      return send({ type: "JOIN_ROOM", roomId });
    },
    leaveRoom(roomId) {
      return send({ type: "LEAVE_ROOM", roomId });
    },
    sendMessage(roomId, content) {
      return send({ type: "SEND_MESSAGE", roomId, content });
    },
    close() {
      socket.close();
    },
    get readyState() {
      return socket.readyState;
    },
  };
}
