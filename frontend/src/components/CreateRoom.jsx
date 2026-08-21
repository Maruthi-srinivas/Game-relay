import { useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";
import { ApiError } from "../api/client.js";
import { ROOM_TYPES, roomTypeLabel } from "./roomTypes.js";

export default function CreateRoom({ open, onClose, onCreated }) {
  const { create } = useChat();
  const [name, setName] = useState("");
  const [type, setType] = useState("GAME_ROOM");
  const [maxMembers, setMaxMembers] = useState(50);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  if (!open) {
    return null;
  }

  async function onSubmit(e) {
    e.preventDefault();
    setError("");
    const trimmed = name.trim();
    if (!trimmed || trimmed.length > 100) {
      setError("Name is required (max 100).");
      return;
    }
    const max = Number(maxMembers);
    if (!Number.isInteger(max) || max < 2 || max > 1000) {
      setError("Max members must be 2–1000.");
      return;
    }
    setBusy(true);
    try {
      const room = await create({ name: trimmed, type, maxMembers: max });
      setName("");
      onCreated?.(room);
      onClose?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create lobby.");
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
        aria-labelledby="create-title"
      >
        <h2 id="create-title">New lobby</h2>
        {error ? <div className="alert compact">{error}</div> : null}
        <label className="field">
          Name
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={100}
            placeholder="Ranked queue"
            autoFocus
          />
        </label>
        <label className="field">
          Type
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {ROOM_TYPES.map((value) => (
              <option key={value} value={value}>
                {roomTypeLabel(value)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          Max members
          <input
            type="number"
            min={2}
            max={1000}
            value={maxMembers}
            onChange={(e) => setMaxMembers(e.target.value)}
          />
        </label>
        <div className="modal-actions">
          <button className="ghost" type="button" onClick={onClose}>
            Cancel
          </button>
          <button className="primary" type="submit" disabled={busy}>
            {busy ? "Creating…" : "Create"}
          </button>
        </div>
      </form>
    </div>
  );
}
