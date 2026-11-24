/**
 * Bluefin cloud TTS proxy.
 *
 * Security model:
 *  - FISH_API_KEY lives only in Worker secrets; no response, log or token
 *    ever contains it. Clients authenticate with short-lived HMAC device
 *    tokens, not with the vendor key.
 *  - The upstream host and model are constants (see config.ts / fish.ts).
 *    Clients cannot choose a vendor, model, URL or upstream header.
 *  - Every TTS call passes: token verification → device registry (revocation)
 *    → per-device rate/quota/concurrency → global concurrency lease.
 *  - Voice mappings are scoped: global (owner-provisioned built-ins) or
 *    device-private. One device can never resolve another device's voice.
 */

import type { Env } from "./config.ts";
import { DEVICE_ID_RE, FISH_MODEL, FISH_MODEL_ID_RE, VOICE_ID_RE, limits, upstreamUrl } from "./config.ts";
import { bearer, issueToken, shortHash, verifyToken } from "./auth.ts";
import type { TokenClaims } from "./auth.ts";
import { composeText, normalizeEmotion, stripDirectives } from "./emotion.ts";
import { BUILTIN_VOICES } from "./builtin-voices.ts";
import { ERRORS, errorResponse, json } from "./errors.ts";
import type { SafeError } from "./errors.ts";
import { fishCreateModel, fishDeleteModel, fishGetModel, fishTts } from "./fish.ts";
import { Gate, TTS_LEASE_MS } from "./gate.ts";

export { Gate };

interface GateResult {
  ok: boolean;
  error?: string;
  [key: string]: unknown;
}

