import { request } from "./client.js";

export function register(body) {
  return request(null, "POST", "/api/auth/register", body);
}

export function login(body) {
  return request(null, "POST", "/api/auth/login", body);
}
