/**
 * End-to-end integration test on real workerd (Miniflare) with real Durable
 * Objects. The Fish Audio upstream is intercepted with undici MockAgent —
 * no real API key, no network, and the worker source has NO test override:
 * it always calls https://api.fish.audio with model s2.1-pro-free.
 *
 * Run: node test/integration.mjs
 */

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare, Log, LogLevel } from "miniflare";
import { decode as msgpackDecode } from "@msgpack/msgpack";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const TEST_KEY = "integration-test-key-not-a-real-secret";
const SIGNING = "integration-signing-secret-0123456789abcdef";
const ADMIN = "integration-admin-secret-0123456789abcdef";
const MODEL_ID = "a1b2c3d4e5f6a1b2c3d4e5f6";
const UPSTREAM = "https://api.fish.audio";

// ---- bundle the worker -----------------------------------------------------

await build({
  entryPoints: [path.join(ROOT, "src/index.ts")],
  bundle: true,
  format: "esm",
  platform: "neutral",
  mainFields: ["module", "main"],
  target: "es2022",
  conditions: ["worker"],
  outfile: path.join(ROOT, "dist-test/index.mjs"),
  logLevel: "silent",
});

// ---- mock upstream ---------------------------------------------------------

/** Minimal valid MPEG1-Layer3 128kbps 44.1kHz mono silent frames. */
function silentMp3(frames = 40) {
  const frame = Buffer.alloc(417, 0);
  frame[0] = 0xff; frame[1] = 0xfb; frame[2] = 0x90; frame[3] = 0xc4;
  return Buffer.concat(Array.from({ length: frames }, () => frame));
}
const AUDIO = silentMp3();

const mockState = {
  mode: "ok",
  ttsRequests: [],
  modelPosts: 0,
  models: new Map([[MODEL_ID, "trained"]]),
};

/**
 * Outbound interceptor: every fetch the worker makes lands here. Anything
 * aimed at a host other than the one public vendor is a contract violation.
 */
async function mockUpstream(req) {
  const url = new URL(req.url);
  if (url.origin !== UPSTREAM) {
    return new Response(JSON.stringify({ error: "unexpected upstream host" }), { status: 500 });
  }
  const auth = req.headers.get("authorization");
  const jsonRes = (status, value) =>
    new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json" } });

  if (url.pathname === "/v1/tts" && req.method === "POST") {
    const raw = new Uint8Array(await req.arrayBuffer());
    const payload = (req.headers.get("content-type") ?? "").includes("msgpack")
      ? msgpackDecode(raw)
      : JSON.parse(new TextDecoder().decode(raw));
    const model = req.headers.get("model");
    mockState.ttsRequests.push({ model, auth, payload });
    if (auth !== `Bearer ${TEST_KEY}`) return jsonRes(401, { status: 401, message: "bad key" });
    if (model !== "s2.1-pro-free") {
      return jsonRes(400, { status: 400, message: "model header must be s2.1-pro-free" });
    }
    if (mockState.mode === "held") {
      let cancelled = false;
      const stream = new ReadableStream({
        start(controller) { controller.enqueue(AUDIO.subarray(0, 512)); },
        async pull(controller) {
          await sleep(50);
          if (!cancelled) controller.enqueue(AUDIO.subarray(0, 512));
        },
        cancel() { cancelled = true; },
      });
      return new Response(stream, { headers: { "content-type": "audio/mpeg" } });
    }
    if (mockState.mode === "slow-headers") {
      await sleep(500);
    } else if (mockState.mode !== "ok") {
      const status = Number(mockState.mode);
      return jsonRes(status, { status, message: `mock ${status}` });
    }
    const stream = new ReadableStream({
      async start(controller) {
        for (let i = 0; i < AUDIO.length; i += 512) {
          controller.enqueue(AUDIO.subarray(i, i + 512));
          await new Promise((r) => setTimeout(r, 1));
        }
        controller.close();
      },
    });
    return new Response(stream, { status: 200, headers: { "content-type": "audio/mpeg" } });
  }
  if (url.pathname === "/model" && req.method === "POST") {
    if (auth !== `Bearer ${TEST_KEY}`) return jsonRes(401, {});
    await req.arrayBuffer(); // drain the multipart body
    mockState.modelPosts++;
    const id = "b" + MODEL_ID.slice(1);
    mockState.models.set(id, "trained");
    return jsonRes(200, { _id: id, state: "trained" });
  }
  const modelMatch = url.pathname.match(/^\/model\/([0-9a-f]+)$/);
  if (modelMatch) {
    const id = modelMatch[1];
    if (req.method === "DELETE") {
      const had = mockState.models.delete(id);
      return jsonRes(had ? 200 : 404, {});
    }
    const state = mockState.models.get(id);
    if (!state) return jsonRes(404, { status: 404, message: "no model" });
    return jsonRes(200, { _id: id, state });
  }
  return jsonRes(404, {});
}

