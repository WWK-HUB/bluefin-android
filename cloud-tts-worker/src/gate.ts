/**
 * Gate — the single Durable Object class behind all stateful guards.
 *
 * Two instantiation modes, both addressed through plain fetch RPC:
 *  - idFromName("registry"): device registry, per-IP registration caps,
 *    global concurrency leases, global + per-device voice mappings.
 *  - idFromName("device:<id>"): per-device sliding-window rate limit,
 *    daily character quota and single-flight concurrency.
 *
 * Durable Objects serialize requests per instance, so counters need no locks.
 * SQLite-backed storage is included in the Workers free plan.
 */

import type { Limits } from "./config.ts";
import { utcDay } from "./config.ts";

interface DeviceRecord {
  createdAt: number;
  lastSeen: number;
  revoked: boolean;
  name?: string;
}

interface Lease {
  deviceId: string;
  expires: number;
}

interface VoiceMapping {
  /** Empty string marks an inline voice whose audio lives in KV. */
  fishModelId: string;
  name: string;
  state: string;
  createdAt: number;
  referenceText?: string;
}

interface DayUsage {
  day: string;
  chars: number;
}

function ok(value: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ ok: true, ...value }), {
    headers: { "Content-Type": "application/json" },
  });
}

function denied(error: string, extra: Record<string, unknown> = {}): Response {
  return new Response(JSON.stringify({ ok: false, error, ...extra }), {
    status: 200, // RPC-level result; the router maps it to the HTTP status.
    headers: { "Content-Type": "application/json" },
  });
}

export class Gate implements DurableObject {
  private state: DurableObjectState;

  constructor(state: DurableObjectState) {
    this.state = state;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const body = request.method === "POST" ? await request.json<Record<string, unknown>>() : {};
    const limits = body.limits as Limits;
    const now = Date.now();
    try {
      switch (url.pathname) {
        case "/register":
          return await this.register(body, limits, now);
        case "/touch":
          return await this.touch(body, now);
        case "/acquire":
          return await this.acquire(body, limits, now);
        case "/release":
          return await this.release(body);
        case "/check":
          return await this.check(body, limits, now);
        case "/commit":
          return await this.commit(body, now);
        case "/probe-check":
          return await this.probeCheck(limits, now);
        case "/voice/first-global":
          return await this.firstGlobalVoice();
        case "/devices":
          return await this.devices();
        case "/revoke":
          return await this.revoke(body);
        case "/voice/resolve":
          return await this.resolveVoice(body);
        case "/voice/put":
          return await this.putVoice(body, limits, now);
        case "/voice/delete":
          return await this.deleteVoice(body);
        case "/voice/list":
          return await this.listVoices(body);
        default:
          return denied("unknown_route");
      }
    } catch (failure) {
      // Type only: storage internals never reach logs or responses.
      console.error(JSON.stringify({ gate: url.pathname, failed: (failure as Error)?.name ?? "Error" }));
      return denied("internal");
    }
  }

  // ---- registry mode ----------------------------------------------------

  private async register(body: Record<string, unknown>, limits: Limits, now: number): Promise<Response> {
    const deviceId = String(body.deviceId ?? "");
    const ip = String(body.ip ?? "unknown");
    const devices = (await this.state.storage.get<Record<string, DeviceRecord>>("devices")) ?? {};
    if (devices[deviceId]?.revoked) return denied("revoked");
    if (!devices[deviceId] && Object.keys(devices).length >= limits.maxDevices) {
      return denied("device_cap");
    }
    const ipKey = `ipreg:${ip}:${utcDay(now)}`;
    const ipCount = (await this.state.storage.get<number>(ipKey)) ?? 0;
    if (!devices[deviceId] && ipCount >= limits.registerPerIpDaily) return denied("register_limited");
    if (!devices[deviceId]) {
      devices[deviceId] = {
        createdAt: now,
        lastSeen: now,
        revoked: false,
        name: typeof body.name === "string" ? body.name.slice(0, 40) : undefined,
      };
      await this.state.storage.put("devices", devices);
      await this.state.storage.put(ipKey, ipCount + 1);
    } else {
      devices[deviceId].lastSeen = now;
      await this.state.storage.put("devices", devices);
    }
    return ok({ registered: true });
  }

