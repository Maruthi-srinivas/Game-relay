import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { listMessages } from "../api/messages.js";
import { createRoom, getRoom, joinRoom, leaveRoom, listMembers, listRooms } from "../api/rooms.js";
import { connectChat } from "../ws/chatSocket.js";
import { useAuth } from "../auth/AuthContext.jsx";

const ChatContext = createContext(null);
const HISTORY_PAGE_SIZE = 50;
const CATCHUP_SIZE = 100;
const TYPING_IDLE_MS = 3000;

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
        setOnlineUserIds(new Set((event.online || []).map((row) => row.userId)));
        for (const row of event.online || []) {
          rememberName(row.userId, row.username);
        }
        break;
      case "PRESENCE":
        if (event.roomId !== currentRoom) {
          break;
        }
        rememberName(event.userId, event.username);
        setOnlineUserIds((prev) => {
          const next = new Set(prev);
          if (event.status === "ONLINE") {
            next.add(event.userId);
          } else {
            next.delete(event.userId);
          }
          return next;
        });
        if (event.status === "ONLINE") {
          refreshMembers(event.roomId).catch(() => {});
        }
        break;
      case "MESSAGE": {
        const pendingId = event.requestId;
        if (pendingId && pendingRef.current[pendingId]) {
          delete pendingRef.current[pendingId];
          setMessages((prev) => prev.filter((msg) => msg.requestId !== pendingId || msg.messageId));
        }
        ingestMessages([event], event.roomId);
        break;
      }
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
  }, [catchUp, ingestMessages, joinSocketRoom, rememberName, refreshMembers, userId]);

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
      }
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

  const join = useCallback(async (roomId) => {
    setError(null);
    const joined = await joinRoom(token, roomId);
    await refreshRooms();
    return joined;
  }, [token, refreshRooms]);

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

  const value = useMemo(
    () => ({
      rooms,
      room,
      members,
      messages,
      onlineUserIds,
      namesByUserId,
      typingByUser,
      wsState,
      error,
      hasMoreHistory,
      loadingHistory,
      joinedRoomId,
      userId,
      username,
      refreshRooms,
      selectRoom,
      create,
      join,
      leave,
      sendChat,
      sendTyping,
      loadOlderHistory,
      clearError,
      minSequence: minSequence(messages),
    }),
    [
      rooms,
      room,
      members,
      messages,
      onlineUserIds,
      namesByUserId,
      typingByUser,
      wsState,
      error,
      hasMoreHistory,
      loadingHistory,
      joinedRoomId,
      userId,
      username,
      refreshRooms,
      selectRoom,
      create,
      join,
      leave,
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
