import { logTraffic, redactForLog } from "./trafficLog.js";

let unauthorizedHandler = null;

export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = fn;
}

export class ApiError extends Error {
  constructor(status, code, message, body) {
    super(message || "Request failed");
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

export async function request(token, method, path, body) {
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
  const requestBody = body === undefined ? null : redactForLog(body);

  let res;
  try {
    res = await fetch(path, init);
  } catch (err) {
    logTraffic({
      kind: "rest",
      method,
      path,
      request: requestBody,
      response: err.message || "Network error",
      status: 0,
      ok: false,
      ms: Date.now() - started,
    });
    throw new ApiError(0, "NETWORK_ERROR", err.message || "Network error");
  }

  const text = await res.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = text;
    }
  }

  logTraffic({
    kind: "rest",
    method,
    path,
    request: requestBody,
    response: redactForLog(payload),
    status: res.status,
    ok: res.ok,
    ms: Date.now() - started,
  });

  if (res.status === 401) {
    unauthorizedHandler?.();
  }

  if (!res.ok) {
    const code = payload?.code || "ERROR";
    const message = payload?.message || res.statusText || "Request failed";
    throw new ApiError(res.status, code, message, payload);
  }

  return payload;
}
