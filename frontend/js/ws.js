export function connectChat({ token, onEvent, onLog, onState }) {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const url = `${proto}://${location.host}/ws/chat?token=${encodeURIComponent(token)}`;
  const socket = new WebSocket(url);
  let requestSeq = 0;
  let pingTimer = null;

  function nextId() {
    requestSeq += 1;
    return `req-${requestSeq}`;
  }

  function quiet(payload) {
    const type = payload?.type;
    return type === "PING" || type === "PONG";
  }

  socket.addEventListener("open", () => {
    onLog({ kind: "ws", dir: "open", payload: { url: "/ws/chat" } });
    pingTimer = setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) {
        send({ type: "PING" });
      }
    }, 20000);
    onState("open");
  });
  socket.addEventListener("close", (ev) => {
    if (pingTimer) {
      clearInterval(pingTimer);
      pingTimer = null;
    }
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
    if (!quiet(parsed)) {
      onLog({ kind: "ws", dir: "in", payload: parsed });
    }
    onEvent(parsed);
  });

  function send(payload) {
    if (!payload.requestId) {
      payload.requestId = nextId();
    }
    if (!quiet(payload)) {
      onLog({ kind: "ws", dir: "out", payload });
    }
    socket.send(JSON.stringify(payload));
    return payload.requestId;
  }

  return {
    joinRoom(roomId, afterSequence = 0) {
      return send({ type: "JOIN_ROOM", roomId, afterSequence });
    },
    leaveRoom(roomId) {
      return send({ type: "LEAVE_ROOM", roomId });
    },
    sendMessage(roomId, content) {
      return send({ type: "SEND_MESSAGE", roomId, content });
    },
    typing(roomId, isTyping) {
      return send({ type: "TYPING", roomId, isTyping });
    },
    close() {
      if (pingTimer) {
        clearInterval(pingTimer);
        pingTimer = null;
      }
      socket.close();
    },
    get readyState() {
      return socket.readyState;
    },
  };
}
