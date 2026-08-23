import { useEffect, useRef, useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";
import { useAuth } from "../auth/AuthContext.jsx";
import Avatar from "./Avatar.jsx";

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

function ticks(msg, mine) {
  if (!mine) {
    return "";
  }
  if (msg.pending) {
    return "○";
  }
  if (msg.read) {
    return "✓✓";
  }
  if (msg.delivered || msg.messageId) {
    return "✓";
  }
  return "○";
}

function AttachmentView({ attachment, token }) {
  const [url, setUrl] = useState(null);
  const image = (attachment.contentType || "").startsWith("image/");

  useEffect(() => {
    if (!attachment?.id || !token) {
      return undefined;
    }
    let objectUrl;
    let cancelled = false;
    fetch(`/api/attachments/${attachment.id}`, { headers: { Authorization: `Bearer ${token}` } })
      .then((res) => (res.ok ? res.blob() : Promise.reject()))
      .then((blob) => {
        if (cancelled) {
          return;
        }
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [attachment?.id, token]);

  if (!url) {
    return <div className="muted tiny">{attachment.originalName || "attachment"}</div>;
  }
  if (image) {
    return <img className="msg-image" src={url} alt={attachment.originalName || "image"} />;
  }
  return (
    <a className="msg-file" href={url} target="_blank" rel="noreferrer">
      {attachment.originalName || "Download file"}
    </a>
  );
}

export default function MessageList() {
  const {
    messages,
    namesByUserId,
    userId,
    hasMoreHistory,
    loadingHistory,
    loadOlderHistory,
    typingByUser,
    deleteChat,
    toggleReaction,
    quickEmojis,
  } = useChat();
  const { session } = useAuth();
  const token = session?.token;
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
        <button className="load-older ghost" type="button" disabled={loadingHistory} onClick={loadOlderHistory}>
          {loadingHistory ? "Loading…" : "Load older messages"}
        </button>
      ) : null}
      {messages.length === 0 && !loadingHistory ? <div className="empty">No messages yet. Say something.</div> : null}
      {messages.map((msg) => {
        const mine = msg.senderId === userId;
        const name = namesByUserId[msg.senderId] || (mine ? "you" : msg.senderId?.slice(0, 8));
        return (
          <div key={msg.messageId || msg.requestId} className={`msg ${mine ? "mine" : ""} ${msg.pending ? "pending" : ""}`}>
            <Avatar name={name} seed={msg.senderId} size="lg" />
            <div>
              <div className="msg-meta">
                <span className="from">{name}</span>
                <span className="time">{msg.pending ? "sending…" : formatTime(msg.timestamp)}</span>
                {mine ? <span className="ticks">{ticks(msg, mine)}</span> : null}
              </div>
              <div className="body">
                {msg.deleted ? <em className="muted">Message deleted</em> : msg.content}
                {msg.edited && !msg.deleted ? <span className="muted"> (edited)</span> : null}
              </div>
              {(msg.attachments || []).map((file) => (
                <AttachmentView key={file.id} attachment={file} token={token} />
              ))}
              {!msg.deleted && msg.messageId ? (
                <div className="reactions">
                  {(msg.reactions || []).map((row) => (
                    <button
                      key={row.emoji}
                      type="button"
                      className={`reaction ${row.userIds?.includes(userId) ? "mine" : ""}`}
                      onClick={() => toggleReaction(msg.messageId, row.emoji)}
                    >
                      {row.emoji} {row.count}
                    </button>
                  ))}
                  {(quickEmojis || ["👍", "❤️"]).map((emoji) => (
                    <button key={emoji} type="button" className="reaction add" onClick={() => toggleReaction(msg.messageId, emoji)}>
                      {emoji}
                    </button>
                  ))}
                </div>
              ) : null}
              {mine && !msg.deleted && msg.messageId ? (
                <button type="button" className="ghost tiny" onClick={() => deleteChat(msg.messageId)}>
                  Delete
                </button>
              ) : null}
            </div>
          </div>
        );
      })}
      {typingNames.length ? (
        <div className="typing">
          {typingNames.length === 1 ? `${typingNames[0]} is typing…` : `${typingNames.join(", ")} are typing…`}
        </div>
      ) : null}
    </div>
  );
}
