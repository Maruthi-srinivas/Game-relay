import { request } from "./client.js";

export function getHealth() {
  return request(null, "GET", "/actuator/health");
}
