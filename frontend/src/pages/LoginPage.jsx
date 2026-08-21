import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";
import { ApiError } from "../api/client.js";
import { TrafficButton } from "../components/TrafficLog.jsx";

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSubmit(e) {
    e.preventDefault();
    setError("");
    if (!username.trim() || !password) {
      setError("Username and password are required.");
      return;
    }
    setBusy(true);
    try {
      await login(username.trim(), password);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-screen">
      <div className="auth-traffic">
        <TrafficButton />
      </div>
      <form className="auth-card" onSubmit={onSubmit}>
        <div className="wordmark">
          ARE<span>NA</span>
        </div>
        <h1>Sign in</h1>
        <p className="muted">Enter your callsign to drop into voice-of-text lobbies.</p>
        {error ? <div className="alert">{error}</div> : null}
        <label className="field">
          Username
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
          />
        </label>
        <label className="field">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Connecting…" : "Enter"}
        </button>
        <p className="muted">
          New here? <Link to="/register">Create an account</Link>
        </p>
      </form>
    </div>
  );
}
