export async function request(token, method, path, body, onLog) {
  const headers = { Accept: "application/json" };
  if (body !== undefined && body !== null) {
    headers["Content-Type"] = "application/json";
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const init = { method, headers };
  if (body !== undefined && body !== null) {
    init.body = JSON.stringify(body);
  }

  const started = Date.now();
  try {
    const res = await fetch(path, init);
    const text = await res.text();
    let payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch {
        payload = text;
      }
    }
    onLog?.({
      kind: "rest",
      method,
      path,
      status: res.status,
      ok: res.ok,
      request: body ?? null,
      response: payload,
      ms: Date.now() - started,
    });
    return { ok: res.ok, status: res.status, body: payload };
  } catch (err) {
    onLog?.({
      kind: "rest",
      method,
      path,
      ok: false,
      status: 0,
      request: body ?? null,
      response: String(err),
      ms: Date.now() - started,
    });
    throw err;
  }
}