  private async touch(body: Record<string, unknown>, now: number): Promise<Response> {
    const deviceId = String(body.deviceId ?? "");
    const devices = (await this.state.storage.get<Record<string, DeviceRecord>>("devices")) ?? {};
    const record = devices[deviceId];
    if (!record) return denied("unknown_device");
    if (record.revoked) return denied("revoked");
    record.lastSeen = now;
    await this.state.storage.put("devices", devices);
    return ok();
  }

  private async acquire(body: Record<string, unknown>, limits: Limits, now: number): Promise<Response> {
    const deviceId = String(body.deviceId ?? "");
    const devices = (await this.state.storage.get<Record<string, DeviceRecord>>("devices")) ?? {};
    const record = devices[deviceId];
    if (!record) return denied("unknown_device");
    if (record.revoked) return denied("revoked");
    const leases = (await this.state.storage.get<Record<string, Lease>>("leases")) ?? {};
    // Self-heal: drop leases whose worker died mid-stream.
    for (const [id, lease] of Object.entries(leases)) {
      if (lease.expires <= now) delete leases[id];
    }
    if (Object.keys(leases).length >= limits.globalConcurrency) {
      await this.state.storage.put("leases", leases);
      return denied("global_busy");
    }
    const leaseId = `${deviceId}:${now}:${Math.random().toString(36).slice(2, 8)}`;
    leases[leaseId] = { deviceId, expires: now + 90_000 };
    record.lastSeen = now;
    devices[deviceId] = record;
    await this.state.storage.put({ leases, devices });
    return ok({ leaseId });
  }

  private async release(body: Record<string, unknown>): Promise<Response> {
    const leaseId = String(body.leaseId ?? "");
    const leases = (await this.state.storage.get<Record<string, Lease>>("leases")) ?? {};
    if (leases[leaseId]) {
      delete leases[leaseId];
      await this.state.storage.put("leases", leases);
    }
    return ok();
  }

  private async devices(): Promise<Response> {
    const devices = (await this.state.storage.get<Record<string, DeviceRecord>>("devices")) ?? {};
    const leases = (await this.state.storage.get<Record<string, Lease>>("leases")) ?? {};
    return ok({ devices, inFlight: Object.keys(leases).length });
  }

  private async revoke(body: Record<string, unknown>): Promise<Response> {
    const deviceId = String(body.deviceId ?? "");
    const devices = (await this.state.storage.get<Record<string, DeviceRecord>>("devices")) ?? {};
    if (!devices[deviceId]) return denied("unknown_device");
    devices[deviceId].revoked = true;
    await this.state.storage.put("devices", devices);
    // Drop the device's voice mappings so a revoked device leaves no usable state.
    const prefix = `dvoice:${deviceId}:`;
    const entries = await this.state.storage.list({ prefix });
    for (const key of entries.keys()) await this.state.storage.delete(key);
    return ok();
  }

  private async resolveVoice(body: Record<string, unknown>): Promise<Response> {
    const voiceId = String(body.voiceId ?? "");
    const deviceId = String(body.deviceId ?? "");
    const global = await this.state.storage.get<VoiceMapping>(`gvoice:${voiceId}`);
    if (global) return ok({ scope: "global", ...global });
    const own = await this.state.storage.get<VoiceMapping>(`dvoice:${deviceId}:${voiceId}`);
    if (own) return ok({ scope: "device", ...own });
    return denied("voice_not_found");
  }

  private async putVoice(body: Record<string, unknown>, limits: Limits, now: number): Promise<Response> {
    const voiceId = String(body.voiceId ?? "");
    const mapping: VoiceMapping = {
      fishModelId: String(body.fishModelId ?? ""),
      name: String(body.name ?? "").slice(0, 40),
      state: String(body.state ?? "trained"),
      createdAt: now,
      referenceText: typeof body.referenceText === "string" ? body.referenceText.slice(0, 200) : undefined,
    };
    if (body.scope === "global") {
      await this.state.storage.put(`gvoice:${voiceId}`, mapping);
      return ok();
    }
    const deviceId = String(body.deviceId ?? "");
    const prefix = `dvoice:${deviceId}:`;
    const existing = await this.state.storage.list({ prefix });
    if (!existing.has(`${prefix}${voiceId}`) && existing.size >= limits.maxVoicesPerDevice) {
      return denied("voice_cap");
    }
    await this.state.storage.put(`${prefix}${voiceId}`, mapping);
    return ok();
  }

