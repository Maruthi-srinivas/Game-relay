import { useEffect, useState } from "react";
import { useAuth } from "../auth/AuthContext.jsx";
import { useChat } from "../chat/ChatContext.jsx";
import { getHealth } from "../api/health.js";
import { TrafficButton } from "./TrafficLog.jsx";

const WS_LABEL = {
  connecting: "connecting",
  open: "live",
  closed: "offline",
  error: "error",
};

export default function Header() {
  const { session, logout } = useAuth();
  const { wsState } = useChat();
  const [health, setHealth] = useState("…");

  useEffect(() => {
    let cancelled = false;

    async function probe() {
      try {
        const data = await getHealth();
        if (!cancelled) {
          setHealth(data?.status === "UP" ? "UP" : "DOWN");
        }
      } catch {
        if (!cancelled) {
          setHealth("DOWN");
        }
      }
    }

    probe();
    const timer = setInterval(probe, 15000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  return (
    <header className="topbar">
      <div className="topbar-title">
        <h1>Game Chat</h1>
        <span className={`badge ${health === "UP" ? "up" : health === "DOWN" ? "down" : ""}`}>
          API {health}
        </span>
        <span className="ws-chip">
          <span className={`status-dot ${wsState}`} />
          {WS_LABEL[wsState] || wsState}
        </span>
      </div>
      <div className="topbar-user">
        <TrafficButton />
        <span className="who">{session?.username}</span>
        <button type="button" onClick={logout}>
          Log out
        </button>
      </div>
    </header>
  );
}
