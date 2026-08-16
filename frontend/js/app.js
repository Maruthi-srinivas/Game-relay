import { mountPane } from "./pane.js";

const panes = document.getElementById("panes");

const left = document.createElement("div");
const right = document.createElement("div");
panes.append(left, right);

mountPane(left, {
  title: "User A",
  defaults: { username: "alice", email: "alice@example.com", password: "password123" },
});

mountPane(right, {
  title: "User B",
  defaults: { username: "bob", email: "bob@example.com", password: "password123" },
});

const health = document.getElementById("api-health");
try {
  const res = await fetch("/actuator/health");
  const body = await res.json();
  const up = res.ok && body.status === "UP";
  health.textContent = up ? "API UP" : `API ${body.status || res.status}`;
  health.className = `badge ${up ? "up" : "down"}`;
} catch {
  health.textContent = "API down";
  health.className = "badge down";
}
