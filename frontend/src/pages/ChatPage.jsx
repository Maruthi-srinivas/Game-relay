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
import ReportInbox from "../components/ReportInbox.jsx";
import { roomTypeLabel } from "../components/roomTypes.js";

export default function ChatPage() {
  const { roomId } = useParams();
  const navigate = useNavigate();
  const chat = useChat();
  const [createOpen, setCreateOpen] = useState(false);
  const [joinOpen, setJoinOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState([]);

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
      if (chat.room.type === "PARTY" || chat.room.type === "TEAM" || chat.room.type === "GAME_ROOM") {
        const invite = await chat.invite(chat.room.id);
        await navigator.clipboard.writeText(invite.code);
        return;
      }
      await navigator.clipboard.writeText(chat.room.id);
    } catch {
      window.prompt("Invite", chat.room.id);
    }
  }

  async function onSearch(e) {
    e.preventDefault();
    if (!query.trim()) {
      setHits([]);
      return;
    }
    const rows = await chat.searchChat(query);
    setHits(Array.isArray(rows) ? rows : []);
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
                    <form className="search-form" onSubmit={onSearch}>
                      <input
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        placeholder="Search messages"
                      />
                    </form>
                    {hits.length ? (
                      <div className="search-hits">
                        {hits.map((item) => (
                          <div key={item.messageId} className="search-hit">
                            {item.content}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </div>
                    <div className="chat-head-actions">
                    {chat.room.type !== "GLOBAL" && chat.room.type !== "PRIVATE" ? (
                      <button className="ghost" type="button" onClick={copyInvite}>
                        Invite
                      </button>
                    ) : null}
                    {chat.room.type !== "GLOBAL" ? (
                      <button className="danger" type="button" onClick={onLeave}>
                        Leave
                      </button>
                    ) : null}
                    </div>
                </div>
                <MessageList />
                <Composer key={chat.room.id} disabled={chat.wsState !== "open" || chat.muted} />
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
            <ReportInbox />
          </aside>
        ) : null}
      </div>
      <CreateRoom open={createOpen} onClose={() => setCreateOpen(false)} onCreated={onCreated} />
      <JoinRoom open={joinOpen} onClose={() => setJoinOpen(false)} onJoined={onJoined} />
    </div>
  );
}
