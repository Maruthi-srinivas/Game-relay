import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useChat } from "../chat/ChatContext.jsx";
import UserDock from "../components/Header.jsx";
import RoomList from "../components/RoomList.jsx";
import CreateRoom from "../components/CreateRoom.jsx";
import JoinRoom from "../components/JoinRoom.jsx";
import MessageList from "../components/MessageList.jsx";
import Composer from "../components/Composer.jsx";
import MemberList from "../components/MemberList.jsx";
import { roomTypeLabel } from "../components/roomTypes.js";

export default function ChatPage() {
  const { roomId } = useParams();
  const navigate = useNavigate();
  const chat = useChat();
  const [createOpen, setCreateOpen] = useState(false);
  const [joinOpen, setJoinOpen] = useState(false);

  useEffect(() => {
    chat.selectRoom(roomId || null);
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

  async function copyInvite() {
    if (!chat.room?.id) {
      return;
    }
    try {
      await navigator.clipboard.writeText(chat.room.id);
    } catch {
      window.prompt("Invite id", chat.room.id);
    }
  }

  return (
    <div className="app-shell">
      {chat.error ? (
        <div className="banner">
          <span>{chat.error}</span>
          <button type="button" className="ghost" onClick={chat.clearError}>
            Dismiss
          </button>
        </div>
      ) : null}
      <div className="app-body">
        <aside className="sidebar">
          <div className="brand">
            <h1>
              ARE<span>NA</span>
            </h1>
            <span className="tag">Lobbies</span>
          </div>
          <div className="sidebar-actions">
            <button className="primary" type="button" onClick={() => setCreateOpen(true)}>
              New lobby
            </button>
            <button className="ghost" type="button" onClick={() => setJoinOpen(true)}>
              Join
            </button>
          </div>
          <RoomList selectedId={roomId} />
          <UserDock />
        </aside>
        <section className="chat-main">
          {roomId ? (
            chat.room ? (
              <>
                <div className="chat-head">
                  <div>
                    <h2>{chat.room.name}</h2>
                    <div className="chat-head-meta">
                      <span className={`type-chip ${chat.room.type}`}>{roomTypeLabel(chat.room.type)}</span>
                      <span>{chat.onlineUserIds.size} online</span>
                      <span>cap {chat.room.maxMembers}</span>
                    </div>
                  </div>
                  <div className="chat-head-actions">
                    <button className="ghost" type="button" onClick={copyInvite}>
                      Invite
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
                <h2>{chat.error ? "Can't open lobby" : "Loading…"}</h2>
              </div>
            )
          ) : (
            <div className="empty-main">
              <h2>Pick a lobby</h2>
              <p className="muted">Create one or join with an invite to start chatting.</p>
            </div>
          )}
        </section>
        {roomId ? (
          <aside className="members-col">
            <MemberList />
          </aside>
        ) : null}
      </div>
      <CreateRoom open={createOpen} onClose={() => setCreateOpen(false)} onCreated={onCreated} />
      <JoinRoom open={joinOpen} onClose={() => setJoinOpen(false)} onJoined={onJoined} />
    </div>
  );
}
