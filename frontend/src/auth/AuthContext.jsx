import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { login as loginApi, register as registerApi } from "../api/auth.js";
import { setUnauthorizedHandler } from "../api/client.js";

const STORAGE_KEY = "gamechat.session";

const AuthContext = createContext(null);

function readSession() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw);
    if (!parsed?.token || !parsed?.userId || !parsed?.username) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState(readSession);

  const persist = useCallback((next) => {
    setSession(next);
    if (next) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  const logout = useCallback(() => {
    persist(null);
  }, [persist]);

  useEffect(() => {
    setUnauthorizedHandler(logout);
    return () => setUnauthorizedHandler(null);
  }, [logout]);

  const login = useCallback(async (username, password) => {
    const data = await loginApi({ username, password });
    persist({ token: data.token, userId: data.userId, username: data.username });
    return data;
  }, [persist]);

  const register = useCallback(async (username, email, password) => {
    const data = await registerApi({ username, email, password });
    persist({ token: data.token, userId: data.userId, username: data.username });
    return data;
  }, [persist]);

  const value = useMemo(
    () => ({ session, login, register, logout }),
    [session, login, register, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}
