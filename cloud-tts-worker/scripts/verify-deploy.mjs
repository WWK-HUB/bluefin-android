/**
 * Post-deployment smoke test through the REAL worker + REAL Fish Audio free
 * model. Needs no vendor key locally — only the worker URL. Registers a
 * throwaway test device, synthesizes one Chinese sentence with timbre1 and
 * saves the MP3 so you can listen to it.
 *
 * Usage:
 *   WORKER_URL=https://bluefin-tts.<your-subdomain>.workers.dev \
 *   node scripts/verify-deploy.mjs
 *
 * Exit code 0 = all checks passed. The saved file is verify-output.mp3.
 */

import fs from "node:fs";

const WORKER_URL = (process.env.WORKER_URL ?? "").replace(/\/+$/, "");
if (!WORKER_URL) {
  console.error("Set WORKER_URL first.");
  process.exit(1);
}

let failures = 0;
function check(name, ok, detail = "") {
  console.log(`${ok ? "  ok " : "FAIL "} ${name}${detail ? " — " + detail : ""}`);
  if (!ok) failures++;
}

async function call(method, route, { token, body } = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${WORKER_URL}${route}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const type = res.headers.get("content-type") ?? "";
  return {
    status: res.status,
    headers: res.headers,
    data: type.includes("json") ? await res.json() : Buffer.from(await res.arrayBuffer()),
  };
}

const health = await call("GET", "/healthz");
check("worker reachable, free model configured", health.status === 200 && health.data.model === "s2.1-pro-free");

const deviceId = crypto.randomUUID();
const reg = await call("POST", "/v1/register", { body: { deviceId, name: "deploy-verify" } });
check("device registration", reg.status === 200 && !!reg.data.token, JSON.stringify(reg.data).slice(0, 120));
const token = reg.data.token;

const voices = await call("GET", "/v1/voices", { token });
const timbre1 = voices.data.voices?.find((v) => v.voiceId === "timbre1");
check("timbre1 provisioned", !!timbre1 && timbre1.state === "trained",
  "run scripts/provision-voices.mjs if missing");

if (timbre1) {
  const tts = await call("POST", "/v1/tts", {
    token,
    body: { text: "你好呀，我是小深！今天也要开开心心的哦。", voiceId: "timbre1", speed: 1.0, emotion: "happy" },
  });
  const isAudio = tts.status === 200 && Buffer.isBuffer(tts.data) && tts.data.length > 2000;
  check("free-model synthesis returns audio", isAudio, `status=${tts.status} bytes=${tts.data?.length ?? 0}`);
  if (isAudio) {
    fs.writeFileSync("verify-output.mp3", tts.data);
    console.log("  saved verify-output.mp3 — play it to judge naturalness.");
  } else {
    console.log("  error detail:", JSON.stringify(tts.data).slice(0, 200));
  }
}

const probe = await call("POST", "/v1/probe", { token });
check("recovery probe", probe.status === 200 && probe.data.cloud === true);

console.log(failures === 0 ? "\nDEPLOY VERIFICATION PASSED" : `\n${failures} CHECK(S) FAILED`);
process.exit(failures === 0 ? 0 : 1);