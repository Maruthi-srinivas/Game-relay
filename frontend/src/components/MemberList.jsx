import { useNavigate } from "react-router-dom";
import { useChat } from "../chat/ChatContext.jsx";
import Avatar from "./Avatar.jsx";

function formatLastSeen(iso) {
  if (!iso) {
    return "Offline";
  }
  try {
    return `Seen ${new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}`;
  } catch {
    return "Offline";
  }
}

function statusLabel(status) {
  if (status === "IN_GAME") {
    return "In game";
  }
  if (status === "AWAY") {
    return "Away";
  }
  if (status === "ONLINE") {
    return "Online";
  }
  return "Offline";
}

function MemberRow({ id, name, role, online, you, status, lastSeenAt, muted, canModerate, onDm, onKick, onMute, onReport }) {
  return (
    <div className="member-item">
      <Avatar name={name} seed={id} size="sm" online={online && status !== "AWAY"} />
      <div className="member-meta">
        <span className="member-name">
          {name}
          {you ? " (you)" : ""}
          {muted ? " · muted" : ""}
        </span>
        <span className="member-sub">{online ? statusLabel(status) : formatLastSeen(lastSeenAt)}</span>
      </div>
      {role ? <span className={`member-role ${role === "OWNER" ? "owner" : ""}`}>{role === "OWNER" ? "Owner" : role === "MODERATOR" ? "Mod" : "Member"}</span> : null}
      {!you ? (
        <div className="member-actions">
          <button type="button" className="ghost tiny" onClick={() => onDm(id)}>
            DM
          </button>
          {canModerate ? (
            <>
              <button type="button" className="ghost tiny" onClick={() => onMute(id, !muted)}>
                {muted ? "Unmute" : "Mute"}
              </button>
              <button type="button" className="ghost tiny" onClick={() => onKick(id)}>
                Kick
              </button>
            </>
          ) : null}
          <button type="button" className="ghost tiny" onClick={() => onReport(id)}>
            Report
          </button>
        </div>
      ) : null}
    </div>
  );
}

export default function MemberList() {
  const { members, onlineUserIds, namesByUserId, userId, presenceByUser, startDm, kick, mute, report, room } = useChat();
  const navigate = useNavigate();
  const self = members.find((member) => member.userId === userId);
  const canModerate = self?.role === "OWNER" || self?.role === "MODERATOR";

  const extraOnline = [...onlineUserIds].filter((id) => !members.some((member) => member.userId === id));

  const online = [
    ...members.filter((member) => onlineUserIds.has(member.userId)),
    ...extraOnline.map((id) => ({ userId: id, username: namesByUserId[id] || id.slice(0, 8), role: null, muted: false })),
  ];
  const offline = members.filter((member) => !onlineUserIds.has(member.userId));

  async function onDm(id) {
    const created = await startDm(id);
    navigate(`/rooms/${created.id}`);
  }

  function onReport(id) {
    const reason = window.prompt("Why are you reporting this player?");
    if (reason) {
      report({ targetUserId: id, reason });
    }
  }

  function row(member, isOnline) {
    const presence = presenceByUser[member.userId] || {};
    return (
      <MemberRow
        key={member.userId}
        id={member.userId}
        name={member.username}
        role={member.role}
        online={isOnline}
        you={member.userId === userId}
        status={presence.status || (isOnline ? "ONLINE" : "OFFLINE")}
        lastSeenAt={presence.lastSeenAt}
        muted={member.muted}
        canModerate={canModerate && room?.type !== "GLOBAL"}
        onDm={onDm}
        onKick={kick}
        onMute={mute}
        onReport={onReport}
      />
    );
  }

  return (
    <section className="members-panel">
      <h3>Squad</h3>
      <div className="group-label">Online — {online.length}</div>
      <div className="member-list">
        {online.length === 0 ? <div className="empty">Nobody live</div> : null}
        {online.map((member) => row(member, true))}
      </div>
      <div className="group-label">Offline — {offline.length}</div>
      <div className="member-list">
        {offline.length === 0 ? <div className="empty">Everyone's in</div> : null}
        {offline.map((member) => row(member, false))}
      </div>
    </section>
  );
}