  private async deleteVoice(body: Record<string, unknown>): Promise<Response> {
    const voiceId = String(body.voiceId ?? "");
    if (body.scope === "global") {
      await this.state.storage.delete(`gvoice:${voiceId}`);
      return ok();
    }
    const deviceId = String(body.deviceId ?? "");
    const key = `dvoice:${deviceId}:${voiceId}`;
    const mapping = await this.state.storage.get<VoiceMapping>(key);
    await this.state.storage.delete(key);
    return ok({ fishModelId: mapping?.fishModelId ?? "" });
  }

  private async listVoices(body: Record<string, unknown>): Promise<Response> {
    const deviceId = String(body.deviceId ?? "");
    const globals = await this.state.storage.list<VoiceMapping>({ prefix: "gvoice:" });
    const own = await this.state.storage.list<VoiceMapping>({ prefix: `dvoice:${deviceId}:` });
    const voices: Array<Record<string, unknown>> = [];
    for (const [key, value] of globals) {
      voices.push({ voiceId: key.slice("gvoice:".length), scope: "global", name: value.name, state: value.state });
    }
    for (const [key, value] of own) {
      voices.push({
        voiceId: key.slice(`dvoice:${deviceId}:`.length),
        scope: "device",
        name: value.name,
        state: value.state,
      });
    }
    return ok({ voices });
  }

  // ---- per-device mode --------------------------------------------------

  private async check(body: Record<string, unknown>, limits: Limits, now: number): Promise<Response> {
    if (await this.state.storage.get<boolean>("busy")) return denied("device_busy");
    const window = (await this.state.storage.get<number[]>("recent")) ?? [];
    const fresh = window.filter((t) => now - t < limits.deviceRateWindowMs);
    if (fresh.length >= limits.deviceRateMax) {
      const retryMs = limits.deviceRateWindowMs - (now - fresh[0]);
      return denied("rate_limited", { retryAfterSeconds: Math.max(1, Math.ceil(retryMs / 1000)) });
    }
    const usage = (await this.state.storage.get<DayUsage>("usage")) ?? { day: utcDay(now), chars: 0 };
    if (usage.day === utcDay(now) && usage.chars >= limits.deviceDailyChars) {
      return denied("quota_exceeded");
    }
    await this.state.storage.put({ busy: true, recent: [...fresh, now] });
    return ok();
  }

  /** Health probes are rate-limited separately so they never eat TTS quota. */
  private async probeCheck(limits: Limits, now: number): Promise<Response> {
    const last = (await this.state.storage.get<number>("lastProbe")) ?? 0;
    if (now - last < limits.probeMinIntervalMs) {
      return denied("rate_limited", {
        retryAfterSeconds: Math.max(1, Math.ceil((limits.probeMinIntervalMs - (now - last)) / 1000)),
      });
    }
    await this.state.storage.put("lastProbe", now);
    return ok();
  }

  private async firstGlobalVoice(): Promise<Response> {
    const globals = await this.state.storage.list<VoiceMapping>({ prefix: "gvoice:", limit: 1 });
    for (const [key, value] of globals) {
      return ok({ voiceId: key.slice("gvoice:".length), ...value });
    }
    return denied("voice_not_found");
  }

  private async commit(body: Record<string, unknown>, now: number): Promise<Response> {
    const chars = Number(body.chars ?? 0);
    const success = body.success === true;
    if (success && chars > 0) {
      const usage = (await this.state.storage.get<DayUsage>("usage")) ?? { day: utcDay(now), chars: 0 };
      const day = utcDay(now);
      const next: DayUsage = usage.day === day ? { day, chars: usage.chars + chars } : { day, chars };
      await this.state.storage.put("usage", next);
    }
    await this.state.storage.put("busy", false);
    return ok();
  }
}