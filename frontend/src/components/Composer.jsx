import { useEffect, useRef, useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";

const MAX_LEN = 2000;
const TYPING_THROTTLE_MS = 400;

export default function Composer({ disabled }) {
  const { sendChat, sendTyping } = useChat();
  const [text, setText] = useState("");
  const typingSentRef = useRef(false);
  const lastSentRef = useRef(0);
  const idleTimerRef = useRef(null);

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

  return (
    <form className="composer" onSubmit={onSubmit}>
      <input
        value={text}
        onChange={onChange}
        maxLength={MAX_LEN}
        placeholder={disabled ? "Connecting…" : "Message"}
        disabled={disabled}
        autoComplete="off"
      />
      <span className="count">
        {text.length}/{MAX_LEN}
      </span>
      <button className="primary" type="submit" disabled={disabled || !text.trim()}>
        Send
      </button>
    </form>
  );
}