// ---- worker ----------------------------------------------------------------

const mf = new Miniflare({
  modules: true,
  scriptPath: path.join(ROOT, "dist-test/index.mjs"),
  compatibilityDate: "2025-09-01",
  compatibilityFlags: ["enable_request_signal"],
  unsafeDirectSockets: [{ host: "127.0.0.1" }],
  durableObjects: { GATE: { className: "Gate", useSQLite: true } },
  kvNamespaces: { VOICES: "voices" },
  bindings: {
    FISH_API_KEY: TEST_KEY,
    TOKEN_SIGNING_KEY: SIGNING,
    ADMIN_TOKEN: ADMIN,
    MAX_TEXT_CHARS: "500",
    DEVICE_DAILY_CHARS: "120",
    DEVICE_RATE_MAX: "5",
    DEVICE_RATE_WINDOW_MS: "60000",
    GLOBAL_CONCURRENCY: "2",
    MAX_DEVICES: "4",
    REGISTER_PER_IP_DAILY: "3",
    MAX_VOICES_PER_DEVICE: "3",
    MAX_AUDIO_BYTES: "4194304",
    UPSTREAM_TIMEOUT_MS: "30000",
    TOKEN_TTL_SECONDS: "604800",
    PROBE_MIN_INTERVAL_MS: "1000",
  },
  outboundService: mockUpstream,
  log: new Log(LogLevel.WARN),
});

