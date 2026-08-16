import { request } from "./api.js";
import { connectChat } from "./ws.js";

const ROOM_TYPES = ["GAME_ROOM", "GLOBAL", "TEAM", "PARTY", "PRIVATE"];

export function mountPane(root, { title, defaults }) {
  const state = {
    token: null,
    userId: null,
    username: null,
    rooms: [],
    selectedRoomId: null,
    members: [],
    messages: [],
    seenMessageIds: new Set(),
    socket: null,
    wsState: "closed",
    logs: [],
  };

  root.innerHTML = `
    <article class="pane">
      <div class="pane-head">
        <h2>${escapeHtml(title)}</h2>
        <span class="who" data-who>not signed in</span>
      </div>

      <section>
        <h3>Auth</h3>
        <div class="row">
          <label class="field">Username <input data-username value="${escapeAttr(defaults.username)}" autocomplete="off"></label>
          <label class="field">Email <input data-email value="${escapeAttr(defaults.email)}" autocomplete="off"></label>
          <label class="field">Password <input data-password type="password" value="${escapeAttr(defaults.password)}" autocomplete="off"></label>
        </div>
        <div class="row">
          <button class="primary" data-register>Register</button>
          <button data-login>Login</button>
        </div>
        <div class="meta" data-session>No token</div>
      </section>

      <section>
        <h3>WebSocket</h3>
        <div class="row">
          <span class="meta"><span class="status-dot" data-ws-dot></span><span data-ws-label>closed</span></span>
          <button class="primary" data-ws-connect>Connect</button>
          <button data-ws-disconnect disabled>Disconnect</button>
          <button data-ws-join disabled>JOIN_ROOM</button>
          <button data-ws-leave disabled>LEAVE_ROOM</button>
        </div>
      </section>

      <section>
        <h3>Rooms</h3>
        <div class="row">
          <label class="field">Name <input data-room-name value="Arena"></label>
          <label class="field">Type
            <select data-room-type>
              ${ROOM_TYPES.map((type) => `<option>${type}</option>`).join("")}
            </select>
          </label>
          <button class="primary" data-create>Create</button>
        </div>
        <div class="row">
          <label class="field">Room id <input data-join-id placeholder="paste UUID"></label>
          <button data-join>Join</button>
          <button data-copy>Copy selected id</button>
          <button data-refresh>Refresh list</button>
        </div>
        <div class="rooms" data-rooms><div class="empty">Sign in to list rooms</div></div>
        <div class="row" style="margin-top:6px">
          <button class="danger" data-leave-rest disabled>Leave room (REST)</button>
          <button data-members disabled>Members</button>
          <button data-history disabled>History</button>
        </div>
        <div class="members" data-members-list><div class="empty">No members loaded</div></div>
      </section>

      <section>
        <h3>Chat</h3>
        <div class="messages" data-messages><div class="empty">No messages</div></div>
        <form class="composer" data-send-form>
          <input data-content maxlength="2000" placeholder="SEND_MESSAGE (max 2000)" autocomplete="off">
          <button class="primary" type="submit">Send</button>
        </form>
      </section>

      <section>
        <h3>Traffic log</h3>
        <div class="log" data-log><div class="empty">REST and WS frames appear here</div></div>
      </section>
    </article>
  `;

  const el = {
    who: root.querySelector("[data-who]"),
    username: root.querySelector("[data-username]"),
    email: root.querySelector("[data-email]"),
    password: root.querySelector("[data-password]"),
    session: root.querySelector("[data-session]"),
    wsDot: root.querySelector("[data-ws-dot]"),
    wsLabel: root.querySelector("[data-ws-label]"),
    wsConnect: root.querySelector("[data-ws-connect]"),
    wsDisconnect: root.querySelector("[data-ws-disconnect]"),
    wsJoin: root.querySelector("[data-ws-join]"),
    wsLeave: root.querySelector("[data-ws-leave]"),
    roomName: root.querySelector("[data-room-name]"),
    roomType: root.querySelector("[data-room-type]"),
    joinId: root.querySelector("[data-join-id]"),
    rooms: root.querySelector("[data-rooms]"),
    members: root.querySelector("[data-members-list]"),
    messages: root.querySelector("[data-messages]"),
    content: root.querySelector("[data-content]"),
    log: root.querySelector("[data-log]"),
    leaveRest: root.querySelector("[data-leave-rest]"),
    membersBtn: root.querySelector("[data-members]"),
    historyBtn: root.querySelector("[data-history]"),
  };

  root.querySelector("[data-register]").addEventListener("click", () => auth("register"));
  root.querySelector("[data-login]").addEventListener("click", () => auth("login"));
  el.wsConnect.addEventListener("click", connectWs);
  el.wsDisconnect.addEventListener("click", disconnectWs);
  el.wsJoin.addEventListener("click", () => {
    if (state.socket && state.selectedRoomId) {
      state.socket.joinRoom(state.selectedRoomId);
    }
  });
  el.wsLeave.addEventListener("click", () => {
    if (state.socket && state.selectedRoomId) {
      state.socket.leaveRoom(state.selectedRoomId);
    }
  });
  root.querySelector("[data-create]").addEventListener("click", createRoom);
  root.querySelector("[data-join]").addEventListener("click", joinRoom);
  root.querySelector("[data-copy]").addEventListener("click", copySelected);
  root.querySelector("[data-refresh]").addEventListener("click", refreshRooms);
  el.leaveRest.addEventListener("click", leaveRoomRest);
  el.membersBtn.addEventListener("click", loadMembers);
  el.historyBtn.addEventListener("click", loadHistory);
  root.querySelector("[data-send-form]").addEventListener("submit", (event) => {
    event.preventDefault();
    sendChat();
  });

  function log(entry) {
    state.logs.unshift(entry);
    if (state.logs.length > 80) {
      state.logs.length = 80;
    }
    renderLog();
  }

  async function api(method, path, body) {
    return request(state.token, method, path, body, log);
  }

  async function auth(kind) {
    const path = kind === "register" ? "/api/auth/register" : "/api/auth/login";
    const body = kind === "register"
      ? {
          username: el.username.value.trim(),
          email: el.email.value.trim(),
          password: el.password.value,
        }
      : {
          username: el.username.value.trim(),
          password: el.password.value,
        };
    const result = await api("POST", path, body);
    if (!result.ok || !result.body?.token) {
      return;
    }
    disconnectWs();
    state.token = result.body.token;
    state.userId = result.body.userId;
    state.username = result.body.username;
    state.selectedRoomId = null;
    state.messages = [];
    state.seenMessageIds = new Set();
    renderSession();
    renderMessages();
    await refreshRooms();
  }

  async function createRoom() {
    const result = await api("POST", "/api/rooms", {
      name: el.roomName.value.trim(),
      type: el.roomType.value,
    });
    if (result.ok && result.body?.id) {
      await refreshRooms();
      await selectRoom(result.body.id);
    }
  }

  async function joinRoom() {
    const roomId = el.joinId.value.trim();
    if (!roomId) {
      return;
    }
    const result = await api("POST", `/api/rooms/${roomId}/join`);
    if (result.ok && result.body?.id) {
      await refreshRooms();
      await selectRoom(result.body.id);
    }
  }

  async function leaveRoomRest() {
    if (!state.selectedRoomId) {
      return;
    }
    const roomId = state.selectedRoomId;
    const result = await api("POST", `/api/rooms/${roomId}/leave`);
    if (result.ok || result.status === 204) {
      if (state.socket) {
        state.socket.leaveRoom(roomId);
      }
      state.selectedRoomId = null;
      state.members = [];
      state.messages = [];
      state.seenMessageIds = new Set();
      await refreshRooms();
      renderMembers();
      renderMessages();
      renderRoomButtons();
    }
  }

  async function refreshRooms() {
    if (!state.token) {
      return;
    }
    const result = await api("GET", "/api/rooms");
    if (result.ok && Array.isArray(result.body)) {
      state.rooms = result.body;
      renderRooms();
    }
  }

  async function selectRoom(roomId) {
    state.selectedRoomId = roomId;
    el.joinId.value = roomId;
    renderRooms();
    renderRoomButtons();
    await Promise.all([loadMembers(), loadHistory()]);
    if (state.socket && state.wsState === "open") {
      state.socket.joinRoom(roomId);
    }
  }

  async function loadMembers() {
    if (!state.selectedRoomId) {
      return;
    }
    const result = await api("GET", `/api/rooms/${state.selectedRoomId}/members`);
    if (result.ok && Array.isArray(result.body)) {
      state.members = result.body;
      renderMembers();
    }
  }

  async function loadHistory() {
    if (!state.selectedRoomId) {
      return;
    }
    const result = await api("GET", `/api/rooms/${state.selectedRoomId}/messages?page=0&size=50`);
    if (result.ok && result.body?.content) {
      const chronological = [...result.body.content].reverse();
      state.messages = [];
      state.seenMessageIds = new Set();
      for (const msg of chronological) {
        addMessage(msg, false);
      }
      renderMessages();
    }
  }

  function addMessage(msg, render) {
    const id = msg.messageId;
    if (id && state.seenMessageIds.has(id)) {
      return;
    }
    if (id) {
      state.seenMessageIds.add(id);
    }
    state.messages.push(msg);
    if (render !== false) {
      renderMessages();
    }
  }

  function connectWs() {
    if (!state.token) {
      return;
    }
    disconnectWs();
    state.socket = connectChat({
      token: state.token,
      onLog: log,
      onState: (wsState) => {
        state.wsState = wsState;
        if (wsState === "closed" || wsState === "error") {
          state.socket = null;
        }
        renderWs();
      },
      onEvent: (event) => {
        if (event?.type === "MESSAGE" && event.roomId === state.selectedRoomId) {
          addMessage(event, true);
        }
      },
    });
    renderWs();
  }

  function disconnectWs() {
    if (state.socket) {
      state.socket.close();
      state.socket = null;
    }
    state.wsState = "closed";
    renderWs();
  }

  function sendChat() {
    const content = el.content.value;
    if (!content.trim() || !state.selectedRoomId) {
      return;
    }
    if (!state.socket || state.wsState !== "open") {
      log({
        kind: "ws",
        dir: "error",
        payload: { message: "Connect WebSocket before sending" },
      });
      return;
    }
    state.socket.sendMessage(state.selectedRoomId, content);
    el.content.value = "";
  }

  async function copySelected() {
    if (!state.selectedRoomId) {
      return;
    }
    try {
      await navigator.clipboard.writeText(state.selectedRoomId);
      log({
        kind: "rest",
        method: "CLIPBOARD",
        path: "roomId",
        ok: true,
        status: 200,
        response: state.selectedRoomId,
      });
    } catch {
      el.joinId.value = state.selectedRoomId;
      el.joinId.select();
    }
  }

  function renderSession() {
    if (!state.token) {
      el.who.textContent = "not signed in";
      el.session.textContent = "No token";
      return;
    }
    el.who.textContent = state.username;
    el.session.textContent = `${state.username}  ${state.userId}  ${truncate(state.token, 28)}`;
  }

  function renderWs() {
    const open = state.wsState === "open";
    el.wsDot.className = `status-dot ${state.wsState === "open" ? "open" : state.wsState === "error" ? "error" : ""}`;
    el.wsLabel.textContent = state.wsState;
    el.wsConnect.disabled = !state.token || open;
    el.wsDisconnect.disabled = !open;
    el.wsJoin.disabled = !open || !state.selectedRoomId;
    el.wsLeave.disabled = !open || !state.selectedRoomId;
  }

  function renderRoomButtons() {
    const hasRoom = Boolean(state.selectedRoomId && state.token);
    el.leaveRest.disabled = !hasRoom;
    el.membersBtn.disabled = !hasRoom;
    el.historyBtn.disabled = !hasRoom;
    renderWs();
  }

  function renderRooms() {
    if (!state.rooms.length) {
      el.rooms.innerHTML = `<div class="empty">${state.token ? "No rooms yet" : "Sign in to list rooms"}</div>`;
      return;
    }
    el.rooms.innerHTML = state.rooms.map((room) => `
      <div class="room-item${room.id === state.selectedRoomId ? " active" : ""}" data-id="${escapeAttr(room.id)}">
        <span>${escapeHtml(room.name)} <span class="id">${escapeHtml(room.type)}</span></span>
        <span class="id">${escapeHtml(shortId(room.id))}</span>
      </div>
    `).join("");
    el.rooms.querySelectorAll(".room-item").forEach((item) => {
      item.addEventListener("click", () => selectRoom(item.dataset.id));
    });
  }

  function renderMembers() {
    if (!state.members.length) {
      el.members.innerHTML = `<div class="empty">No members loaded</div>`;
      return;
    }
    el.members.innerHTML = state.members.map((member) => `
      <div class="member-item">${escapeHtml(member.username)} <span class="id">${escapeHtml(member.role)}</span></div>
    `).join("");
  }

  function renderMessages() {
    if (!state.messages.length) {
      el.messages.innerHTML = `<div class="empty">No messages</div>`;
      return;
    }
    el.messages.innerHTML = state.messages.map((msg) => {
      const mine = msg.senderId === state.userId;
      const name = senderName(state, msg.senderId);
      return `
        <div class="msg${mine ? " mine" : ""}">
          <span class="from">${escapeHtml(name)}</span>
          <span class="time">${escapeHtml(formatTime(msg.timestamp))}</span>
          <div class="body">${escapeHtml(msg.content)}</div>
        </div>
      `;
    }).join("");
    el.messages.scrollTop = el.messages.scrollHeight;
  }

  function renderLog() {
    if (!state.logs.length) {
      el.log.innerHTML = `<div class="empty">REST and WS frames appear here</div>`;
      return;
    }
    el.log.innerHTML = state.logs.map((entry) => {
      const cls = logClass(entry);
      const line = logLine(entry);
      const payload = logPayload(entry);
      return `<div class="log-item ${cls}"><div class="line">${escapeHtml(line)}</div>${payload ? `<pre>${escapeHtml(payload)}</pre>` : ""}</div>`;
    }).join("");
  }

  renderSession();
  renderWs();
  renderRooms();
  renderMembers();
  renderMessages();
  renderRoomButtons();
}

