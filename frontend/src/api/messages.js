import { request } from "./client.js";

export function listMessages(token, roomId, { page = 0, size = 50, afterSequence } = {}) {
  const params = new URLSearchParams();
  if (afterSequence !== undefined && afterSequence !== null) {
    params.set("afterSequence", String(afterSequence));
    params.set("size", String(size));
  } else {
    params.set("page", String(page));
    params.set("size", String(size));
  }
  return request(token, "GET", `/api/rooms/${roomId}/messages?${params}`);
}
