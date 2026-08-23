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

export default function MemberList() {
  const {
    members,
    onlineUserIds,
    namesByUserId,
    userId,
    presenceByUser,
    startDm,
    kick,
    mute,
    report,
    ban,
    promote,
    demote,
    room,
  } = useChat();
  const navigate = useNavigate();
  const self = members.find((member) => member.userId === userId);
  const canModerate = self?.role === "OWNER" || self?.role === "MODERATOR";
  const isOwner = self?.role === "OWNER";

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

  function onBan(id) {
    const reason = window.prompt("Ban reason (optional)") || "";
    if (reason !== null) {
      ban(id, reason);
    }
  }

  function row(member, isOnline) {
    const presence = presenceByUser[member.userId] || {};
    const you = member.userId === userId;
    return (
      <div key={member.userId} className="member-item">
        <Avatar name={member.username} seed={member.userId} size="sm" online={isOnline && presence.status !== "AWAY"} />
        <div className="member-meta">
          <span className="member-name">
            {member.username}
            {you ? " (you)" : ""}
            {member.muted ? " · muted" : ""}
          </span>
          <span className="member-sub">{isOnline ? statusLabel(presence.status || "ONLINE") : formatLastSeen(presence.lastSeenAt)}</span>
        </div>
        {member.role ? (
          <span className={`member-role ${member.role === "OWNER" ? "owner" : ""}`}>
            {member.role === "OWNER" ? "Owner" : member.role === "MODERATOR" ? "Mod" : "Member"}
          </span>
        ) : null}
        {!you ? (
          <div className="member-actions">
            <button type="button" className="ghost tiny" onClick={() => onDm(member.userId)}>
              DM
            </button>
            {canModerate && room?.type !== "GLOBAL" ? (
              <>
                <button type="button" className="ghost tiny" onClick={() => mute(member.userId, !member.muted)}>
                  {member.muted ? "Unmute" : "Mute"}
                </button>
                <button type="button" className="ghost tiny" onClick={() => kick(member.userId)}>
                  Kick
                </button>
                <button type="button" className="ghost tiny" onClick={() => onBan(member.userId)}>
                  Ban
                </button>
                {isOwner && member.role === "MEMBER" ? (
                  <button type="button" className="ghost tiny" onClick={() => promote(member.userId)}>
                    Promote
                  </button>
                ) : null}
                {isOwner && member.role === "MODERATOR" ? (
                  <button type="button" className="ghost tiny" onClick={() => demote(member.userId)}>
                    Demote
                  </button>
                ) : null}
              </>
            ) : null}
            <button type="button" className="ghost tiny" onClick={() => onReport(member.userId)}>
              Report
            </button>
          </div>
        ) : null}
      </div>
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
