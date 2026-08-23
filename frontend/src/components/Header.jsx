import { useEffect, useRef, useState } from "react";
import { useAuth } from "../auth/AuthContext.jsx";
import { useChat } from "../chat/ChatContext.jsx";
import Avatar from "./Avatar.jsx";
import { TrafficButton } from "./TrafficLog.jsx";

const WS_LABEL = {
  connecting: "Connecting",
  open: "Live",
  closed: "Offline",
  error: "Offline",
};

const STATUSES = [
  { id: "ONLINE", label: "Online" },
  { id: "AWAY", label: "Away" },
  { id: "IN_GAME", label: "In game" },
];

export default function UserDock() {
  const { session, logout } = useAuth();
  const { wsState, myStatus, setPresence } = useChat();
  const [open, setOpen] = useState(false);
  const pickerRef = useRef(null);
  const current = STATUSES.find((status) => status.id === myStatus) || STATUSES[0];
  const canChange = wsState === "open";

  useEffect(() => {
    if (!open) {
      return undefined;
    }
    function onPointerDown(event) {
      if (!pickerRef.current?.contains(event.target)) {
        setOpen(false);
      }
    }
    function onKeyDown(event) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  function chooseStatus(status) {
    setPresence(status);
    setOpen(false);
  }

  return (
    <div className="user-dock">
      <Avatar name={session?.username} seed={session?.userId} size="md" online={wsState === "open" && myStatus !== "AWAY"} />
      <div className="who">
        <div className="name">{session?.username}</div>
        <div className="status-line">
          <span className={`status-dot ${wsState}`} />
          {WS_LABEL[wsState] || wsState}
        </div>
        <div className={`presence-picker ${open ? "open" : ""}`} ref={pickerRef}>
          <button
            type="button"
            className="presence-trigger"
            disabled={!canChange}
            aria-haspopup="listbox"
            aria-expanded={open}
            aria-label="Set presence"
            onClick={() => setOpen((value) => !value)}
          >
            <span className={`presence-dot ${current.id}`} />
            <span className="presence-label">{current.label}</span>
            <svg className="presence-caret" viewBox="0 0 12 12" aria-hidden="true">
              <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          {open ? (
            <ul className="presence-menu" role="listbox" aria-label="Presence">
              {STATUSES.map((status) => {
                const selected = status.id === myStatus;
                return (
                  <li key={status.id} role="none">
                    <button
                      type="button"
                      role="option"
                      aria-selected={selected}
                      className={selected ? "active" : ""}
                      onClick={() => chooseStatus(status.id)}
                    >
                      <span className={`presence-dot ${status.id}`} />
                      <span>{status.label}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : null}
        </div>
      </div>
      <div className="user-dock-actions">
        <TrafficButton />
        <button type="button" className="ghost" onClick={logout} title="Log out">
          Out
        </button>
      </div>
    </div>
  );
}
