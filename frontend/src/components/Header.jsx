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

export default function UserDock() {
  const { session, logout } = useAuth();
  const { wsState } = useChat();

  return (
    <div className="user-dock">
      <Avatar name={session?.username} seed={session?.userId} size="md" online={wsState === "open"} />
      <div className="who">
        <div className="name">{session?.username}</div>
        <div className="status-line">
          <span className={`status-dot ${wsState}`} />
          {WS_LABEL[wsState] || wsState}
        </div>
      </div>
      <TrafficButton />
      <button type="button" className="ghost" onClick={logout} title="Log out">
        Out
      </button>
    </div>
  );
}
