import { NavLink } from "react-router-dom";
import { useChat } from "../chat/ChatContext.jsx";
import { roomTypeGlyph, roomTypeLabel } from "./roomTypes.js";

export default function RoomList({ selectedId }) {
  const { rooms, refreshRooms } = useChat();

  return (
    <div className="room-scroll">
      <div className="panel-head">
        <h3>Lobbies</h3>
        <button type="button" className="ghost" onClick={() => refreshRooms().catch(() => {})}>
          Refresh
        </button>
      </div>
      <div className="room-list">
        {rooms.length === 0 ? (
          <div className="empty">No lobbies yet</div>
        ) : (
          rooms.map((room) => (
            <NavLink
              key={room.id}
              to={`/rooms/${room.id}`}
              className={`room-item ${room.id === selectedId ? "active" : ""}`}
              title={roomTypeLabel(room.type)}
            >
              <span className="room-glyph">{roomTypeGlyph(room.type)}</span>
              <span className="room-name">{room.name}</span>
            </NavLink>
          ))
        )}
      </div>
    </div>
  );
}
