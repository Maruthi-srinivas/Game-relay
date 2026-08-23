import { useEffect, useRef, useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";
import { roomSlug } from "./roomTypes.js";

const MAX_LEN = 2000;
const TYPING_THROTTLE_MS = 400;

export default function Composer({ disabled }) {
  const { sendChat, sendTyping, attachFile, room } = useChat();
  const [text, setText] = useState("");
  const typingSentRef = useRef(false);
  const lastSentRef = useRef(0);
  const idleTimerRef = useRef(null);
  const fileRef = useRef(null);
  const slug = roomSlug(room?.name);

  function stopTyping() {
    if (idleTimerRef.current) {
      clearTimeout(idleTimerRef.current);
      idleTimerRef.current = null;
    }
    if (typingSentRef.current) {
      sendTyping(false);
      typingSentRef.current = false;
    }
  }

  useEffect(() => () => stopTyping(), []);

  function onChange(e) {
    const value = e.target.value.slice(0, MAX_LEN);
    setText(value);
    if (disabled) {
      return;
    }
    const now = Date.now();
    if (value.trim()) {
      if (!typingSentRef.current || now - lastSentRef.current > TYPING_THROTTLE_MS) {
        sendTyping(true);
        typingSentRef.current = true;
        lastSentRef.current = now;
      }
      if (idleTimerRef.current) {
        clearTimeout(idleTimerRef.current);
      }
      idleTimerRef.current = setTimeout(stopTyping, 3000);
    } else {
      stopTyping();
    }
  }

  function onSubmit(e) {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || disabled) {
      return;
    }
    sendChat(trimmed);
    setText("");
    stopTyping();
  }

  async function onFile(e) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file || disabled) {
      return;
    }
    await attachFile(file, text.trim() || undefined);
    setText("");
    stopTyping();
  }

  const nearCap = text.length > MAX_LEN - 200;

  return (
    <form className="composer" onSubmit={onSubmit}>
      <input ref={fileRef} type="file" hidden accept="image/png,image/jpeg,image/webp,image/gif,application/pdf" onChange={onFile} />
      <button type="button" className="ghost attach" disabled={disabled} onClick={() => fileRef.current?.click()} title="Attach">
        +
      </button>
      <div className="composer-wrap">
        <input
          value={text}
          onChange={onChange}
          maxLength={MAX_LEN}
          placeholder={disabled ? (room ? "Muted or connecting…" : "Connecting…") : `Message #${slug}`}
          disabled={disabled}
          autoComplete="off"
        />
        {nearCap ? (
          <span className="count">
            {text.length}/{MAX_LEN}
          </span>
        ) : null}
      </div>
      <button className="primary send" type="submit" disabled={disabled || !text.trim()} title="Send">
        <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
          <path d="M2.2 8.1 13.4 2.6c.5-.3 1 .3.7.8L9.4 14c-.2.5-.9.5-1.2 0L6.7 10.4 2.2 8.9c-.6-.2-.6-.7 0-.8Z" />
        </svg>
      </button>
    </form>
  );
}
