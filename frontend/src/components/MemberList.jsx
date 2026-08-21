import { useChat } from "../chat/ChatContext.jsx";
import Avatar from "./Avatar.jsx";

function MemberRow({ id, name, role, online, you }) {
  return (
    <div className="member-item">
      <Avatar name={name} seed={id} size="sm" online={online} />
      <span className="member-name">
        {name}
        {you ? " (you)" : ""}
      </span>
      {role ? <span className={`member-role ${role === "OWNER" ? "owner" : ""}`}>{role === "OWNER" ? "Owner" : "Member"}</span> : null}
    </div>
  );
}

export default function MemberList() {
  const { members, onlineUserIds, namesByUserId, userId } = useChat();

  const extraOnline = [...onlineUserIds].filter(
    (id) => !members.some((member) => member.userId === id)
  );

  const online = [
    ...members.filter((member) => onlineUserIds.has(member.userId)),
    ...extraOnline.map((id) => ({ userId: id, username: namesByUserId[id] || id.slice(0, 8), role: null })),
  ];
  const offline = members.filter((member) => !onlineUserIds.has(member.userId));

  return (
    <section className="members-panel">
      <h3>Squad</h3>
      <div className="group-label">Online — {online.length}</div>
      <div className="member-list">
        {online.length === 0 ? <div className="empty">Nobody live</div> : null}
        {online.map((member) => (
          <MemberRow
            key={member.userId}
            id={member.userId}
            name={member.username}
            role={member.role}
            online
            you={member.userId === userId}
          />
        ))}
      </div>
      <div className="group-label">Offline — {offline.length}</div>
      <div className="member-list">
        {offline.length === 0 ? <div className="empty">Everyone's in</div> : null}
        {offline.map((member) => (
          <MemberRow
            key={member.userId}
            id={member.userId}
            name={member.username}
            role={member.role}
            you={member.userId === userId}
          />
        ))}
      </div>
    </section>
  );
}