async function gate(env: Env, instance: string, path: string, body: Record<string, unknown>): Promise<GateResult> {
  const stub = env.GATE.get(env.GATE.idFromName(instance));
  const response = await stub.fetch(`https://gate${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ...body, limits: limits(env) }),
  });
  return (await response.json()) as GateResult;
}

function log(route: string, status: number, deviceHash: string, startedMs: number): void {
  // No text, tokens, keys, audio or upstream bodies are ever logged.
  console.log(
    JSON.stringify({ route, status, dev: deviceHash || "-", ms: Date.now() - startedMs }),
  );
}

async function requireDevice(request: Request, env: Env): Promise<TokenClaims | Response> {
  const secret = env.TOKEN_SIGNING_KEY;
  if (!secret) return errorResponse(ERRORS.serviceConfig);
  const claims = await verifyToken(secret, bearer(request), Date.now());
  if (!claims || !DEVICE_ID_RE.test(claims.deviceId)) return errorResponse(ERRORS.unauthorized);
  return claims;
}

function deviceError(result: GateResult): Response {
  switch (result.error) {
    case "revoked":
      return errorResponse(ERRORS.revoked);
    case "unknown_device":
      return errorResponse(ERRORS.unauthorized);
    case "device_busy":
      return errorResponse(ERRORS.deviceBusy, 1);
    case "rate_limited":
      return errorResponse(ERRORS.rateLimited, Number(result.retryAfterSeconds ?? 5));
    case "quota_exceeded":
      return errorResponse(ERRORS.quotaExceeded);
    case "global_busy":
      return errorResponse(ERRORS.busy, 2);
    case "device_cap":
      return errorResponse(ERRORS.deviceCapReached);
    case "register_limited":
      return errorResponse(ERRORS.registerLimited);
    case "voice_cap":
      return errorResponse(ERRORS.voiceCapReached);
    case "voice_not_found":
      return errorResponse(ERRORS.voiceNotFound);
    case "voice_removed":
      return errorResponse(ERRORS.voiceInvalid);
    default:
      return errorResponse(ERRORS.upstreamError);
  }
}

function upstreamFailure(failure: string | undefined): SafeError {
  switch (failure) {
    case "payment":
      // Free model refused (balance/permission). Stop; NEVER fall back to a
      // paid model — that would spend the owner's money without consent.
      return ERRORS.freeModelUnavailable;
    case "auth":
      return ERRORS.serviceConfig;
    case "bad_request":
      return ERRORS.voiceInvalid;
    case "not_found":
      return ERRORS.voiceNotFound;
    case "rate":
      return ERRORS.upstreamBusy;
    case "timeout":
      return ERRORS.upstreamTimeout;
    case "network":
      return ERRORS.upstreamNetwork;
    default:
      return ERRORS.upstreamError;
  }
}

/**
 * Resolves an internal voice id to upstream parameters.
 *  - persistent mapping (owner-provisioned or linked Fish model id) → reference_id
 *  - device-uploaded inline voice → reference audio from KV
 *  - bundled timbres → compile-time embedded reference audio (works at $0,
 *    no model creation, no storage, no setup for any device)
 */
type ResolvedVoice =
  | { kind: "persistent"; referenceId: string }
  | { kind: "inline"; audioB64: string; text: string };

async function resolveVoiceForTts(env: Env, voiceId: string, deviceId: string): Promise<ResolvedVoice | null> {
  const mapping = await gate(env, "registry", "/voice/resolve", { voiceId, deviceId });
  if (mapping.ok) {
    if (mapping.state !== "trained") return null;
    const fishModelId = String(mapping.fishModelId ?? "");
    if (fishModelId) return { kind: "persistent", referenceId: fishModelId };
    const key = String(mapping.audioKey ?? `audio:${deviceId}:${voiceId}`);
    const audio = await env.VOICES.get(key, "text");
    if (!audio) return null;
    return { kind: "inline", audioB64: audio, text: String(mapping.referenceText ?? "") };
  }
  const builtin = BUILTIN_VOICES[voiceId];
  if (builtin) return { kind: "inline", audioB64: builtin.audioB64, text: builtin.text };
  return null;
}

async function readJson(request: Request, maxBytes: number): Promise<Record<string, unknown> | null> {
  const length = Number(request.headers.get("Content-Length") ?? "0");
  if (length > maxBytes) return null;
  try {
    const text = await request.text();
    if (text.length > maxBytes) return null;
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

// ---- routes ---------------------------------------------------------------

async function handleRegister(request: Request, env: Env): Promise<Response> {
  if (!env.TOKEN_SIGNING_KEY) return errorResponse(ERRORS.serviceConfig);
  const body = await readJson(request, 4096);
  if (!body) return errorResponse(ERRORS.badRequest);
  const deviceId = String(body.deviceId ?? "").toLowerCase();
  if (!DEVICE_ID_RE.test(deviceId)) return errorResponse(ERRORS.badRequest);
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const result = await gate(env, "registry", "/register", {
    deviceId,
    ip,
    name: typeof body.name === "string" ? body.name : "",
  });
  if (!result.ok) return deviceError(result);
  const { token, expiresAt } = await issueToken(
    env.TOKEN_SIGNING_KEY!,
    deviceId,
    limits(env).tokenTtlSeconds,
    Date.now(),
  );
  return json({ token, expiresAt, model: FISH_MODEL });
}

async function handleRefresh(request: Request, env: Env): Promise<Response> {
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  const touched = await gate(env, "registry", "/touch", { deviceId: claims.deviceId });
  if (!touched.ok) return deviceError(touched);
  const { token, expiresAt } = await issueToken(
    env.TOKEN_SIGNING_KEY!,
    claims.deviceId,
    limits(env).tokenTtlSeconds,
    Date.now(),
  );
  return json({ token, expiresAt });
}

function streamAudio(
  body: ReadableStream<Uint8Array>,
  timeoutMs: number,
  signal: AbortSignal,
  finish: (success: boolean) => Promise<void>,
  ctx: ExecutionContext,
): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  let resolveOutcome!: (success: boolean) => void;
  const outcome = new Promise<boolean>((resolve) => { resolveOutcome = resolve; });
  const completion = outcome.then(finish);
  // Keep cleanup registered while the response is streaming so disconnects
  // can settle the lease even when they end the request.
  ctx.waitUntil(completion);
  let settled = false;
  let timer: ReturnType<typeof setTimeout>;
  let onAbort: () => void;
  const settle = (success: boolean) => {
    if (!settled) {
      settled = true;
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
      resolveOutcome(success);
    }
    return completion;
  };
  return new ReadableStream<Uint8Array>({
    start(controller) {
      const stop = (error: Error) => {
        if (settled) return;
        controller.error(error);
        void reader.cancel(error).catch(() => {});
        void settle(false);
      };
      onAbort = () => stop(new Error("TTS client disconnected"));
      timer = setTimeout(() => stop(new Error("TTS stream timed out")), timeoutMs);
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    },
    async pull(controller) {
      try {
        const { done, value } = await reader.read();
        if (settled) return;
        if (!done) {
          controller.enqueue(value);
          return;
        }
        await settle(true);
        controller.close();
      } catch (error) {
        if (settled) return;
        controller.error(error);
        await settle(false);
      }
    },
    async cancel(reason) {
      await Promise.all([reader.cancel(reason).catch(() => {}), settle(false)]);
    },
  });
}

async function handleTts(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const started = Date.now();
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  const deviceId = claims.deviceId;
  const devHash = await shortHash(deviceId);
  const cfg = limits(env);
  if (!env.FISH_API_KEY) return errorResponse(ERRORS.serviceConfig);

  const body = await readJson(request, 64 * 1024);
  if (!body) return errorResponse(ERRORS.badRequest);
  const voiceId = String(body.voiceId ?? "");
  if (!VOICE_ID_RE.test(voiceId)) return errorResponse(ERRORS.badRequest);
  const rawText = typeof body.text === "string" ? body.text : "";
  const text = stripDirectives(rawText);
  const chars = Array.from(text).length;
  if (chars === 0) return errorResponse(ERRORS.badRequest);
  if (chars > cfg.maxTextChars) return errorResponse(ERRORS.textTooLong);
  const speedRaw = Number(body.speed ?? 1.0);
  const speed = Number.isFinite(speedRaw) ? Math.min(2.0, Math.max(0.5, speedRaw)) : 1.0;
  const emotion = normalizeEmotion(body.emotion);

  const voice = await resolveVoiceForTts(env, voiceId, deviceId);
  if (!voice) return errorResponse(ERRORS.voiceNotFound);

  // Registry first: revocation must outrank rate/quota responses, and the
  // global lease is released again if the per-device guard then declines.
  const acquired = await gate(env, "registry", "/acquire", { deviceId });
  if (!acquired.ok) return deviceError(acquired);
  const leaseId = String(acquired.leaseId ?? "");
  const expires = Number(acquired.expires ?? (Date.now() + TTS_LEASE_MS));
  const deviceGate = `device:${deviceId}`;

  const deviceCheck = gate(env, deviceGate, "/check", { leaseId, expires });
  const releaseLeases = async (success: boolean) => {
    // A lost /check reply may still have stored the lease. Wait for admission
    // to settle, then let the Durable Object match the owner on cleanup.
    const checked = await deviceCheck.catch(() => undefined);
    const release = gate(env, "registry", "/release", { leaseId });
    const deviceLeaseMayBeHeld = !checked || checked.ok || checked.error === "internal";
    const commit = deviceLeaseMayBeHeld ? gate(env, deviceGate, "/commit", { leaseId, chars, success }) : undefined;
    await Promise.allSettled([release, commit]);
  };
  let cleanup: Promise<void> | undefined;
  const finish = (success: boolean) => cleanup ??= releaseLeases(success);
  const onAbort = () => ctx.waitUntil(finish(false));
  request.signal.addEventListener("abort", onAbort, { once: true });

  let streaming = false;
  try {
    const checked = await deviceCheck;
    if (!checked.ok) return deviceError(checked);
    if (request.signal.aborted) return errorResponse(ERRORS.upstreamTimeout);

    const remainingMs = expires - Date.now();
    if (remainingMs <= 0) return errorResponse(ERRORS.upstreamTimeout);
    const upstream = await fishTts(
      env,
      {
        text: composeText(text, emotion),
        speed,
        ...(voice.kind === "persistent"
          ? { referenceId: voice.referenceId }
          : { references: [{ audio: voice.audioB64, text: voice.text }] }),
      },
      Math.min(cfg.upstreamTimeoutMs, remainingMs),
      request.signal,
    );
    if (!upstream.ok || !upstream.body) {
      log("tts", upstream.status, devHash, started);
      return errorResponse(upstreamFailure(upstream.failure), upstream.failure === "rate" ? 2 : undefined);
    }

    const streamTimeoutMs = Math.min(90_000, expires - Date.now());
    if (streamTimeoutMs <= 0) {
      await upstream.body.cancel().catch(() => {});
      return errorResponse(ERRORS.upstreamTimeout);
    }

    const audio = streamAudio(upstream.body, streamTimeoutMs, request.signal, finish, ctx);
    streaming = true;
    log("tts", 200, devHash, started);
    return new Response(audio, {
      status: 200,
      headers: {
        "Content-Type": "audio/mpeg",
        "Cache-Control": "no-store",
        "X-Tts-Model": FISH_MODEL,
      },
    });
  } finally {
    request.signal.removeEventListener("abort", onAbort);
    if (!streaming) {
      const completion = finish(false);
      ctx.waitUntil(completion);
      await completion;
    }
  }
}

/**
 * Cloud recovery probe for devices in LOCAL_FALLBACK. Runs one minimal
 * free-model synthesis server-side (never a paid model, never client text),
 * rate-limited separately from TTS so probing cannot eat the daily quota.
 */
async function handleProbe(request: Request, env: Env): Promise<Response> {
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  if (!env.FISH_API_KEY) return errorResponse(ERRORS.serviceConfig);
  const cfg = limits(env);
  const allowed = await gate(env, `device:${claims.deviceId}`, "/probe-check", {});
  if (!allowed.ok) return deviceError(allowed);
  const voice = await resolveVoiceForTts(env, "timbre1", claims.deviceId);
  if (!voice) return errorResponse(ERRORS.voiceNotFound);
  const acquired = await gate(env, "registry", "/acquire", { deviceId: claims.deviceId });
  if (!acquired.ok) return deviceError(acquired);
  const upstream = await fishTts(
    env,
    {
      text: "嗯",
      speed: 1.0,
      ...(voice.kind === "persistent"
        ? { referenceId: voice.referenceId }
        : { references: [{ audio: voice.audioB64, text: voice.text }] }),
    },
    cfg.upstreamTimeoutMs,
  );
  await gate(env, "registry", "/release", { leaseId: String(acquired.leaseId ?? "") });
  if (!upstream.ok) return errorResponse(upstreamFailure(upstream.failure));
  await upstream.body?.cancel().catch(() => {});
  return json({ cloud: true, model: FISH_MODEL });
}

async function handleVoiceUpload(request: Request, env: Env): Promise<Response> {
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  const cfg = limits(env);
  const body = await readJson(request, cfg.maxAudioBytes * 2 + 64 * 1024);
  if (!body) return errorResponse(ERRORS.audioTooLarge);
  const voiceId = String(body.voiceId ?? "");
  if (!VOICE_ID_RE.test(voiceId)) return errorResponse(ERRORS.badRequest);
  const name = typeof body.name === "string" ? body.name.slice(0, 40) : voiceId;
  const referenceText = typeof body.referenceText === "string" ? body.referenceText.trim().slice(0, 200) : "";
  const audioB64 = String(body.audio ?? "");
  try {
    const raw = atob(audioB64);
    if (raw.length === 0 || raw.length > cfg.maxAudioBytes) return errorResponse(ERRORS.audioTooLarge);
  } catch {
    return errorResponse(ERRORS.badRequest);
  }
  // Inline voice: reference audio lives in KV and rides along on each TTS
  // request via `references`. This is the $0 path — Fish's POST /model
  // (persistent cloning) requires prepaid API credit, while inline cloning
  // is free with s2.1-pro-free. Persistent ids, if later provisioned by the
  // owner, automatically take precedence (resolveVoiceForTts).
  const stored = await gate(env, "registry", "/voice/put", {
    scope: "device",
    deviceId: claims.deviceId,
    voiceId,
    fishModelId: "",
    name,
    state: "trained",
    referenceText,
    audio: audioB64,
    bindingId: body.bindingId,
  });
  if (!stored.ok) return deviceError(stored);
  return json({ voiceId, state: "trained", ready: true });
}

async function handleVoiceLink(request: Request, env: Env): Promise<Response> {
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  const cfg = limits(env);
  if (!env.FISH_API_KEY) return errorResponse(ERRORS.serviceConfig);
  const body = await readJson(request, 8192);
  if (!body) return errorResponse(ERRORS.badRequest);
  const voiceId = String(body.voiceId ?? "");
  const fishModelId = String(body.fishModelId ?? "").toLowerCase();
  if (!VOICE_ID_RE.test(voiceId) || !FISH_MODEL_ID_RE.test(fishModelId)) {
    return errorResponse(ERRORS.badRequest);
  }
  // Verify under the owner key: private models of other accounts are not
  // visible, so a device cannot link someone else's private voice.
  const model = await fishGetModel(env, fishModelId, cfg.upstreamTimeoutMs);
  if (!model.ok) {
    return model.status === 404 ? errorResponse(ERRORS.voiceInvalid) : errorResponse(upstreamFailure("server"));
  }
  const stored = await gate(env, "registry", "/voice/put", {
    scope: "device",
    deviceId: claims.deviceId,
    voiceId,
    fishModelId,
    name: typeof body.name === "string" ? body.name.slice(0, 40) : voiceId,
    state: model.state,
    bindingId: body.bindingId,
  });
  if (!stored.ok) return deviceError(stored);
  return json({ voiceId, state: model.state, ready: model.state === "trained" });
}

async function handleVoiceList(request: Request, env: Env): Promise<Response> {
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  const listed = await gate(env, "registry", "/voice/list", { deviceId: claims.deviceId });
  if (!listed.ok) return deviceError(listed);
  const voices = [...(listed.voices as Array<Record<string, unknown>> ?? [])];
  // Bundled timbres are always available (compile-time inline audio); report
  // them unless a persistent/inline mapping already shadows the same id.
  for (const id of Object.keys(BUILTIN_VOICES)) {
    if (!voices.some((v) => v.voiceId === id)) {
      voices.push({ voiceId: id, scope: "global", name: id, state: "trained" });
    }
  }
  return json({ voices });
}

async function handleVoiceDelete(request: Request, env: Env, voiceId: string): Promise<Response> {
  const claims = await requireDevice(request, env);
  if (claims instanceof Response) return claims;
  if (!VOICE_ID_RE.test(voiceId)) return errorResponse(ERRORS.badRequest);
  const body = request.body === null ? {} : await readJson(request, 1024);
  if (!body) return errorResponse(ERRORS.badRequest);
  const deleted = await gate(env, "registry", "/voice/delete", {
    scope: "device",
    deviceId: claims.deviceId,
    voiceId,
    bindingId: body.bindingId,
  });
  if (!deleted.ok) return deviceError(deleted);
  return json({ deleted: true });
}

// ---- admin ----------------------------------------------------------------

function timingSafeStringEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function requireAdmin(request: Request, env: Env): Response | null {
  if (!env.ADMIN_TOKEN) return errorResponse(ERRORS.adminDisabled);
  if (!timingSafeStringEqual(bearer(request), env.ADMIN_TOKEN)) return errorResponse(ERRORS.adminUnauthorized);
  return null;
}

async function handleAdmin(request: Request, env: Env, url: URL): Promise<Response> {
  const denied = requireAdmin(request, env);
  if (denied) return denied;
  const cfg = limits(env);
  if (request.method === "GET" && url.pathname === "/admin/health") {
    // Presence/length only — secret values are never exposed. The optional
    // live probe reports just the upstream HTTP status (free model, 1 char).
    let fishProbe: number | null = null;
    if (env.FISH_API_KEY && url.searchParams.get("probe") === "1") {
      try {
        const res = await fetch(upstreamUrl("/v1/tts"), {
          method: "POST",
          headers: {
            Authorization: `Bearer ${env.FISH_API_KEY}`,
            "Content-Type": "application/json",
            model: FISH_MODEL,
          },
          body: JSON.stringify({ text: "嗯", format: "mp3" }),
          signal: AbortSignal.timeout(20_000),
        });
        fishProbe = res.status;
        await res.body?.cancel().catch(() => {});
      } catch {
        fishProbe = -1;
      }
    }
    return json({
      fishKeyPresent: !!env.FISH_API_KEY,
      fishKeyLength: (env.FISH_API_KEY ?? "").trim().length,
      signingKeyPresent: !!env.TOKEN_SIGNING_KEY,
      adminTokenPresent: !!env.ADMIN_TOKEN,
      model: FISH_MODEL,
      fishProbe,
    });
  }
  if (request.method === "POST" && url.pathname === "/admin/voice-maintenance") {
    const body = await readJson(request, 4096);
    if (!body) return errorResponse(ERRORS.badRequest);
    const cursor = typeof body.cursor === "string" ? body.cursor : undefined;
    return json(await runVoiceMaintenance(env, body.remove === true && env.VOICE_RETENTION_ENABLED === "true", cursor));
  }
  if (request.method === "GET" && url.pathname === "/admin/devices") {
    const result = await gate(env, "registry", "/devices", {});
    return json(result);
  }
  if (request.method === "POST" && url.pathname === "/admin/revoke") {
    const body = await readJson(request, 4096);
    const deviceId = String(body?.deviceId ?? "");
    if (!DEVICE_ID_RE.test(deviceId)) return errorResponse(ERRORS.badRequest);
    const result = await gate(env, "registry", "/revoke", { deviceId });
    return result.ok ? json({ revoked: true }) : deviceError(result);
  }
  if (request.method === "GET" && url.pathname === "/admin/voices") {
    const result = await gate(env, "registry", "/voice/list", { deviceId: "" });
    return json({ voices: result.voices ?? [] });
  }
  if (request.method === "POST" && url.pathname === "/admin/tts-test") {
    // Owner diagnostic: synthesizes with a bundled timbre and surfaces the
    // upstream status/reason (admin-only; device routes remain opaque).
    if (!env.FISH_API_KEY) return errorResponse(ERRORS.serviceConfig);
    const body = await readJson(request, 8192);
    const text = typeof body?.text === "string" ? body.text.slice(0, 100) : "你好。";
    const voiceId = typeof body?.voiceId === "string" ? body.voiceId : "timbre1";
    const voice = await resolveVoiceForTts(env, voiceId, "admin-test");
    if (!voice) return errorResponse(ERRORS.voiceNotFound);
    const upstream = await fishTts(
      env,
      {
        text,
        speed: 1.0,
        ...(voice.kind === "persistent"
          ? { referenceId: voice.referenceId }
          : { references: [{ audio: voice.audioB64, text: voice.text }] }),
      },
      cfg.upstreamTimeoutMs,
    );
    if (!upstream.ok) {
      return json({ ok: false, upstreamStatus: upstream.status, upstreamReason: upstream.reason ?? "" }, 200);
    }
    const bytes = new Uint8Array(await new Response(upstream.body).arrayBuffer());
    return json({ ok: true, audioBytes: bytes.length, voiceKind: voice.kind });
  }
  if (request.method === "POST" && url.pathname === "/admin/voices/clone") {
    // Owner-side provisioning of the bundled timbres: clone once under the
    // owner account, then map globally so every device reuses the same ids.
    if (!env.FISH_API_KEY) return errorResponse(ERRORS.serviceConfig);
    const body = await readJson(request, cfg.maxAudioBytes * 2 + 64 * 1024);
    if (!body) return errorResponse(ERRORS.audioTooLarge);
    const voiceId = String(body.voiceId ?? "");
    if (!VOICE_ID_RE.test(voiceId)) return errorResponse(ERRORS.badRequest);
    const referenceText = typeof body.referenceText === "string" ? body.referenceText.trim().slice(0, 200) : "";
    let audio: Uint8Array;
    try {
      const raw = atob(String(body.audio ?? ""));
      if (raw.length === 0 || raw.length > cfg.maxAudioBytes) return errorResponse(ERRORS.audioTooLarge);
      audio = new Uint8Array(raw.length);
      for (let i = 0; i < raw.length; i++) audio[i] = raw.charCodeAt(i);
    } catch {
      return errorResponse(ERRORS.badRequest);
    }
    const created = await fishCreateModel(
      env,
      `bluefin-builtin-${voiceId}`,
      audio,
      String(body.audioType ?? "audio/wav"),
      `${voiceId}.wav`,
      referenceText,
      cfg.upstreamTimeoutMs,
    );
    if (!created.ok) {
      // Admin-only route: the upstream status code is safe to surface here
      // (never the body). Device-facing routes stay fully opaque.
      const err = upstreamFailure(created.failure);
      return json({ error: err.error, category: err.category, message: err.message, upstreamStatus: created.status, upstreamReason: created.reason }, err.status);
    }
    const stored = await gate(env, "registry", "/voice/put", {
      scope: "global",
      voiceId,
      fishModelId: created.model.id,
      name: typeof body.name === "string" ? body.name.slice(0, 40) : voiceId,
      state: created.model.state,
    });
    if (!stored.ok) {
      await fishDeleteModel(env, created.model.id, cfg.upstreamTimeoutMs);
      return deviceError(stored);
    }
    return json({ voiceId, state: created.model.state, ready: created.model.state === "trained" });
  }
  if (request.method === "POST" && url.pathname === "/admin/voices") {
    const body = await readJson(request, 8192);
    const voiceId = String(body?.voiceId ?? "");
    const fishModelId = String(body?.fishModelId ?? "").toLowerCase();
    if (!VOICE_ID_RE.test(voiceId) || !FISH_MODEL_ID_RE.test(fishModelId)) {
      return errorResponse(ERRORS.badRequest);
    }
    if (env.FISH_API_KEY) {
      const model = await fishGetModel(env, fishModelId, cfg.upstreamTimeoutMs);
      if (!model.ok) return errorResponse(ERRORS.voiceInvalid);
    }
    const result = await gate(env, "registry", "/voice/put", {
      scope: "global",
      voiceId,
      fishModelId,
      name: typeof body?.name === "string" ? body.name.slice(0, 40) : voiceId,
      state: "trained",
    });
    return result.ok ? json({ provisioned: true }) : deviceError(result);
  }
  if (request.method === "DELETE" && url.pathname.startsWith("/admin/voices/")) {
    const voiceId = url.pathname.slice("/admin/voices/".length);
    if (!VOICE_ID_RE.test(voiceId)) return errorResponse(ERRORS.badRequest);
    const result = await gate(env, "registry", "/voice/delete", { scope: "global", voiceId });
    return result.ok ? json({ deleted: true }) : deviceError(result);
  }
  return errorResponse(ERRORS.notFound);
}

// ---- retention -------------------------------------------------------------

async function runVoiceMaintenance(env: Env, remove: boolean, cursor?: string, retire = cursor === undefined) {
  const result = await gate(env, "registry", "/maintenance/candidates", {});
  if (!result.ok) throw new Error("retention candidates unavailable");
  const expired = Array.isArray(result.expired) ? result.expired.map(String) : [];
  let retired = 0;
  let failed = 0;
  if (remove && retire) {
    for (const deviceId of expired.slice(0, 8)) {
      const reply = await gate(env, "registry", "/maintenance/device", { deviceId });
      if (!reply.ok) failed++;
      else if (reply.retired) retired++;
    }
  }
  let orphan = 0;
  let unclassified = 0;
  const page = await env.VOICES.list({ prefix: "audio:", limit: 32, cursor });
  for (const item of page.keys) {
    const reply = await gate(env, "registry", "/maintenance/orphan", { key: item.name, remove });
    if (!reply.ok) failed++;
    else if (reply.orphan) orphan++;
    else if (reply.unclassified) unclassified++;
  }
  return { expired: expired.length, retired, orphan, unclassified, failed, cursor: page.list_complete ? null : page.cursor };
}

// ---- entry ----------------------------------------------------------------

export default {
  async scheduled(_event: ScheduledController, env: Env): Promise<void> {
    const enabled = env.VOICE_RETENTION_ENABLED === "true";
    const cursorKey = "maintenance:audio-cursor";
    const cursor = await env.VOICES.get(cursorKey) ?? undefined;
    const result = await runVoiceMaintenance(env, enabled, cursor, true);
    if (result.cursor) await env.VOICES.put(cursorKey, result.cursor);
    else await env.VOICES.delete(cursorKey);
    console.log(JSON.stringify({ route: "voice_maintenance", dryRun: !enabled, ...result, cursor: undefined }));
  },
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/healthz") {
        return json({ ok: true, model: FISH_MODEL, engine: "fish-audio-cloud" });
      }
      if (url.pathname.startsWith("/admin/")) return await handleAdmin(request, env, url);
      if (request.method === "POST" && url.pathname === "/v1/register") {
        return await handleRegister(request, env);
      }
      if (request.method === "POST" && url.pathname === "/v1/token/refresh") {
        return await handleRefresh(request, env);
      }
      if (request.method === "POST" && url.pathname === "/v1/tts") {
        return await handleTts(request, env, ctx);
      }
      if (request.method === "POST" && url.pathname === "/v1/probe") {
        return await handleProbe(request, env);
      }
      if (request.method === "POST" && url.pathname === "/v1/voices") {
        return await handleVoiceUpload(request, env);
      }
      if (request.method === "POST" && url.pathname === "/v1/voices/link") {
        return await handleVoiceLink(request, env);
      }
      if (request.method === "GET" && url.pathname === "/v1/voices") {
        return await handleVoiceList(request, env);
      }
      if (request.method === "DELETE" && url.pathname.startsWith("/v1/voices/")) {
        return await handleVoiceDelete(request, env, url.pathname.slice("/v1/voices/".length));
      }
      return errorResponse(ERRORS.notFound);
    } catch (failure) {
      // Unexpected errors: type only, never the message (may carry internals).
      console.error(JSON.stringify({ route: url.pathname, failed: (failure as Error)?.name ?? "Error" }));
      return errorResponse(ERRORS.upstreamError);
    }
  },
};
