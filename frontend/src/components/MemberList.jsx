import { useChat } from "../chat/ChatContext.jsx";

export default function MemberList() {
  const { members, onlineUserIds, namesByUserId, userId } = useChat();

  const extraOnline = [...onlineUserIds].filter(
    (id) => !members.some((member) => member.userId === id)
  );

  return (
    <section className="panel members-panel">
      <h3>Members</h3>
      <div className="member-list">
        {members.length === 0 ? <div className="empty">No members loaded</div> : null}
        {members.map((member) => (
          <div key={member.userId} className="member-item">
            <span className={`presence ${onlineUserIds.has(member.userId) ? "on" : ""}`} />
            <span className="member-name">
              {member.username}
              {member.userId === userId ? " (you)" : ""}
            </span>
            <span className="member-role">{member.role}</span>
          </div>
        ))}
        {extraOnline.map((id) => (
          <div key={id} className="member-item">
            <span className="presence on" />
            <span className="member-name">{namesByUserId[id] || id.slice(0, 8)}</span>
            <span className="member-role">online</span>
          </div>
        ))}
      </div>
    </section>
  );
}
