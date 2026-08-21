import { NavLink } from "react-router-dom";
import { useChat } from "../chat/ChatContext.jsx";

export default function RoomList({ selectedId }) {
  const { rooms, refreshRooms } = useChat();

  return (
    <section className="panel">
      <div className="panel-head">
        <h3>Rooms</h3>
        <button type="button" onClick={() => refreshRooms().catch(() => {})}>
          Refresh
        </button>
      </div>
      <div className="room-list">
        {rooms.length === 0 ? (
          <div className="empty">No rooms yet</div>
        ) : (
          rooms.map((room) => (
            <NavLink
              key={room.id}
              to={`/rooms/${room.id}`}
              className={`room-item ${room.id === selectedId ? "active" : ""}`}
            >
              <span className="room-name">{room.name}</span>
              <span className="room-meta">{room.type}</span>
            </NavLink>
          ))
        )}
      </div>
    </section>
  );
}
