import { useState } from "react";
import { useChat } from "../chat/ChatContext.jsx";
import { ApiError } from "../api/client.js";

const ROOM_TYPES = ["GAME_ROOM", "GLOBAL", "TEAM", "PARTY", "PRIVATE"];

export default function CreateRoom({ onCreated }) {
  const { create } = useChat();
  const [name, setName] = useState("Arena");
  const [type, setType] = useState("GAME_ROOM");
  const [maxMembers, setMaxMembers] = useState(50);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

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
      onCreated?.(room);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create room.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel" onSubmit={onSubmit}>
      <h3>Create room</h3>
      {error ? <div className="alert compact">{error}</div> : null}
      <label className="field">
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
      </label>
      <label className="field">
        Type
        <select value={type} onChange={(e) => setType(e.target.value)}>
          {ROOM_TYPES.map((value) => (
            <option key={value} value={value}>
              {value}
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
      <button className="primary" type="submit" disabled={busy}>
        {busy ? "Creating…" : "Create"}
      </button>
    </form>
  );
}
