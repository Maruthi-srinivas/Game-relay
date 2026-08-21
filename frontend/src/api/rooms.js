import { request } from "./client.js";

export function listRooms(token) {
  return request(token, "GET", "/api/rooms");
}

export function createRoom(token, body) {
  return request(token, "POST", "/api/rooms", body);
}

export function getRoom(token, roomId) {
  return request(token, "GET", `/api/rooms/${roomId}`);
}

export function joinRoom(token, roomId) {
  return request(token, "POST", `/api/rooms/${roomId}/join`);
}

export function leaveRoom(token, roomId) {
  return request(token, "POST", `/api/rooms/${roomId}/leave`);
}

export function listMembers(token, roomId) {
  return request(token, "GET", `/api/rooms/${roomId}/members`);
}
