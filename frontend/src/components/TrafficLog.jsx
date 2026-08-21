import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { clearTraffic, getTraffic, subscribeTraffic } from "../api/trafficLog.js";

const TrafficLogContext = createContext(null);

function formatTime(iso) {
  try {
    const date = new Date(iso);
    return date.toLocaleTimeString([], { hour12: false }) + "." + String(date.getMilliseconds()).padStart(3, "0");
  } catch {
    return iso;
  }
}

function pretty(value) {
  if (value === undefined) {
    return "";
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function restSummary(entry) {
  return `${entry.method} ${entry.path}`;
}

function wsSummary(entry) {
  if (entry.dir === "in" || entry.dir === "out") {
    const type = entry.payload?.type;
    return `${entry.dir} ${type || "frame"}`;
  }
  return entry.dir;
}

export function TrafficLogProvider({ children }) {
  const [entries, setEntries] = useState(getTraffic);
  const [open, setOpen] = useState(false);

  useEffect(() => subscribeTraffic(setEntries), []);

  const value = useMemo(
    () => ({
      entries,
      open,
      openLog: () => setOpen(true),
      closeLog: () => setOpen(false),
      clear: clearTraffic,
    }),
    [entries, open]
  );

  return (
    <TrafficLogContext.Provider value={value}>
      {children}
      {open ? <TrafficModal /> : null}
    </TrafficLogContext.Provider>
  );
}

export function useTrafficLog() {
  const ctx = useContext(TrafficLogContext);
  if (!ctx) {
    throw new Error("useTrafficLog must be used within TrafficLogProvider");
  }
  return ctx;
}

export function TrafficButton() {
  const { openLog, entries } = useTrafficLog();
  return (
    <button type="button" onClick={openLog} title="API traffic">
      Traffic{entries.length ? ` (${entries.length})` : ""}
    </button>
  );
}

function TrafficModal() {
  const { entries, closeLog, clear } = useTrafficLog();
  const [filter, setFilter] = useState("all");
  const [expanded, setExpanded] = useState(null);

  useEffect(() => {
    function onKey(e) {
      if (e.key === "Escape") {
        closeLog();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [closeLog]);

  const visible = entries.filter((entry) => filter === "all" || entry.kind === filter);

  return (
    <div className="traffic-backdrop" onClick={closeLog} role="presentation">
      <div
        className="traffic-modal"
        role="dialog"
        aria-labelledby="traffic-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="traffic-head">
          <h2 id="traffic-title">API traffic</h2>
          <div className="traffic-actions">
            <div className="traffic-filters">
              {["all", "rest", "ws"].map((value) => (
                <button
                  key={value}
                  type="button"
                  className={filter === value ? "primary" : ""}
                  onClick={() => setFilter(value)}
                >
                  {value.toUpperCase()}
                </button>
              ))}
            </div>
            <button type="button" onClick={clear}>
              Clear
            </button>
            <button type="button" onClick={closeLog}>
              Close
            </button>
          </div>
        </div>
        <div className="traffic-list">
          {visible.length === 0 ? (
            <div className="empty">No traffic yet</div>
          ) : (
            visible.map((entry) => {
              const isOpen = expanded === entry.id;
              const ok = entry.kind === "ws" ? entry.dir !== "error" : entry.ok;
              return (
                <article
                  key={entry.id}
                  className={`traffic-item ${entry.kind} ${ok ? "ok" : "err"}`}
                >
                  <button
                    type="button"
                    className="traffic-row"
                    onClick={() => setExpanded(isOpen ? null : entry.id)}
                  >
                    <span className="traffic-time">{formatTime(entry.at)}</span>
                    <span className="traffic-kind">{entry.kind.toUpperCase()}</span>
                    <span className="traffic-summary">
                      {entry.kind === "rest" ? restSummary(entry) : wsSummary(entry)}
                    </span>
                    {entry.kind === "rest" ? (
                      <span className="traffic-status">
                        {entry.status} · {entry.ms}ms
                      </span>
                    ) : (
                      <span className="traffic-status">{entry.dir}</span>
                    )}
                  </button>
                  {isOpen ? (
                    <pre className="traffic-detail">
                      {entry.kind === "rest"
                        ? pretty({ request: entry.request, response: entry.response })
                        : pretty(entry.payload)}
                    </pre>
                  ) : null}
                </article>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
