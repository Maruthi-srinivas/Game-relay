import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { login as loginApi, logout as logoutApi, refresh as refreshApi, register as registerApi } from "../api/auth.js";
import { setRefreshHandler, setUnauthorizedHandler } from "../api/client.js";

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

function toSession(data) {
  return {
    token: data.accessToken || data.token,
    userId: data.userId,
    username: data.username,
    expiresIn: data.expiresIn,
    expiresAt: Date.now() + (data.expiresIn || 900000) - 15000,
  };
}

export function AuthProvider({ children }) {
  const [session, setSession] = useState(readSession);
  const sessionRef = useRef(session);
  sessionRef.current = session;

  const persist = useCallback((next) => {
    setSession(next);
    sessionRef.current = next;
    if (next) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  }, []);

  const logout = useCallback(async () => {
    const current = sessionRef.current;
    try {
      if (current?.token) {
        await logoutApi(current.token);
      }
    } catch {
      // still clear locally
    }
    persist(null);
  }, [persist]);

  const applyAuth = useCallback((data) => {
    const next = toSession(data);
    persist(next);
    return next;
  }, [persist]);

  const silentRefresh = useCallback(async () => {
    const data = await refreshApi();
    const next = applyAuth(data);
    return next.token;
  }, [applyAuth]);

  useEffect(() => {
    setUnauthorizedHandler(() => persist(null));
    setRefreshHandler(silentRefresh);
    return () => {
      setUnauthorizedHandler(null);
      setRefreshHandler(null);
    };
  }, [persist, silentRefresh]);

  useEffect(() => {
    if (!session?.expiresAt) {
      return undefined;
    }
    const delay = Math.max(session.expiresAt - Date.now(), 1000);
    const timer = setTimeout(() => {
      silentRefresh().catch(() => persist(null));
    }, delay);
    return () => clearTimeout(timer);
  }, [session, silentRefresh, persist]);

  const login = useCallback(async (username, password) => {
    const data = await loginApi({ username, password });
    return applyAuth(data);
  }, [applyAuth]);

  const register = useCallback(async (username, email, password) => {
    const data = await registerApi({ username, email, password });
    return applyAuth(data);
  }, [applyAuth]);

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
