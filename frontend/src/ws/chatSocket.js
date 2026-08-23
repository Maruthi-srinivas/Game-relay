import { logTraffic } from "../api/trafficLog.js";

const PING_MS = 20000;
const WS_PATH = "/ws/chat";

function quiet(payload) {
  const type = payload?.type;
  return type === "PING" || type === "PONG" || type === "MESSAGE_ACK";
}

export function connectChat({ token, onEvent, onState }) {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const url = `${proto}://${location.host}${WS_PATH}`;
  const socket = new WebSocket(url);
  let requestSeq = 0;
  let pingTimer = null;
  let closed = false;
  let authed = false;

  function nextId() {
    requestSeq += 1;
    return `req-${requestSeq}`;
  }

  socket.addEventListener("open", () => {
    logTraffic({ kind: "ws", dir: "open", path: WS_PATH, payload: { url: WS_PATH } });
    send({ type: "AUTH", token });
    pingTimer = setInterval(() => {
      if (socket.readyState === WebSocket.OPEN && authed) {
        send({ type: "PING" });
      }
    }, PING_MS);
    onState?.("open");
  });

  socket.addEventListener("close", (ev) => {
    if (pingTimer) {
      clearInterval(pingTimer);
      pingTimer = null;
    }
    logTraffic({
      kind: "ws",
      dir: "close",
      path: WS_PATH,
      payload: { code: ev.code, reason: ev.reason || "" },
    });
    onState?.("closed");
  });

  socket.addEventListener("error", () => {
    logTraffic({ kind: "ws", dir: "error", path: WS_PATH, payload: { message: "WebSocket error" } });
    onState?.("error");
  });

  socket.addEventListener("message", (ev) => {
    let parsed = ev.data;
    try {
      parsed = JSON.parse(ev.data);
    } catch {
      // keep raw string
    }
    if (parsed?.type === "CONNECTED") {
      authed = true;
    }
    if (parsed?.type === "MESSAGE" && parsed.messageId) {
      send({ type: "MESSAGE_ACK", messageId: parsed.messageId, roomId: parsed.roomId });
    }
    if (!quiet(parsed)) {
      logTraffic({ kind: "ws", dir: "in", path: WS_PATH, payload: parsed });
    }
    onEvent?.(parsed);
  });

  function send(payload) {
    if (!payload.requestId) {
      payload.requestId = nextId();
    }
    if (socket.readyState !== WebSocket.OPEN) {
      return payload.requestId;
    }
    if (!quiet(payload) && payload.type !== "AUTH") {
      logTraffic({ kind: "ws", dir: "out", path: WS_PATH, payload });
    }
    socket.send(JSON.stringify(payload));
    return payload.requestId;
  }

  onState?.("connecting");

  return {
    joinRoom(roomId, afterSequence = 0) {
      return send({ type: "JOIN_ROOM", roomId, afterSequence });
    },
    leaveRoom(roomId) {
      return send({ type: "LEAVE_ROOM", roomId });
    },
    sendMessage(roomId, content, requestId) {
      return send({ type: "SEND_MESSAGE", roomId, content, requestId });
    },
    markRead(roomId, sequenceNumber) {
      return send({ type: "MARK_READ", roomId, sequenceNumber });
    },
    addReaction(roomId, messageId, emoji) {
      return send({ type: "ADD_REACTION", roomId, messageId, emoji });
    },
    removeReaction(roomId, messageId, emoji) {
      return send({ type: "REMOVE_REACTION", roomId, messageId, emoji });
    },
    typing(roomId, isTyping) {
      return send({ type: "TYPING", roomId, isTyping });
    },
    setPresence(status) {
      return send({ type: "SET_PRESENCE", status });
    },
    deleteMessage(roomId, messageId) {
      return send({ type: "DELETE_MESSAGE", roomId, messageId });
    },
    editMessage(roomId, messageId, content) {
      return send({ type: "EDIT_MESSAGE", roomId, messageId, content });
    },
    close() {
      closed = true;
      if (pingTimer) {
        clearInterval(pingTimer);
        pingTimer = null;
      }
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
    },
    get readyState() {
      return socket.readyState;
    },
    get closed() {
      return closed;
    },
  };
}
