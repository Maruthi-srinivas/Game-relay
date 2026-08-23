import { request } from "./client.js";

export function listRooms(token) {
  return request(token, "GET", "/api/rooms");
}

export function createRoom(token, body) {
  return request(token, "POST", "/api/rooms", body);
}

export function createPrivateRoom(token, userId) {
  return request(token, "POST", "/api/rooms/private", { userId });
}

export function getRoom(token, roomId) {
  return request(token, "GET", `/api/rooms/${roomId}`);
}

export function joinRoom(token, roomId, inviteCode) {
  return request(token, "POST", `/api/rooms/${roomId}/join`, inviteCode ? { inviteCode } : {});
}

export function joinByCode(token, inviteCode) {
  return request(token, "POST", "/api/rooms/join", { inviteCode });
}

export function leaveRoom(token, roomId) {
  return request(token, "POST", `/api/rooms/${roomId}/leave`);
}

export function listMembers(token, roomId) {
  return request(token, "GET", `/api/rooms/${roomId}/members`);
}

export function createInvite(token, roomId) {
  return request(token, "POST", `/api/rooms/${roomId}/invites`);
}

export function kickMember(token, roomId, userId) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/kick`);
}

export function muteMember(token, roomId, userId) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/mute`);
}

export function unmuteMember(token, roomId, userId) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/unmute`);
}

export function reportMember(token, roomId, body) {
  return request(token, "POST", `/api/rooms/${roomId}/reports`, body);
}

export function banMember(token, roomId, userId, reason) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/ban`, reason ? { reason } : {});
}

export function unbanMember(token, roomId, userId) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/unban`);
}

export function promoteMember(token, roomId, userId) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/promote`);
}

export function demoteMember(token, roomId, userId) {
  return request(token, "POST", `/api/rooms/${roomId}/members/${userId}/demote`);
}

export function listReports(token, roomId) {
  return request(token, "GET", `/api/rooms/${roomId}/reports`);
}

export function resolveReport(token, roomId, reportId) {
  return request(token, "POST", `/api/rooms/${roomId}/reports/${reportId}/resolve`);
}
