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

  return (
    <div className="user-dock">
      <Avatar name={session?.username} seed={session?.userId} size="md" online={wsState === "open" && myStatus !== "AWAY"} />
      <div className="who">
        <div className="name">{session?.username}</div>
        <div className="status-line">
          <span className={`status-dot ${wsState}`} />
          {WS_LABEL[wsState] || wsState}
        </div>
        <label className="presence-picker">
          <select
            value={myStatus}
            disabled={wsState !== "open"}
            onChange={(e) => setPresence(e.target.value)}
            aria-label="Presence"
          >
            {STATUSES.map((status) => (
              <option key={status.id} value={status.id}>
                {status.label}
              </option>
            ))}
          </select>
        </label>
      </div>
      <TrafficButton />
      <button type="button" className="ghost" onClick={logout} title="Log out">
        Out
      </button>
    </div>
  );
}
