import { useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";
import { ApiError } from "../api/client.js";

export default function JoinRoom({ onJoined }) {
  const { join } = useChat();
  const [roomId, setRoomId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function onSubmit(e) {
    e.preventDefault();
    setError("");
    const id = roomId.trim();
    if (!id) {
      setError("Paste a room UUID.");
      return;
    }
    setBusy(true);
    try {
      const room = await join(id);
      setRoomId("");
      onJoined?.(room);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not join room.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel" onSubmit={onSubmit}>
      <h3>Join by id</h3>
      {error ? <div className="alert compact">{error}</div> : null}
      <label className="field">
        Room id
        <input
          value={roomId}
          onChange={(e) => setRoomId(e.target.value)}
          placeholder="paste UUID"
          autoComplete="off"
        />
      </label>
      <button type="submit" disabled={busy}>
        {busy ? "Joining…" : "Join"}
      </button>
    </form>
  );
}
