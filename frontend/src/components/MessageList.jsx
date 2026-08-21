import { useEffect, useRef } from "react";
import { useChat } from "../chat/ChatContext.jsx";

function formatTime(ts) {
  if (!ts) {
    return "";
  }
  try {
    return new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  } catch {
    return "";
  }
}

export default function MessageList() {
  const { messages, namesByUserId, userId, hasMoreHistory, loadingHistory, loadOlderHistory, typingByUser } =
    useChat();
  const scrollerRef = useRef(null);
  const stickToBottomRef = useRef(true);

  useEffect(() => {
    const el = scrollerRef.current;
    if (el && stickToBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    }
  }, [messages, typingByUser]);

  function onScroll(e) {
    const el = e.currentTarget;
    stickToBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    if (el.scrollTop < 40 && hasMoreHistory && !loadingHistory) {
      const prevHeight = el.scrollHeight;
      loadOlderHistory().then(() => {
        requestAnimationFrame(() => {
          if (scrollerRef.current) {
            scrollerRef.current.scrollTop = scrollerRef.current.scrollHeight - prevHeight;
          }
        });
      });
    }
  }

  const typingNames = Object.values(typingByUser);

  return (
    <div className="transcript" ref={scrollerRef} onScroll={onScroll}>
      {hasMoreHistory ? (
        <button className="load-older" type="button" disabled={loadingHistory} onClick={loadOlderHistory}>
          {loadingHistory ? "Loading…" : "Load older messages"}
        </button>
      ) : null}
      {messages.length === 0 && !loadingHistory ? <div className="empty">No messages yet</div> : null}
      {messages.map((msg) => {
        const mine = msg.senderId === userId;
        const name = namesByUserId[msg.senderId] || (mine ? "you" : msg.senderId?.slice(0, 8));
        return (
          <div key={msg.messageId || msg.requestId} className={`msg ${mine ? "mine" : ""} ${msg.pending ? "pending" : ""}`}>
            <div className="msg-meta">
              <span className="from">{name}</span>
              <span className="time">{msg.pending ? "sending…" : formatTime(msg.timestamp)}</span>
            </div>
            <div className="body">{msg.content}</div>
          </div>
        );
      })}
      {typingNames.length ? (
        <div className="typing">
          {typingNames.length === 1
            ? `${typingNames[0]} is typing…`
            : `${typingNames.join(", ")} are typing…`}
        </div>
      ) : null}
    </div>
  );
}
