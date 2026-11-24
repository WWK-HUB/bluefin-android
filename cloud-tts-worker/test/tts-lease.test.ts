import assert from "node:assert/strict";
import { test } from "node:test";
import { Gate } from "../src/gate.ts";
import { limits, utcDay } from "../src/config.ts";
import type { Env } from "../src/config.ts";

function deviceGate() {
  const values = new Map<string, unknown>();
  const gate = new Gate({
    storage: {
      get: async (key: string) => structuredClone(values.get(key)),
      put: async (key: string | Record<string, unknown>, value?: unknown) => {
        const entries = typeof key === "string" ? [[key, value] as const] : Object.entries(key);
        for (const [name, data] of entries) values.set(name, structuredClone(data));
      },
    },
  } as unknown as DurableObjectState, {} as Env);
  const rpc = async (path: string, body: Record<string, unknown>) =>
    (await gate.fetch(new Request(`https://gate${path}`, {
      method: "POST",
      body: JSON.stringify({ ...body, limits: limits({} as Env) }),
    }))).json();
  return { values, rpc };
}

test("a legacy busy record no longer blocks speech or erases existing usage", async (t) => {
  const now = Date.UTC(2026, 10, 20);
  t.mock.method(Date, "now", () => now);
  const { values, rpc } = deviceGate();
  values.set("busy", true);
  values.set("usage", { day: utcDay(now), chars: 7 });
  assert.deepEqual(await rpc("/check", { leaseId: "new", expires: now + 120_000 }), { ok: true });
  await rpc("/commit", { success: true, chars: 5 });
  assert.equal(values.get("busy"), false);
  assert.deepEqual(values.get("ttsLease"), { leaseId: "new", expires: now + 120_000 });
  await rpc("/commit", { leaseId: "new", success: false, chars: 5 });
  assert.equal(values.get("ttsLease"), null);
  assert.deepEqual(values.get("usage"), { day: utcDay(now), chars: 7 });
});

test("expired leases recover, and stale or duplicate completions do not release or charge twice", async (t) => {
  let now = Date.UTC(2026, 10, 20);
  t.mock.method(Date, "now", () => now);
  const { values, rpc } = deviceGate();
  const first = { leaseId: "abandoned", expires: now + 120_000 };
  const next = { leaseId: "replacement", expires: first.expires + 120_000 };
  assert.deepEqual(await rpc("/check", first), { ok: true });
  assert.deepEqual(await rpc("/check", next), { ok: false, error: "device_busy" });
  now = first.expires;
  assert.deepEqual(await rpc("/check", next), { ok: true });
  await rpc("/commit", { leaseId: first.leaseId, success: true, chars: 11 });
  assert.deepEqual(values.get("ttsLease"), next);
  assert.equal(values.has("usage"), false);
  const finished = { leaseId: next.leaseId, success: true, chars: 9 };
  await rpc("/commit", finished);
  await rpc("/commit", finished);
  assert.equal(values.get("ttsLease"), null);
  assert.deepEqual(values.get("usage"), { day: utcDay(now), chars: 9 });
});

test("the previous Pages router can finish old and new requests during rollout", async (t) => {
  const now = Date.UTC(2026, 10, 20);
  t.mock.method(Date, "now", () => now);
  const { values, rpc } = deviceGate();
  values.set("busy", true);
  await rpc("/commit", { success: true, chars: 2 });
  assert.deepEqual(values.get("usage"), { day: utcDay(now), chars: 2 });
  assert.deepEqual(await rpc("/check", {}), { ok: true });
  assert.deepEqual(values.get("ttsLease"), { leaseId: null, expires: now + 120_000 });
  assert.deepEqual(await rpc("/check", { leaseId: "new", expires: now + 120_000 }),
    { ok: false, error: "device_busy" });
  await rpc("/commit", { success: true, chars: 3 });
  await rpc("/commit", { success: true, chars: 3 });
  assert.deepEqual(values.get("usage"), { day: utcDay(now), chars: 5 });
  assert.equal(values.get("ttsLease"), null);
});
