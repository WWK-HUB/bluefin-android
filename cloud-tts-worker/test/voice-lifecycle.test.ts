import assert from "node:assert/strict";
import { test } from "node:test";
import { Gate } from "../src/gate.ts";
import { DEVICE_RETENTION_MS } from "../src/config.ts";

const DEVICE = "55dc3867-5986-4c92-a02d-2c6366a0057f";
const VOICE = "personal";
const BLOB = `audio:${DEVICE}:${VOICE}`;

function fixture(lastSeen: number, now: number) {
  const storage = new Map<string, unknown>([
    ["devices", { [DEVICE]: { createdAt: 1, lastSeen, revoked: false } }],
    [`dvoice:${DEVICE}:${VOICE}`, { fishModelId: "", state: "trained" }],
  ]);
  const audio = new Set([BLOB]);
  let fail = false;
  const gate = new Gate({
    storage: {
      get: async (key: string) => structuredClone(storage.get(key)),
      put: async (key: string, value: unknown) => storage.set(key, structuredClone(value)),
      delete: async (key: string) => storage.delete(key),
      list: async ({ prefix }: { prefix: string }) => new Map([...storage].filter(([key]) => key.startsWith(prefix))),
    },
  } as unknown as DurableObjectState, {
    VOICES: {
      delete: async (key: string) => {
        if (fail) throw new Error("KV temporarily unavailable");
        audio.delete(key);
      },
      list: async ({ prefix }: { prefix: string }) => ({
        keys: [...audio].filter((key) => key.startsWith(prefix)).map((name) => ({ name })),
        list_complete: true,
      }),
    },
  } as unknown as ConstructorParameters<typeof Gate>[1]);
  const request = async (path: string, body = { deviceId: DEVICE, voiceId: VOICE }) => {
    const clock = Date.now;
    Date.now = () => now;
    try {
      return await (await gate.fetch(new Request(`https://test${path}`, {
        method: "POST", body: JSON.stringify(body),
      }))).json();
    } finally {
      Date.now = clock;
    }
  };
  return { request, storage, audio, failWrites: (value: boolean) => { fail = value; } };
}

test("90-day cutoff retires audio and frees the device slot", async () => {
  const now = DEVICE_RETENTION_MS * 2;
  const fresh = fixture(now - DEVICE_RETENTION_MS + 1, now);
  assert.equal((await fresh.request("/maintenance/device")).retired, false);
  assert.ok(fresh.audio.has(BLOB));
  const expired = fixture(now - DEVICE_RETENTION_MS, now);
  assert.deepEqual((await expired.request("/maintenance/candidates")).expired, [DEVICE]);
  assert.equal((await expired.request("/maintenance/device")).retired, true);
  assert.equal(expired.audio.size, 0);
  assert.deepEqual(expired.storage.get("devices"), {});
});

test("failed deletion unpublishes the mapping and retry clears the clip", async () => {
  const f = fixture(1, 2);
  f.failWrites(true);
  assert.equal((await f.request("/voice/delete")).ok, false);
  assert.equal(f.storage.has(`dvoice:${DEVICE}:${VOICE}`), false);
  assert.ok(f.audio.has(BLOB));
  f.failWrites(false);
  assert.equal((await f.request("/voice/delete")).ok, true);
  assert.equal(f.audio.size, 0);
});