function logClass(entry) {
  if (entry.kind === "ws") {
    return entry.dir === "error" ? "err" : "ws";
  }
  return entry.ok ? "ok" : "err";
}

function logLine(entry) {
  if (entry.kind === "ws") {
    return `WS ${entry.dir}`;
  }
  return `REST ${entry.method} ${entry.path} → ${entry.status} (${entry.ms}ms)`;
}

function logPayload(entry) {
  if (entry.kind === "ws") {
    return pretty(entry.payload);
  }
  const parts = [];
  if (entry.request) {
    parts.push("req " + pretty(entry.request));
  }
  if (entry.response !== null && entry.response !== undefined) {
    parts.push("res " + pretty(entry.response));
  }
  return parts.join("\n");
}

function pretty(value) {
  if (typeof value === "string") {
    return value;
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function senderName(state, senderId) {
  if (senderId === state.userId) {
    return state.username || "me";
  }
  const member = state.members.find((item) => item.userId === senderId);
  return member?.username || shortId(senderId);
}

function truncate(value, n) {
  if (!value || value.length <= n) {
    return value || "";
  }
  return `${value.slice(0, 10)}…${value.slice(-8)}`;
}

function shortId(id) {
  if (!id) {
    return "";
  }
  return String(id).slice(0, 8);
}

function formatTime(value) {
  if (!value) {
    return "";
  }
  try {
    return new Date(value).toLocaleTimeString();
  } catch {
    return String(value);
  }
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function escapeAttr(value) {
  return escapeHtml(value);
}
