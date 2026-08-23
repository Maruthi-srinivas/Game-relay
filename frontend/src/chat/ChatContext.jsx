import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { listMessages, searchMessages, uploadAttachment } from "../api/messages.js";
import { banMember, createRoom, createInvite, createPrivateRoom, demoteMember, getRoom, joinByCode, joinRoom, kickMember, leaveRoom, listMembers, listReports, listRooms, muteMember, promoteMember, reportMember, resolveReport, unbanMember, unmuteMember } from "../api/rooms.js";
import { connectChat } from "../ws/chatSocket.js";
import { useAuth } from "../auth/AuthContext.jsx";

const ChatContext = createContext(null);
const HISTORY_PAGE_SIZE = 50;
const CATCHUP_SIZE = 100;
const TYPING_IDLE_MS = 3000;
const AWAY_IDLE_MS = 300000;
const QUICK_EMOJIS = ["👍", "❤️", "😂", "🔥", "😮"];

function maxSequence(messages) {
  let max = 0;
  for (const msg of messages) {
    if (typeof msg.sequenceNumber === "number" && msg.sequenceNumber > max) {
      max = msg.sequenceNumber;
    }
  }
  return max;
}

function minSequence(messages) {
  let min = Infinity;
  for (const msg of messages) {
    if (typeof msg.sequenceNumber === "number" && msg.sequenceNumber < min) {
      min = msg.sequenceNumber;
    }
  }
  return min === Infinity ? 0 : min;
}

function upsertMessages(prev, incoming) {
  if (!incoming?.length) {
    return prev;
  }
  const byId = new Map();
  for (const msg of prev) {
    if (msg.messageId) {
      byId.set(msg.messageId, msg);
    }
  }
  const next = prev.filter((msg) => !msg.messageId);
  for (const msg of incoming) {
    if (!msg?.messageId) {
      continue;
    }
    byId.set(msg.messageId, { ...byId.get(msg.messageId), ...msg, pending: false });
  }
  next.push(...byId.values());
  next.sort((a, b) => {
    const as = a.sequenceNumber ?? Number.MAX_SAFE_INTEGER;
    const bs = b.sequenceNumber ?? Number.MAX_SAFE_INTEGER;
    if (as !== bs) {
      return as - bs;
    }
    return String(a.timestamp || "").localeCompare(String(b.timestamp || ""));
  });
  return next;
}

