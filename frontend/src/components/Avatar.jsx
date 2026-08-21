function hueFrom(seed) {
  const text = String(seed || "?");
  let hash = 0;
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash * 33 + text.charCodeAt(i)) >>> 0;
  }
  return hash % 360;
}

function initials(name) {
  const parts = String(name || "?").trim().split(/\s+/).filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return (parts[0] || "?").slice(0, 2).toUpperCase();
}

export default function Avatar({ name, seed, size = "md", online }) {
  const hue = hueFrom(seed || name);
  const style = {
    background: `hsl(${hue} 42% 28%)`,
    color: `hsl(${hue} 80% 88%)`,
  };
  return (
    <span className={`avatar avatar-${size} ${online ? "online" : ""}`} style={style} aria-hidden="true">
      {initials(name)}
    </span>
  );
}
