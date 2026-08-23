import { useEffect, useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";

export default function ReportInbox() {
  const { members, userId, loadReports, resolveInboxReport, unban } = useChat();
  const [reports, setReports] = useState([]);
  const self = members.find((member) => member.userId === userId);
  const canModerate = self?.role === "OWNER" || self?.role === "MODERATOR";

  async function refresh() {
    if (!canModerate) {
      return;
    }
    const rows = await loadReports();
    setReports(Array.isArray(rows) ? rows : []);
  }

  useEffect(() => {
    refresh().catch(() => {});
  }, [canModerate]);

  if (!canModerate) {
    return null;
  }

  async function onResolve(id) {
    await resolveInboxReport(id);
    await refresh();
  }

  async function onUnban() {
    const target = window.prompt("User id to unban");
    if (target) {
      await unban(target.trim());
    }
  }

  return (
    <section className="reports-panel">
      <div className="panel-head">
        <h3>Reports</h3>
        <button type="button" className="ghost tiny" onClick={onUnban}>
          Unban
        </button>
      </div>
      <div className="report-list">
        {reports.length === 0 ? <div className="empty">No reports</div> : null}
        {reports.map((item) => (
          <div key={item.id} className="report-item">
            <div className="report-meta">
              <strong>{item.status || "OPEN"}</strong>
              <span className="muted">{item.reason}</span>
            </div>
            {item.status !== "RESOLVED" ? (
              <button type="button" className="ghost tiny" onClick={() => onResolve(item.id)}>
                Resolve
              </button>
            ) : null}
          </div>
        ))}
      </div>
    </section>
  );
}