export function ChatProvider({ children }) {
  const { session } = useAuth();
  const token = session?.token;
  const userId = session?.userId;
  const username = session?.username;

  const [rooms, setRooms] = useState([]);
  const [room, setRoom] = useState(null);
  const [members, setMembers] = useState([]);
  const [messages, setMessages] = useState([]);
  const [onlineUserIds, setOnlineUserIds] = useState(() => new Set());
  const [presenceByUser, setPresenceByUser] = useState({});
  const [myStatus, setMyStatus] = useState("ONLINE");
  const [namesByUserId, setNamesByUserId] = useState({});
  const [typingByUser, setTypingByUser] = useState({});
  const [wsState, setWsState] = useState("closed");
  const [error, setError] = useState(null);
  const [hasMoreHistory, setHasMoreHistory] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [joinedRoomId, setJoinedRoomId] = useState(null);

  const socketRef = useRef(null);
  const roomIdRef = useRef(null);
  const lastSeqRef = useRef({});
  const historyPageRef = useRef(0);
  const reconnectTimerRef = useRef(null);
  const reconnectDelayRef = useRef(1000);
  const stopReconnectRef = useRef(false);
  const pendingRef = useRef({});
  const typingTimersRef = useRef({});
  const catchupRef = useRef(new Set());
  const historyReadyRef = useRef(false);

  const rememberName = useCallback((id, name) => {
    if (!id || !name) {
      return;
    }
    setNamesByUserId((prev) => (prev[id] === name ? prev : { ...prev, [id]: name }));
  }, []);

  const ingestMessages = useCallback((incoming, forRoomId) => {
    const target = forRoomId || roomIdRef.current;
    if (!target || !incoming?.length) {
      return;
    }
    const scoped = incoming.filter((msg) => !msg.roomId || msg.roomId === target);
    if (!scoped.length) {
      return;
    }
    setMessages((prev) => {
      if (roomIdRef.current !== target) {
        return prev;
      }
      const next = upsertMessages(prev, scoped);
      lastSeqRef.current[target] = Math.max(lastSeqRef.current[target] || 0, maxSequence(next));
      return next;
    });
  }, []);

  const refreshRooms = useCallback(async () => {
    if (!token) {
      return [];
    }
    const list = await listRooms(token);
    setRooms(Array.isArray(list) ? list : []);
    return list;
  }, [token]);

  const refreshMembers = useCallback(async (roomId) => {
    if (!token || !roomId) {
      return [];
    }
    const list = await listMembers(token, roomId);
    if (roomIdRef.current !== roomId) {
      return list;
    }
    setMembers(Array.isArray(list) ? list : []);
    for (const member of list || []) {
      rememberName(member.userId, member.username);
    }
    return list;
  }, [token, rememberName]);

  const catchUp = useCallback(async (roomId, fromSequence) => {
    if (!token || !roomId || catchupRef.current.has(roomId)) {
      return;
    }
    catchupRef.current.add(roomId);
    try {
      let seq = fromSequence;
      for (;;) {
        const page = await listMessages(token, roomId, { afterSequence: seq, size: CATCHUP_SIZE });
        const rows = page?.content || [];
        ingestMessages(rows, roomId);
        if (!rows.length || rows.length < CATCHUP_SIZE) {
          break;
        }
        seq = maxSequence(rows);
      }
    } finally {
      catchupRef.current.delete(roomId);
    }
  }, [token, ingestMessages]);

  const loadLatestHistory = useCallback(async (roomId) => {
    if (!token || !roomId) {
      return 0;
    }
    setLoadingHistory(true);
    try {
      const page = await listMessages(token, roomId, { page: 0, size: HISTORY_PAGE_SIZE });
      if (roomIdRef.current !== roomId) {
        return lastSeqRef.current[roomId] || 0;
      }
      const rows = [...(page?.content || [])].reverse();
      historyPageRef.current = 0;
      setHasMoreHistory((page?.totalPages || 0) > 1);
      setMessages(upsertMessages([], rows));
      const max = maxSequence(rows);
      lastSeqRef.current[roomId] = Math.max(lastSeqRef.current[roomId] || 0, max);
      return lastSeqRef.current[roomId] || 0;
    } finally {
      if (roomIdRef.current === roomId) {
        setLoadingHistory(false);
      }
    }
  }, [token]);

  const loadOlderHistory = useCallback(async () => {
    const roomId = roomIdRef.current;
    if (!token || !roomId || loadingHistory || !hasMoreHistory) {
      return;
    }
    setLoadingHistory(true);
    try {
      const nextPage = historyPageRef.current + 1;
      const page = await listMessages(token, roomId, { page: nextPage, size: HISTORY_PAGE_SIZE });
      if (roomIdRef.current !== roomId) {
        return;
      }
      const rows = [...(page?.content || [])].reverse();
      ingestMessages(rows, roomId);
      historyPageRef.current = nextPage;
      setHasMoreHistory(nextPage + 1 < (page?.totalPages || 0));
    } finally {
      if (roomIdRef.current === roomId) {
        setLoadingHistory(false);
      }
    }
  }, [token, loadingHistory, hasMoreHistory, ingestMessages]);

  const joinSocketRoom = useCallback((roomId) => {
    const socket = socketRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN || !roomId) {
      return;
    }
    const after = lastSeqRef.current[roomId] || 0;
    socket.joinRoom(roomId, after);
  }, []);

  const handleEvent = useCallback((event) => {
    if (!event || typeof event !== "object") {
      return;
    }
    const currentRoom = roomIdRef.current;

    switch (event.type) {
      case "CONNECTED":
        rememberName(event.userId, event.username);
        reconnectDelayRef.current = 1000;
        if (currentRoom && historyReadyRef.current) {
          joinSocketRoom(currentRoom);
        }
        break;
      case "HISTORY_SYNC":
        if (event.roomId && event.roomId !== currentRoom) {
          break;
        }
        ingestMessages(event.messages || [], event.roomId);
        if (event.truncated && event.roomId) {
          const from = event.toSequence || lastSeqRef.current[event.roomId] || 0;
          catchUp(event.roomId, from).catch((err) => setError(err.message));
        }
        break;
      case "JOINED":
        if (event.roomId === currentRoom) {
          setJoinedRoomId(event.roomId);
        }
        break;
      case "LEFT":
        if (event.roomId === currentRoom) {
          setJoinedRoomId(null);
        }
        break;
      case "PRESENCE_SNAPSHOT":
        if (event.roomId !== currentRoom) {
          break;
        }
        setOnlineUserIds(new Set((event.online || []).filter((row) => row.status !== "OFFLINE").map((row) => row.userId)));
        setPresenceByUser((prev) => {
          const next = { ...prev };
          for (const row of event.online || []) {
            next[row.userId] = { status: row.status || "ONLINE", lastSeenAt: row.lastSeenAt };
          }
          return next;
        });
        for (const row of event.online || []) {
          rememberName(row.userId, row.username);
        }
        break;
      case "PRESENCE":
        if (event.roomId !== currentRoom) {
          break;
        }
        rememberName(event.userId, event.username);
        setPresenceByUser((prev) => ({
          ...prev,
          [event.userId]: { status: event.status, lastSeenAt: event.lastSeenAt },
        }));
        setOnlineUserIds((prev) => {
          const next = new Set(prev);
          if (event.status && event.status !== "OFFLINE") {
            next.add(event.userId);
          } else {
            next.delete(event.userId);
          }
          return next;
        });
        if (event.status && event.status !== "OFFLINE") {
          refreshMembers(event.roomId).catch(() => {});
        }
        break;
      case "USER_JOINED":
      case "USER_LEFT":
        if (event.roomId === currentRoom) {
          refreshMembers(event.roomId).catch(() => {});
          refreshRooms().catch(() => {});
        }
        break;
      case "MESSAGE_DELETED":
        if (event.roomId === currentRoom) {
          setMessages((prev) =>
            prev.map((msg) => (msg.messageId === event.messageId ? { ...msg, deleted: true, content: "Message deleted" } : msg))
          );
        }
        break;
      case "MESSAGE_EDITED":
        if (event.roomId === currentRoom) {
          setMessages((prev) =>
            prev.map((msg) => (msg.messageId === event.messageId ? { ...msg, content: event.content, edited: true } : msg))
          );
        }
        break;
      case "MESSAGE": {
        const pendingId = event.requestId;
        if (pendingId && pendingRef.current[pendingId]) {
          delete pendingRef.current[pendingId];
          setMessages((prev) => prev.filter((msg) => msg.requestId !== pendingId || msg.messageId));
        }
        ingestMessages([event], event.roomId);
        if (event.roomId === currentRoom) {
          const seq = event.sequenceNumber || lastSeqRef.current[event.roomId] || 0;
          const socket = socketRef.current;
          if (seq && socket?.readyState === WebSocket.OPEN) {
            socket.markRead(event.roomId, seq);
          }
        } else if (event.roomId) {
          setRooms((prev) =>
            prev.map((item) =>
              item.id === event.roomId ? { ...item, unreadCount: (item.unreadCount || 0) + 1 } : item
            )
          );
        }
        break;
      }
      case "REACTION":
        if (event.roomId === currentRoom) {
          setMessages((prev) =>
            prev.map((msg) => {
              if (msg.messageId !== event.messageId) {
                return msg;
              }
              const reactions = [...(msg.reactions || [])];
              const index = reactions.findIndex((row) => row.emoji === event.emoji);
              const current = index >= 0 ? reactions[index] : { emoji: event.emoji, count: 0, userIds: [] };
              const userIds = new Set(current.userIds || []);
              if (event.action === "REMOVE") {
                userIds.delete(event.userId);
              } else {
                userIds.add(event.userId);
              }
              const next = { emoji: event.emoji, count: userIds.size, userIds: [...userIds] };
              if (index >= 0) {
                if (next.count === 0) {
                  reactions.splice(index, 1);
                } else {
                  reactions[index] = next;
                }
              } else if (next.count > 0) {
                reactions.push(next);
              }
              return { ...msg, reactions };
            })
          );
        }
        break;
      case "DELIVERY":
        if (event.roomId === currentRoom && event.messageId) {
          setMessages((prev) =>
            prev.map((msg) => (msg.messageId === event.messageId ? { ...msg, delivered: true } : msg))
          );
        }
        break;
      case "READ":
        if (event.roomId === currentRoom && event.userId !== userId) {
          const seq = event.sequenceNumber || 0;
          setMessages((prev) =>
            prev.map((msg) => (msg.senderId === userId && (msg.sequenceNumber || 0) <= seq ? { ...msg, read: true } : msg))
          );
        }
        break;
      case "ACK": {
        const pending = pendingRef.current[event.requestId];
        if (pending && event.roomId === currentRoom) {
          ingestMessages(
            [
              {
                messageId: event.messageId,
                roomId: event.roomId,
                senderId: pending.senderId,
                content: pending.content,
                timestamp: pending.timestamp,
                sequenceNumber: event.sequenceNumber,
                requestId: event.requestId,
              },
            ],
            event.roomId
          );
          delete pendingRef.current[event.requestId];
          setMessages((prev) => prev.filter((msg) => msg.requestId !== event.requestId || msg.messageId));
        }
        break;
      }
      case "TYPING":
        if (event.roomId !== currentRoom || event.userId === userId) {
          break;
        }
        rememberName(event.userId, event.username);
        setTypingByUser((prev) => {
          if (!event.isTyping) {
            if (!prev[event.userId]) {
              return prev;
            }
            const next = { ...prev };
            delete next[event.userId];
            return next;
          }
          return { ...prev, [event.userId]: event.username };
        });
        if (typingTimersRef.current[event.userId]) {
          clearTimeout(typingTimersRef.current[event.userId]);
        }
        if (event.isTyping) {
          typingTimersRef.current[event.userId] = setTimeout(() => {
            setTypingByUser((prev) => {
              const next = { ...prev };
              delete next[event.userId];
              return next;
            });
          }, TYPING_IDLE_MS);
        }
        break;
      case "ERROR":
        setError(event.message || event.code || "WebSocket error");
        break;
      default:
        break;
    }
  }, [catchUp, ingestMessages, joinSocketRoom, rememberName, refreshMembers, refreshRooms, userId]);

  const handleEventRef = useRef(handleEvent);
  handleEventRef.current = handleEvent;

  useEffect(() => {
    if (!token) {
      return undefined;
    }
    stopReconnectRef.current = false;
    reconnectDelayRef.current = 1000;

    function clearTimer() {
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
    }

    function connect() {
      if (stopReconnectRef.current) {
        return;
      }
      clearTimer();
      socketRef.current?.close();
      const socket = connectChat({
        token,
        onEvent: (event) => handleEventRef.current(event),
        onState: (state) => {
          setWsState(state);
          if (state === "open") {
            reconnectDelayRef.current = 1000;
          }
          if (state === "closed" && !stopReconnectRef.current && !socket.closed) {
            const delay = reconnectDelayRef.current;
            reconnectDelayRef.current = Math.min(delay * 2, 15000);
            reconnectTimerRef.current = setTimeout(connect, delay);
          }
        },
      });
      socketRef.current = socket;
    }

    setWsState("connecting");
    connect();

    return () => {
      stopReconnectRef.current = true;
      clearTimer();
      socketRef.current?.close();
      socketRef.current = null;
      setWsState("closed");
    };
  }, [token]);

  useEffect(() => {
    if (userId && username) {
      rememberName(userId, username);
    }
  }, [userId, username, rememberName]);

  useEffect(() => {
    if (!token) {
      return;
    }
    refreshRooms().catch((err) => setError(err.message));
  }, [token, refreshRooms]);

  useEffect(() => {
    if (!token) {
      return undefined;
    }
    let timer = null;
    function arm() {
      if (timer) {
        clearTimeout(timer);
      }
      timer = setTimeout(() => {
        setMyStatus((current) => {
          if (current !== "ONLINE") {
            return current;
          }
          socketRef.current?.setPresence("AWAY");
          return "AWAY";
        });
      }, AWAY_IDLE_MS);
    }
    function onActivity() {
      setMyStatus((current) => {
        if (current !== "AWAY") {
          return current;
        }
        socketRef.current?.setPresence("ONLINE");
        return "ONLINE";
      });
      arm();
    }
    arm();
    window.addEventListener("mousemove", onActivity);
    window.addEventListener("keydown", onActivity);
    window.addEventListener("click", onActivity);
    return () => {
      if (timer) {
        clearTimeout(timer);
      }
      window.removeEventListener("mousemove", onActivity);
      window.removeEventListener("keydown", onActivity);
      window.removeEventListener("click", onActivity);
    };
  }, [token]);

  const selectRoom = useCallback(async (roomId) => {
    const previous = roomIdRef.current;
    const socket = socketRef.current;
    if (previous && previous !== roomId && socket?.readyState === WebSocket.OPEN) {
      socket.leaveRoom(previous);
    }
    roomIdRef.current = roomId || null;
    historyReadyRef.current = false;
    setJoinedRoomId(null);
    setError(null);
    setTypingByUser({});
    setOnlineUserIds(new Set());
    setPresenceByUser({});
    setMembers([]);
    setMessages([]);
    setHasMoreHistory(false);
    setRoom(null);
    historyPageRef.current = 0;

    if (!roomId || !token) {
      return;
    }

    try {
      const details = await getRoom(token, roomId);
      if (roomIdRef.current !== roomId) {
        return;
      }
      setRoom(details);
      await refreshMembers(roomId);
      const after = await loadLatestHistory(roomId);
      if (roomIdRef.current !== roomId) {
        return;
      }
      historyReadyRef.current = true;
      if (socketRef.current?.readyState === WebSocket.OPEN) {
        socketRef.current.joinRoom(roomId, after || 0);
        if (after) {
          socketRef.current.markRead(roomId, after);
        }
      }
      setRooms((prev) => prev.map((item) => (item.id === roomId ? { ...item, unreadCount: 0 } : item)));
    } catch (err) {
      if (roomIdRef.current === roomId) {
        setError(err.message);
      }
    }
  }, [token, refreshMembers, loadLatestHistory]);

  const create = useCallback(async ({ name, type, maxMembers }) => {
    setError(null);
    const created = await createRoom(token, { name, type, maxMembers });
    await refreshRooms();
    return created;
  }, [token, refreshRooms]);

  const join = useCallback(async (roomIdOrCode, inviteCode) => {
    setError(null);
    const value = String(roomIdOrCode || "").trim();
    const looksUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
    const joined = looksUuid
      ? await joinRoom(token, value, inviteCode)
      : await joinByCode(token, value);
    await refreshRooms();
    return joined;
  }, [token, refreshRooms]);

  const startDm = useCallback(async (userId) => {
    setError(null);
    const created = await createPrivateRoom(token, userId);
    await refreshRooms();
    return created;
  }, [token, refreshRooms]);

  const invite = useCallback(async (roomId) => {
    return createInvite(token, roomId);
  }, [token]);

  const kick = useCallback(async (userId) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    await kickMember(token, roomId, userId);
    await refreshMembers(roomId);
  }, [token, refreshMembers]);

  const mute = useCallback(async (userId, nextMuted) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    if (nextMuted) {
      await muteMember(token, roomId, userId);
    } else {
      await unmuteMember(token, roomId, userId);
    }
    await refreshMembers(roomId);
  }, [token, refreshMembers]);

  const report = useCallback(async (body) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    return reportMember(token, roomId, body);
  }, [token]);

  const ban = useCallback(async (userId, reason) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    await banMember(token, roomId, userId, reason);
    await refreshMembers(roomId);
  }, [token, refreshMembers]);

  const unban = useCallback(async (userId) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    await unbanMember(token, roomId, userId);
  }, [token]);

  const promote = useCallback(async (userId) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    await promoteMember(token, roomId, userId);
    await refreshMembers(roomId);
  }, [token, refreshMembers]);

  const demote = useCallback(async (userId) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    await demoteMember(token, roomId, userId);
    await refreshMembers(roomId);
  }, [token, refreshMembers]);

  const loadReports = useCallback(async () => {
    const roomId = roomIdRef.current;
    if (!token || !roomId) {
      return [];
    }
    return listReports(token, roomId);
  }, [token]);

  const resolveInboxReport = useCallback(async (reportId) => {
    const roomId = roomIdRef.current;
    if (!roomId) {
      return;
    }
    return resolveReport(token, roomId, reportId);
  }, [token]);

  const searchChat = useCallback(async (query) => {
    const roomId = roomIdRef.current;
    if (!token || !roomId || !query?.trim()) {
      return [];
    }
    const page = await searchMessages(token, roomId, query.trim());
    return page?.content || [];
  }, [token]);

  const attachFile = useCallback(async (file, caption) => {
    const roomId = roomIdRef.current;
    if (!token || !roomId || !file) {
      return;
    }
    const saved = await uploadAttachment(token, roomId, file, caption);
    ingestMessages([saved], roomId);
    return saved;
  }, [token, ingestMessages]);

  const toggleReaction = useCallback((messageId, emoji) => {
    const roomId = roomIdRef.current;
    const socket = socketRef.current;
    if (!roomId || !socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    const message = messages.find((item) => item.messageId === messageId);
    const existing = (message?.reactions || []).find((row) => row.emoji === emoji);
    const mine = existing?.userIds?.includes(userId);
    if (mine) {
      socket.removeReaction(roomId, messageId, emoji);
    } else {
      socket.addReaction(roomId, messageId, emoji);
    }
  }, [messages, userId]);

  const setPresence = useCallback((status) => {
    const socket = socketRef.current;
    setMyStatus(status);
    if (socket?.readyState === WebSocket.OPEN) {
      socket.setPresence(status);
    }
  }, []);

  const deleteChat = useCallback((messageId) => {
    const roomId = roomIdRef.current;
    const socket = socketRef.current;
    if (!roomId || !socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    socket.deleteMessage(roomId, messageId);
  }, []);

  const leave = useCallback(async (roomId) => {
    setError(null);
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.leaveRoom(roomId);
    }
    try {
      await leaveRoom(token, roomId);
    } catch (err) {
      setError(err.message);
      throw err;
    }
    if (roomIdRef.current === roomId) {
      roomIdRef.current = null;
      setRoom(null);
      setMessages([]);
      setMembers([]);
      setJoinedRoomId(null);
    }
    delete lastSeqRef.current[roomId];
    await refreshRooms();
  }, [token, refreshRooms]);

  const sendChat = useCallback((content) => {
    const roomId = roomIdRef.current;
    const socket = socketRef.current;
    const trimmed = content.trim();
    if (!roomId || !trimmed || !socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    const requestId = socket.sendMessage(roomId, trimmed);
    const pending = {
      requestId,
      roomId,
      senderId: userId,
      content: trimmed,
      timestamp: new Date().toISOString(),
      pending: true,
    };
    pendingRef.current[requestId] = pending;
    setMessages((prev) => [...prev, pending]);
  }, [userId]);

  const sendTyping = useCallback((isTyping) => {
    const roomId = roomIdRef.current;
    const socket = socketRef.current;
    if (!roomId || !socket || socket.readyState !== WebSocket.OPEN) {
      return;
    }
    socket.typing(roomId, isTyping);
  }, []);

  const clearError = useCallback(() => setError(null), []);

  const muted = members.some((member) => member.userId === userId && member.muted);

  const value = useMemo(
    () => ({
      rooms,
      room,
      members,
      messages,
      onlineUserIds,
      presenceByUser,
      myStatus,
      namesByUserId,
      typingByUser,
      wsState,
      error,
      hasMoreHistory,
      loadingHistory,
      joinedRoomId,
      userId,
      username,
      muted,
      refreshRooms,
      selectRoom,
      create,
      join,
      leave,
      startDm,
      invite,
      kick,
      mute,
      report,
      ban,
      unban,
      promote,
      demote,
      loadReports,
      resolveInboxReport,
      searchChat,
      attachFile,
      toggleReaction,
      setPresence,
      deleteChat,
      sendChat,
      sendTyping,
      loadOlderHistory,
      clearError,
      quickEmojis: QUICK_EMOJIS,
      minSequence: minSequence(messages),
    }),
    [
      rooms,
      room,
      members,
      messages,
      onlineUserIds,
      presenceByUser,
      myStatus,
      namesByUserId,
      typingByUser,
      wsState,
      error,
      hasMoreHistory,
      loadingHistory,
      joinedRoomId,
      userId,
      username,
      muted,
      refreshRooms,
      selectRoom,
      create,
      join,
      leave,
      startDm,
      invite,
      kick,
      mute,
      report,
      ban,
      unban,
      promote,
      demote,
      loadReports,
      resolveInboxReport,
      searchChat,
      attachFile,
      toggleReaction,
      setPresence,
      deleteChat,
      sendChat,
      sendTyping,
      loadOlderHistory,
      clearError,
    ]
  );

  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>;
}

export function useChat() {
  const ctx = useContext(ChatContext);
  if (!ctx) {
    throw new Error("useChat must be used within ChatProvider");
  }
  return ctx;
}