async function api(method, route, { token, body, admin, ip } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (admin) headers.Authorization = `Bearer ${admin}`;
  if (ip) headers["CF-Connecting-IP"] = ip;
  const res = await mf.dispatchFetch(`https://worker.test${route}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const type = res.headers.get("content-type") ?? "";
  const data = type.includes("json") ? await res.json() : Buffer.from(await res.arrayBuffer());
  return { status: res.status, data, headers: res.headers };
}

let failures = 0;
function check(name, condition, detail = "") {
  if (condition) console.log(`  ok  ${name}`);
  else { failures++; console.error(`FAIL  ${name} ${detail}`); }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  const health = await api("GET", "/healthz");
  check("healthz reports the free model", health.data.model === "s2.1-pro-free");

  // Registration + auth
  const bad = await api("POST", "/v1/register", { body: { deviceId: "not-a-uuid" }, ip: "1.2.3.4" });
  check("register rejects malformed device id", bad.status === 400);
  const deviceA = crypto.randomUUID();
  const reg = await api("POST", "/v1/register", { body: { deviceId: deviceA, name: "test-a" }, ip: "1.2.3.4" });
  check("register issues a token", reg.status === 200 && typeof reg.data.token === "string", JSON.stringify(reg.data));
  const tokenA = reg.data.token;
  const noAuth = await api("POST", "/v1/tts", { body: { text: "你好", voiceId: "timbre1" } });
  check("tts without token is 401 AUTH_ERROR", noAuth.status === 401 && noAuth.data.category === "AUTH_ERROR");
  const badToken = await api("POST", "/v1/tts", { token: "v1.forged.sig", body: { text: "你好", voiceId: "timbre1" } });
  check("tts with forged token is 401", badToken.status === 401);

  // Bundled timbres work with ZERO provisioning (compile-time inline audio):
  // the $0 path that needs no Fish model creation and no device setup.
  const builtin = await api("POST", "/v1/tts", { token: tokenA, body: { text: "内置音色测试。", voiceId: "timbre1" } });
  const builtinUp = mockState.ttsRequests.at(-1);
  check("builtin timbre works without provisioning", builtin.status === 200 && Buffer.isBuffer(builtin.data));
  check("builtin uses inline references, not reference_id", Array.isArray(builtinUp?.payload.references) && !builtinUp?.payload.reference_id);
  check("builtin reference carries transcript", !!builtinUp?.payload.references?.[0]?.text);
  const noVoice = await api("POST", "/v1/tts", { token: tokenA, body: { text: "你好呀。", voiceId: "unknown-voice" } });
  check("unknown voice is 404 INVALID_VOICE", noVoice.status === 404 && noVoice.data.category === "INVALID_VOICE");

  // Admin auth + global provisioning
  const adminNoAuth = await api("GET", "/admin/devices");
  check("admin without token is 401", adminNoAuth.status === 401);
  const prov = await api("POST", "/admin/voices", { admin: ADMIN, body: { voiceId: "timbre1", fishModelId: MODEL_ID, name: "音色1" } });
  check("admin provisions global voice", prov.status === 200, JSON.stringify(prov.data));
  const clone = await api("POST", "/admin/voices/clone", { admin: ADMIN, body: { voiceId: "timbre2", name: "音色2", audio: Buffer.from("UklGRg==").toString("base64"), referenceText: "参考" } });
  check("admin clone provisions bundled timbre", clone.status === 200 && clone.data.ready === true);
  const cloneNoAuth = await api("POST", "/admin/voices/clone", { body: { voiceId: "x", audio: "AA==" } });
  check("admin clone requires admin token", cloneNoAuth.status === 401);

  // Happy path TTS
  const tts = await api("POST", "/v1/tts", { token: tokenA, body: { text: "你好呀，我是小深！", voiceId: "timbre1", speed: 1.0, emotion: "happy" } });
  check("tts streams audio", tts.status === 200 && Buffer.isBuffer(tts.data) && tts.data.length === AUDIO.length, `status=${tts.status} len=${tts.data?.length}`);
  check("tts content-type is audio/mpeg", (tts.headers.get("content-type") ?? "").includes("audio/mpeg"));
  const last = mockState.ttsRequests.at(-1);
  check("upstream model header is s2.1-pro-free", last?.model === "s2.1-pro-free");
  check("upstream auth uses the server secret", last?.auth === `Bearer ${TEST_KEY}`);
  check("upstream got reference_id", last?.payload.reference_id === MODEL_ID);
  check("emotion cue prepended server-side", last?.payload.text === "[happy] 你好呀，我是小深！");
  check("prosody.speed forwarded", last?.payload.prosody?.speed === 1.0);
  check("format is mp3", last?.payload.format === "mp3");

  // Client cannot inject directives or tune upstream params
  const inject = await api("POST", "/v1/tts", { token: tokenA, body: { text: "[[emotion:angry]] [whisper] 哼。", voiceId: "timbre1", emotion: "sad", model: "s2.1-pro", temperature: 0.1 } });
  const injUp = mockState.ttsRequests.at(-1);
  check("client directives stripped", inject.status === 200 && injUp.payload.text === "[sad] 哼。", JSON.stringify(injUp?.payload));
  check("client cannot override model/temperature", injUp.model === "s2.1-pro-free" && injUp.payload.temperature === 0.7);

  // Speed clamp + text cap
  const fast = await api("POST", "/v1/tts", { token: tokenA, body: { text: "快一点。", voiceId: "timbre1", speed: 99 } });
  check("speed clamped to 2.0", fast.status === 200 && mockState.ttsRequests.at(-1).payload.prosody.speed === 2.0);
  const long = await api("POST", "/v1/tts", { token: tokenA, body: { text: "长".repeat(501), voiceId: "timbre1" } });
  check("over-long text is 413", long.status === 413);

  // Rate limit (DEVICE_RATE_MAX=5; device A already used 3)
  let rateHit = null;
  for (let i = 0; i < 4; i++) {
    const r = await api("POST", "/v1/tts", { token: tokenA, body: { text: "测试。", voiceId: "timbre1" } });
    if (r.status === 429) { rateHit = r; break; }
  }
  check("per-device rate limit hits 429 RATE_LIMITED", rateHit !== null && rateHit.data.category === "RATE_LIMITED");

  // Fresh device for quota checks (DEVICE_DAILY_CHARS=120)
  const deviceB = crypto.randomUUID();
  const tokenB = (await api("POST", "/v1/register", { body: { deviceId: deviceB }, ip: "1.2.3.4" })).data.token;
  let quotaHit = null;
  for (let i = 0; i < 6; i++) {
    const r = await api("POST", "/v1/tts", { token: tokenB, body: { text: "字".repeat(30) + "。", voiceId: "timbre1" } });
    if (r.status === 429 && r.data.error === "quota_exceeded") { quotaHit = r; break; }
    if (r.status === 429) await sleep(1100);
  }
  check("daily quota enforced QUOTA_EXHAUSTED", quotaHit !== null && quotaHit.data.category === "QUOTA_EXHAUSTED");

  // Upstream failures — normalized categories, never a paid fallback
  const deviceC = crypto.randomUUID();
  const tokenC = (await api("POST", "/v1/register", { body: { deviceId: deviceC }, ip: "5.6.7.8" })).data.token;
  for (const [mode, expectedStatus, expectedCategory] of [
    ["402", 503, "MODEL_UNAVAILABLE"],
    ["429", 429, "RATE_LIMITED"],
    ["500", 502, "SERVICE_UNAVAILABLE"],
    ["401", 502, "AUTH_ERROR"],
  ]) {
    mockState.mode = mode;
    const r = await api("POST", "/v1/tts", { token: tokenC, body: { text: "错误测试。", voiceId: "timbre1" } });
    check(`upstream ${mode} → ${expectedStatus} ${expectedCategory}`, r.status === expectedStatus && r.data.category === expectedCategory, JSON.stringify(r.data));
  }
  mockState.mode = "ok";
  check("every upstream call used the free model", mockState.ttsRequests.every((r) => r.model === "s2.1-pro-free"));
  check("no raw upstream message leaked", !JSON.stringify(mockState.ttsRequests).includes("mock 402"));

  // Probe: recovery check uses the free model and is separately rate-limited
  const probe = await api("POST", "/v1/probe", { token: tokenC });
  check("probe reports cloud ok", probe.status === 200 && probe.data.cloud === true && probe.data.model === "s2.1-pro-free");
  check("probe used the free model upstream", mockState.ttsRequests.at(-1).model === "s2.1-pro-free");
  const probe2 = await api("POST", "/v1/probe", { token: tokenC });
  check("probe rate-limited within interval", probe2.status === 429);
  mockState.mode = "402";
  await sleep(1100);
  const probe3 = await api("POST", "/v1/probe", { token: tokenC });
  check("probe surfaces MODEL_UNAVAILABLE when free model dies", probe3.status === 503 && probe3.data.category === "MODEL_UNAVAILABLE");
  mockState.mode = "ok";

  // Device-private voice upload + isolation (inline KV storage, $0 path)
  const wav = Buffer.from("UklGRg==", "base64");
  const postsBefore = mockState.modelPosts;
  const up = await api("POST", "/v1/voices", { token: tokenC, body: { voiceId: "my-voice", name: "我的音色", audio: wav.toString("base64"), referenceText: "参考文本" } });
  check("voice upload stores inline voice (no paid model creation)", up.status === 200 && up.data.ready === true && mockState.modelPosts === postsBefore, JSON.stringify(up.data));
  const mine = await api("POST", "/v1/tts", { token: tokenC, body: { text: "私有音色测试。", voiceId: "my-voice" } });
  check("owner can use the private voice", mine.status === 200);
  const other = await api("POST", "/v1/tts", { token: tokenB, body: { text: "别人的音色。", voiceId: "my-voice" } });
  check("other device cannot resolve private voice", other.status === 404);
  const listB = await api("GET", "/v1/voices", { token: tokenB });
  check("voice list hides other devices' voices", !listB.data.voices.some((v) => v.voiceId === "my-voice"));
  const listC = await api("GET", "/v1/voices", { token: tokenC });
  check("voice list shows own + global voices", listC.data.voices.some((v) => v.voiceId === "my-voice") && listC.data.voices.some((v) => v.voiceId === "timbre1"));
  check("voice list never exposes fish model ids", !JSON.stringify(listC.data).includes(MODEL_ID));

  // Link an existing Fish id (verified under owner key)
  const link = await api("POST", "/v1/voices/link", { token: tokenC, body: { voiceId: "linked", fishModelId: MODEL_ID, name: "已有音色" } });
  check("link existing fish id works", link.status === 200);
  const linkBad = await api("POST", "/v1/voices/link", { token: tokenC, body: { voiceId: "linked2", fishModelId: "f".repeat(32) } });
  check("link unknown/private fish id rejected", linkBad.status === 400);

  // Token refresh
  const refreshed = await api("POST", "/v1/token/refresh", { token: tokenC });
  check("refresh rotates the token", refreshed.status === 200 && refreshed.data.token !== tokenC);

  // Revocation
  const revoke = await api("POST", "/admin/revoke", { admin: ADMIN, body: { deviceId: deviceC } });
  check("admin revokes device", revoke.status === 200);
  const revoked = await api("POST", "/v1/tts", { token: refreshed.data.token, body: { text: "被停用。", voiceId: "timbre1" } });
  check("revoked device is 403 AUTH_ERROR", revoked.status === 403 && revoked.data.category === "AUTH_ERROR");

  // Registration caps (REGISTER_PER_IP_DAILY=3 for ip 1.2.3.4; MAX_DEVICES=4 total)
  const regC2 = await api("POST", "/v1/register", { body: { deviceId: deviceC }, ip: "5.6.7.8" });
  check("revocation still prevents registering the same device", regC2.status === 403);
  const deviceD = crypto.randomUUID();
  const regD = await api("POST", "/v1/register", { body: { deviceId: deviceD }, ip: "9.9.9.9" });
  check("replacement device registers", regD.status === 200);
  const regE = await api("POST", "/v1/register", { body: { deviceId: crypto.randomUUID() }, ip: "8.8.8.8" });
  check("revoked device frees an active slot", regE.status === 200);
  const regF = await api("POST", "/v1/register", { body: { deviceId: crypto.randomUUID() }, ip: "1.2.3.4" });
  check("registration beyond device cap rejected", regF.status === 503);

  // Voice delete removes the inline audio and mapping
  const kv = await mf.getKVNamespace("VOICES");
  check("revocation clears device recording copies", (await kv.list({ prefix: `audio:${deviceC}:` })).keys.length === 0);
  const tokenD = regD.data.token;
  await api("POST", "/v1/voices/link", { token: tokenD, body: { voiceId: "another-link", fishModelId: MODEL_ID } });
  const unlink = await api("DELETE", "/v1/voices/another-link", { token: tokenD });
  check("deleting a link preserves the existing vendor model", unlink.data.deleted && mockState.models.has(MODEL_ID));
  const upD = await api("POST", "/v1/voices", { token: tokenD, body: { voiceId: "temp", audio: wav.toString("base64"), referenceText: "x" } });
  const del = await api("DELETE", "/v1/voices/temp", { token: tokenD });
  const listD = await api("GET", "/v1/voices", { token: tokenD });
  const afterDel = await api("POST", "/v1/tts", { token: tokenD, body: { text: "删除后。", voiceId: "temp" } });
  check("voice delete removes inline voice", upD.status === 200 && del.status === 200 && !listD.data.voices.some((v) => v.voiceId === "temp") && afterDel.status === 404);
  check("delete removes the actual recording", (await kv.list({ prefix: `audio:${deviceD}:temp` })).keys.length === 0);
  check("repeat delete is acknowledged", (await api("DELETE", "/v1/voices/temp", { token: tokenD })).data.deleted === true);

  const upload = () => api("POST", "/v1/voices", { token: tokenD, body: { voiceId: "race", audio: wav.toString("base64"), referenceText: "x" } });
  await Promise.all([upload(), api("DELETE", "/v1/voices/race", { token: tokenD }), upload()]);
  const listed = await api("GET", "/v1/voices", { token: tokenD });
  const live = listed.data.voices.some((voice) => voice.voiceId === "race");
  check("concurrent delete and upload leave no orphan", (await kv.list({ prefix: `audio:${deviceD}:race` })).keys.length === Number(live));
  const scopedUpload = (bindingId) => api("POST", "/v1/voices", {
    token: tokenD, body: { voiceId: "scoped", bindingId, audio: wav.toString("base64"), referenceText: "x" },
  });
  const scopedDelete = (bindingId) => api("DELETE", "/v1/voices/scoped", { token: tokenD, body: { bindingId } });
  const oldBinding = crypto.randomUUID();
  const newBinding = crypto.randomUUID();
  check("delete acknowledges a binding whose upload has not arrived", (await scopedDelete(oldBinding)).data.deleted === true);
  check("late upload for a deleted binding is refused", (await scopedUpload(oldBinding)).status === 400);
  check("replacement binding can upload", (await scopedUpload(newBinding)).status === 200);
  await scopedDelete(oldBinding);
  check("late deletion preserves the replacement binding",
    (await kv.list({ prefix: `audio:${deviceD}:scoped` })).keys.length === 1);
  const audit = await api("POST", "/admin/voice-maintenance", { admin: ADMIN, body: {} });
  check("maintenance can report without deleting", audit.status === 200 && typeof audit.data.expired === "number");

  // Keep the upstream open so aborting the HTTP client interrupts synthesis.
  // Use the Worker socket directly so the Miniflare ingress proxy does not
  // mask the incoming connection's abort signal.
  const ttsUrl = new URL("/v1/tts", await mf.unsafeGetDirectURL());
  const heldBody = { text: "字".repeat(120), voiceId: "timbre1" };
  function requestTts(token, abort) {
    return fetch(ttsUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify(heldBody),
      signal: abort.signal,
    });
  }
  async function openStream(token, abort) {
    const response = await requestTts(token, abort);
    assert.equal(response.status, 200);
    assert.equal((await response.body.getReader().read()).done, false);
  }
  async function waitForNoLeases() {
    const deadline = Date.now() + 5_000;
    do {
      const devices = await api("GET", "/admin/devices", { admin: ADMIN });
      if (devices.data.inFlight === 0) return true;
      await sleep(25);
    } while (Date.now() < deadline);
    return false;
  }
  const first = new AbortController();
  const second = new AbortController();
  mockState.mode = "held";
  try {
    await openStream(tokenD, first);
    const deviceBusy = await api("POST", "/v1/tts", { token: tokenD, body: heldBody });
    check("active device gets its own busy response", deviceBusy.status === 429 &&
      deviceBusy.data.error === "device_busy" && deviceBusy.data.category === "RATE_LIMITED");
    await openStream(regE.data.token, second);
    const globalBusy = await api("POST", "/v1/tts", { token: tokenB, body: heldBody });
    check("global slot exhaustion still returns busy", globalBusy.status === 429 && globalBusy.data.error === "busy");
  } finally {
    first.abort();
    second.abort();
    mockState.mode = "ok";
  }
  check("disconnect releases both global leases before expiry", await waitForNoLeases());
  const resumed = await api("POST", "/v1/tts", { token: tokenD, body: { ...heldBody, text: "继续朗读。" } });
  check("cancelled synthesis frees the same device and incurs no character quota", resumed.status === 200);

  // A stop can also arrive while the vendor is still preparing the response.
  const earlyAbort = new AbortController();
  mockState.mode = "slow-headers";
  const requestsBefore = mockState.ttsRequests.length;
  const waitingForAudio = requestTts(tokenD, earlyAbort);
  try {
    const deadline = Date.now() + 3_000;
    while (mockState.ttsRequests.length === requestsBefore && Date.now() < deadline) await sleep(10);
    assert.ok(mockState.ttsRequests.length > requestsBefore, "the cancelled request reached the vendor");
    earlyAbort.abort();
    await assert.rejects(waitingForAudio, { name: "AbortError" });
    check("disconnect before upstream headers releases the lease", await waitForNoLeases());
  } finally {
    earlyAbort.abort();
    mockState.mode = "ok";
    await waitingForAudio.catch(() => {});
  }
  const afterEarlyAbort = await api("POST", "/v1/tts", { token: tokenD, body: { ...heldBody, text: "再次朗读。" } });
  check("the same device can speak after cancelling before headers", afterEarlyAbort.status === 200);

  console.log(failures === 0 ? "\nALL INTEGRATION CHECKS PASSED" : `\n${failures} CHECK(S) FAILED`);
} catch (error) {
  failures++;
  console.error("integration run error:", error);
} finally {
  await mf.dispose();
  process.exit(failures === 0 ? 0 : 1);
}
