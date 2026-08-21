const MAX_ENTRIES = 200;

let nextId = 1;
let entries = [];
const listeners = new Set();

function emit() {
  for (const listener of listeners) {
    listener(entries);
  }
}

export function redactForLog(value) {
  if (value == null) {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(redactForLog);
  }
  if (typeof value === "object") {
    const out = {};
    for (const [key, nested] of Object.entries(value)) {
      if (/^(token|password|authorization)$/i.test(key)) {
        out[key] = "***";
      } else {
        out[key] = redactForLog(nested);
      }
    }
    return out;
  }
  return value;
}

export function logTraffic(entry) {
  const item = {
    id: nextId,
    at: new Date().toISOString(),
    ...entry,
  };
  nextId += 1;
  entries = [item, ...entries].slice(0, MAX_ENTRIES);
  emit();
}

export function getTraffic() {
  return entries;
}

export function clearTraffic() {
  entries = [];
  emit();
}

export function subscribeTraffic(listener) {
  listeners.add(listener);
  listener(entries);
  return () => listeners.delete(listener);
}
