/**
 * Owner-side provisioning of the two bundled timbres (timbre1 / timbre2).
 *
 * Uploads the reference WAVs THROUGH the deployed worker (admin route), so
 * the Fish API key is only ever the worker secret — this script needs the
 * ADMIN_TOKEN, not the vendor key. Run once after `wrangler deploy` and
 * `wrangler secret put`.
 *
 * Usage (Git Bash / PowerShell):
 *   WORKER_URL=https://bluefin-tts.<your-subdomain>.workers.dev \
 *   ADMIN_TOKEN=<the admin secret you set> \
 *   node scripts/provision-voices.mjs
 *
 * Optional: VOICE_PACK_DIR overrides the assets location.
 */

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const WORKER_URL = (process.env.WORKER_URL ?? "").replace(/\/+$/, "");
const ADMIN_TOKEN = process.env.ADMIN_TOKEN ?? "";
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PACK_DIR = process.env.VOICE_PACK_DIR
  ?? path.join(ROOT, "../app/src/main/assets/voice-packs");

if (!WORKER_URL || !ADMIN_TOKEN) {
  console.error("Set WORKER_URL and ADMIN_TOKEN environment variables first.");
  process.exit(1);
}

async function admin(method, route, body) {
  const res = await fetch(`${WORKER_URL}${route}`, {
    method,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${ADMIN_TOKEN}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${route} → HTTP ${res.status}: ${data.error ?? "unknown"}`);
  return data;
}

for (const id of ["timbre1", "timbre2"]) {
  const dir = path.join(PACK_DIR, id);
  const manifest = JSON.parse(fs.readFileSync(path.join(dir, "voice.json"), "utf8"));
  const wav = fs.readFileSync(path.join(dir, manifest.reference ?? "reference.wav"));
  console.log(`cloning ${id} (${manifest.name}, ${(wav.length / 1024).toFixed(0)} KB)…`);
  const result = await admin("POST", "/admin/voices/clone", {
    voiceId: id,
    name: manifest.name,
    audio: wav.toString("base64"),
    audioType: "audio/wav",
    referenceText: manifest.referenceText ?? "",
  });
  console.log(`  ${id}: state=${result.state} ready=${result.ready}`);
  if (!result.ready) {
    console.log(`  note: model not trained yet; re-run later or check GET /admin/voices`);
  }
}

const listed = await admin("GET", "/admin/voices");
console.log("\nglobal voices now:", listed.voices.map((v) => `${v.voiceId}(${v.state})`).join(", "));
console.log("done. Devices can now synthesize with timbre1/timbre2 without any upload.");