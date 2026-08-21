import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";
import { ApiError } from "../api/client.js";
import { TrafficButton } from "../components/TrafficLog.jsx";

const USERNAME_RE = /^[A-Za-z0-9_]+$/;

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSubmit(e) {
    e.preventDefault();
    setError("");
    const name = username.trim();
    if (name.length < 3 || name.length > 64 || !USERNAME_RE.test(name)) {
      setError("Username must be 3–64 letters, digits, or underscores.");
      return;
    }
    if (!email.trim() || email.length > 255) {
      setError("A valid email is required.");
      return;
    }
    if (password.length < 8 || password.length > 72) {
      setError("Password must be 8–72 characters.");
      return;
    }
    setBusy(true);
    try {
      await register(name, email.trim(), password);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed.");
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
        <h1>Create account</h1>
        <p className="muted">Pick a callsign. You can hop into any lobby with an invite.</p>
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
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
          />
        </label>
        <label className="field">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
          />
        </label>
        <button className="primary" type="submit" disabled={busy}>
          {busy ? "Creating…" : "Deploy"}
        </button>
        <p className="muted">
          Already registered? <Link to="/login">Sign in</Link>
        </p>
      </form>
    </div>
  );
}
