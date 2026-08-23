import { request, upload } from "./client.js";

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

export function searchMessages(token, roomId, q, size = 20) {
  const params = new URLSearchParams({ q, size: String(size) });
  return request(token, "GET", `/api/rooms/${roomId}/messages/search?${params}`);
}

export function uploadAttachment(token, roomId, file, caption) {
  const form = new FormData();
  form.append("file", file);
  if (caption) {
    form.append("caption", caption);
  }
  return upload(token, `/api/rooms/${roomId}/attachments`, form);
}
