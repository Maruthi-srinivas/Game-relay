import { useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useChat } from "../chat/ChatContext.jsx";
import Header from "../components/Header.jsx";
import RoomList from "../components/RoomList.jsx";
import CreateRoom from "../components/CreateRoom.jsx";
import JoinRoom from "../components/JoinRoom.jsx";
import MessageList from "../components/MessageList.jsx";
import Composer from "../components/Composer.jsx";
import MemberList from "../components/MemberList.jsx";

export default function ChatPage() {
  const { roomId } = useParams();
  const navigate = useNavigate();
  const chat = useChat();

  useEffect(() => {
    chat.selectRoom(roomId || null);
    // selectRoom is stable enough for room changes; avoid looping on the whole context
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roomId]);

  async function onCreated(room) {
    navigate(`/rooms/${room.id}`);
  }

  async function onJoined(room) {
    navigate(`/rooms/${room.id}`);
  }

  async function onLeave() {
    if (!roomId) {
      return;
    }
    try {
      await chat.leave(roomId);
      navigate("/");
    } catch {
      // error banner comes from ChatContext
    }
  }

  async function copyRoomId() {
    if (!chat.room?.id) {
      return;
    }
    try {
      await navigator.clipboard.writeText(chat.room.id);
    } catch {
      window.prompt("Copy room id", chat.room.id);
    }
  }

  return (
    <div className="app-shell">
      <Header />
      {chat.error ? (
        <div className="banner">
          <span>{chat.error}</span>
          <button type="button" onClick={chat.clearError}>
            Dismiss
          </button>
        </div>
      ) : null}
      <div className="app-body">
        <aside className="sidebar">
          <CreateRoom onCreated={onCreated} />
          <JoinRoom onJoined={onJoined} />
          <RoomList selectedId={roomId} />
        </aside>
        <section className="chat-main">
          {roomId ? (
            chat.room ? (
              <>
                <div className="chat-head">
                  <div>
                    <h2>{chat.room.name}</h2>
                    <p className="muted">
                      {chat.room.type} · {chat.onlineUserIds.size} online · max {chat.room.maxMembers}
                    </p>
                  </div>
                  <div className="chat-head-actions">
                    <button type="button" onClick={copyRoomId}>
                      Copy id
                    </button>
                    <button className="danger" type="button" onClick={onLeave}>
                      Leave
                    </button>
                  </div>
                </div>
                <MessageList />
                <Composer key={chat.room.id} disabled={chat.wsState !== "open"} />
              </>
            ) : (
              <div className="empty-main">
                <h2>{chat.error ? "Can't open room" : "Loading…"}</h2>
              </div>
            )
          ) : (
            <div className="empty-main">
              <h2>Select a room</h2>
              <p className="muted">
                Create a room or paste a room id to join. Open a second tab, register another user, and
                join the same id to chat live.
              </p>
            </div>
          )}
        </section>
        {roomId ? (
          <aside className="members-col">
            <MemberList />
          </aside>
        ) : null}
      </div>
    </div>
  );
}
