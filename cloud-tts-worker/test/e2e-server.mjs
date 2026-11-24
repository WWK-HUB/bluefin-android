/**
 * Local E2E rig for emulator verification — NO real credentials anywhere.
 *
 *  - port 8787: the real worker on workerd (Miniflare), upstream intercepted
 *    by a mock that serves a real synthesized WAV clip (audible playback).
 *  - port 8788: mock OpenAI-compatible chat endpoint (SSE) so the app's full
 *    chat → emotion marker → auto-read pipeline runs end to end.
 *  - port 8791: control plane for the test driver (fault injection / state).
 *
 * The emulator reaches all three via 10.0.2.2. Run: node test/e2e-server.mjs
 */

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { Miniflare, Log, LogLevel } from "miniflare";
import { decode as msgpackDecode } from "@msgpack/msgpack";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
// Audible reference clip for emulator listening tests; falls back to silent
// MP3 frames when the local test asset is absent (pipeline still verifiable).
const WAV_PATH = process.env.E2E_AUDIO_WAV ?? "D:/xiaoshen/.voice-verify/zv-out/fp32-base.wav";
function silentMp3(frames = 80) {
  const frame = Buffer.alloc(417, 0);
  frame[0] = 0xff; frame[1] = 0xfb; frame[2] = 0x90; frame[3] = 0xc4;
  return Buffer.concat(Array.from({ length: frames }, () => frame));
}
const AUDIO = fs.existsSync(WAV_PATH) ? fs.readFileSync(WAV_PATH) : silentMp3();
const TEST_KEY = "e2e-test-key-not-a-real-secret";
const SIGNING = "e2e-signing-secret-0123456789abcdef";
const ADMIN = "e2e-admin-secret-0123456789abcdef";
const MODEL_ID = "e2e000000000000000000001";

const state = {
  mode: "ok",
  ttsCount: 0,
  lastTts: null,
  chatReply: "[[emotion:happy]] 哼，你终于来找我啦！我才没有一直等你呢，只是刚好看了一眼时间而已。今天也要开开心心的哦！",
  chatRequests: 0,
};

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

async function mockUpstream(req) {
  const url = new URL(req.url);
  const jsonRes = (status, value) =>
    new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json" } });
  if (url.pathname === "/v1/tts" && req.method === "POST") {
    const raw = new Uint8Array(await req.arrayBuffer());
    const payload = (req.headers.get("content-type") ?? "").includes("msgpack")
      ? msgpackDecode(raw)
      : JSON.parse(new TextDecoder().decode(raw));
    state.ttsCount++;
    state.lastTts = { model: req.headers.get("model"), payload };
    if (req.headers.get("model") !== "s2.1-pro-free") {
      return jsonRes(400, { status: 400, message: "wrong model" });
    }
    if (state.mode !== "ok") {
      return jsonRes(Number(state.mode), { status: Number(state.mode), message: `mock ${state.mode}` });
    }
    return new Response(AUDIO, { status: 200, headers: { "content-type": "audio/mpeg" } });
  }
  if (url.pathname === "/model" && req.method === "POST") {
    await req.arrayBuffer();
    return jsonRes(200, { _id: MODEL_ID, state: "trained" });
  }
  const m = url.pathname.match(/^\/model\/([0-9a-f]+)$/);
  if (m) {
    if (req.method === "DELETE") return jsonRes(200, {});
    return jsonRes(200, { _id: m[1], state: "trained" });
  }
  return jsonRes(404, {});
}

const mf = new Miniflare({
  modules: true,
  scriptPath: path.join(ROOT, "dist-test/index.mjs"),
  compatibilityDate: "2025-09-01",
  compatibilityFlags: ["enable_request_signal"],
  port: 8787,
  hostname: "127.0.0.1",
  durableObjects: { GATE: { className: "Gate", useSQLite: true } },
  kvNamespaces: { VOICES: "voices" },
  bindings: {
    FISH_API_KEY: TEST_KEY,
    TOKEN_SIGNING_KEY: SIGNING,
    ADMIN_TOKEN: ADMIN,
    DEVICE_RATE_MAX: "60",
    DEVICE_DAILY_CHARS: "100000",
    GLOBAL_CONCURRENCY: "4",
    MAX_DEVICES: "50",
    REGISTER_PER_IP_DAILY: "50",
    PROBE_MIN_INTERVAL_MS: "5000",
  },
  outboundService: mockUpstream,
  log: new Log(LogLevel.WARN),
});

// Provision the bundled timbres globally, exactly like the deploy script does.
for (const id of ["timbre1", "timbre2"]) {
  const res = await mf.dispatchFetch("https://worker.test/admin/voices", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${ADMIN}` },
    body: JSON.stringify({ voiceId: id, fishModelId: MODEL_ID, name: id }),
  });
  if (res.status !== 200) throw new Error(`provision ${id} failed: ${res.status}`);
}

// ---- mock chat endpoint (SSE) ----------------------------------------------

const chat = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = JSON.parse(Buffer.concat(chunks).toString() || "{}");
  state.chatRequests++;
  if (req.url.endsWith("/chat/completions") && body.stream === false) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ choices: [{ message: { content: "pong" } }] }));
    return;
  }
  if (req.url.endsWith("/chat/completions")) {
    res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" });
    const reply = state.chatReply;
    // Stream in small deltas so the emotion-marker hold path is exercised.
    for (let i = 0; i < reply.length; i += 7) {
      const delta = reply.slice(i, i + 7);
      res.write(`data: ${JSON.stringify({ choices: [{ delta: { content: delta } }] })}\n\n`);
      await new Promise((r) => setTimeout(r, 30));
    }
    res.write("data: [DONE]\n\n");
    res.end();
    return;
  }
  res.writeHead(404); res.end("{}");
});
chat.listen(8788, "0.0.0.0");

// ---- control plane ----------------------------------------------------------

const control = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks).toString();
  res.writeHead(200, { "Content-Type": "application/json" });
  if (req.url === "/mode" && req.method === "POST") {
    state.mode = JSON.parse(body).mode;
    res.end(JSON.stringify({ mode: state.mode }));
    return;
  }
  if (req.url === "/chat-reply" && req.method === "POST") {
    state.chatReply = JSON.parse(body).text;
    res.end(JSON.stringify({ ok: true }));
    return;
  }
  res.end(JSON.stringify({
    mode: state.mode,
    ttsCount: state.ttsCount,
    lastTts: state.lastTts,
    chatRequests: state.chatRequests,
  }));
});
control.listen(8791, "127.0.0.1");

console.log("E2E rig ready: worker=8787 chat=8788 control=8791 (emulator: 10.0.2.2)");
console.log("Ctrl+C to stop.");

process.on("SIGINT", async () => {
  await mf.dispose();
  chat.close();
  control.close();
  process.exit(0);
});
