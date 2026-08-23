export const ROOM_TYPES = ["GAME_ROOM", "TEAM", "PARTY"];
export const ALL_ROOM_TYPES = ["GAME_ROOM", "GLOBAL", "TEAM", "PARTY", "PRIVATE"];

const LABELS = {
  GAME_ROOM: "Game",
  GLOBAL: "Global",
  TEAM: "Team",
  PARTY: "Party",
  PRIVATE: "Private",
};

const GLYPHS = {
  GAME_ROOM: "#",
  GLOBAL: "◎",
  TEAM: "▣",
  PARTY: "✦",
  PRIVATE: "◈",
};

export function roomTypeLabel(type) {
  return LABELS[type] || type || "Lobby";
}

export function roomTypeGlyph(type) {
  return GLYPHS[type] || "#";
}

export function roomSlug(name) {
  return String(name || "lobby")
    .trim()
    .toLowerCase()
    .replace(/\s+/g, "-")
    .replace(/[^a-z0-9-]/g, "")
    .slice(0, 32) || "lobby";
}
