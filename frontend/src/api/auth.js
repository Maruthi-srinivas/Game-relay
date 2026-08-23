import { request } from "./client.js";

export function register(body) {
  return request(null, "POST", "/api/auth/register", body, { credentials: true });
}

export function login(body) {
  return request(null, "POST", "/api/auth/login", body, { credentials: true });
}

export function refresh() {
  return request(null, "POST", "/api/auth/refresh", {}, { credentials: true, skipRefresh: true });
}

export function logout(token) {
  return request(token, "POST", "/api/auth/logout", {}, { credentials: true, skipRefresh: true });
}
