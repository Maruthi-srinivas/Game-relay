import { useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";
import { ApiError } from "../api/client.js";

export default function JoinRoom({ open, onClose, onJoined }) {
  const { join } = useChat();
  const [roomId, setRoomId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  if (!open) {
    return null;
  }

  async function onSubmit(e) {
    e.preventDefault();
    setError("");
    const id = roomId.trim();
    if (!id) {
      setError("Paste an invite id.");
      return;
    }
    setBusy(true);
    try {
      const room = await join(id);
      setRoomId("");
      onJoined?.(room);
      onClose?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not join lobby.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <form
        className="modal-card"
        onSubmit={onSubmit}
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-labelledby="join-title"
      >
        <h2 id="join-title">Join with invite</h2>
        <p className="muted">Paste a lobby UUID or invite code.</p>
        {error ? <div className="alert compact">{error}</div> : null}
        <label className="field">
          Invite
          <input
            value={roomId}
            onChange={(e) => setRoomId(e.target.value)}
            placeholder="UUID or invite code"
            autoComplete="off"
            autoFocus
          />
        </label>
        <div className="modal-actions">
          <button className="ghost" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="primary" type="submit" disabled={busy}>
            {busy ? "Joining…" : "Join"}
          </button>
        </div>
      </form>
    </div>
  );
}
