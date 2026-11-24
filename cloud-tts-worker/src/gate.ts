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

import type { Env, Limits } from "./config.ts";
import { DEVICE_ID_RE, DEVICE_RETENTION_MS, VOICE_ID_RE, utcDay } from "./config.ts";

interface DeviceRecord {
  createdAt: number;
  lastSeen: number;
  revoked: boolean;
  voiceCleaned?: boolean;
  name?: string;
}

interface Lease {
  deviceId: string;
  expires: number;
}

interface DeviceLease {
  leaseId: string | null;
  expires: number;
}

export const TTS_LEASE_MS = 120_000;

interface VoiceMapping {
  /** Empty string marks an inline voice whose audio lives in KV. */
  fishModelId: string;
  name: string;
  state: string;
  createdAt: number;
  referenceText?: string;
  audioKey?: string;
  bindingId?: string;
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
  private tail: Promise<unknown> = Promise.resolve();

  private readonly state: DurableObjectState;
  private readonly env: Env;

  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  fetch(request: Request): Promise<Response> {
    // A KV await can interleave separate Durable Object requests. Serialize
    // the full operation so delete, rebind and retention share one order.
    const result = this.tail.then(() => this.handle(request));
    this.tail = result.catch(() => {});
    return result;
  }

  private async handle(request: Request): Promise<Response> {
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
          return await this.deleteVoice(body, now);
        case "/voice/list":
          return await this.listVoices(body, now);
        case "/maintenance/candidates":
          return await this.retentionCandidates(now);
        case "/maintenance/device":
          return await this.retireDevice(String(body.deviceId ?? ""), now);
        case "/maintenance/orphan":
          return await this.checkOrphan(String(body.key ?? ""), body.remove === true, now);
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
    if (!devices[deviceId] && Object.values(devices).filter((device) => !device.revoked).length >= limits.maxDevices) {
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
    const expires = now + TTS_LEASE_MS;
    leases[leaseId] = { deviceId, expires };
    record.lastSeen = now;
    devices[deviceId] = record;
    await this.state.storage.put({ leases, devices });
    return ok({ leaseId, expires });
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
    await this.removeDeviceVoices(deviceId);
    await this.state.storage.delete(`dropped:${deviceId}`);
    devices[deviceId].voiceCleaned = true;
    await this.state.storage.put("devices", devices);
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
    const device = await this.touch({ deviceId }, now);
    const active = await device.json<{ ok: boolean }>();
    if (!active.ok) return Response.json(active);
    const prefix = `dvoice:${deviceId}:`;
    const existing = await this.state.storage.list<VoiceMapping>({ prefix });
    if (!existing.has(`${prefix}${voiceId}`) && existing.size >= limits.maxVoicesPerDevice) {
      return denied("voice_cap");
    }
    const key = `${prefix}${voiceId}`;
    if (typeof body.bindingId === "string" && DEVICE_ID_RE.test(body.bindingId)) {
      const deleted = await this.deletedBindings(deviceId, now);
      if (Object.hasOwn(deleted, body.bindingId)) return denied("voice_removed");
      mapping.bindingId = body.bindingId;
    }
    const audio = typeof body.audio === "string" ? body.audio : null;
    const legacyKey = `audio:${deviceId}:${voiceId}`;
    if (audio !== null) {
      mapping.audioKey = `${legacyKey}:${crypto.randomUUID()}`;
    }
    const previous = existing.get(key);
    const cleanup = [previous?.audioKey ?? legacyKey];
    if (mapping.audioKey) cleanup.push(mapping.audioKey);
    if (audio !== null || mapping.fishModelId) {
      await this.queueAudioCleanup(deviceId, voiceId, cleanup);
    }
    if (audio !== null && mapping.audioKey) await this.env.VOICES.put(mapping.audioKey, audio);
    await this.state.storage.put(key, mapping);
    if (audio !== null || mapping.fishModelId) {
      // The mapping is usable as soon as it commits. The durable queue is
      // retried by retention/maintenance if removing the previous blob fails.
      await this.clearOldAudio(deviceId, voiceId, mapping.audioKey ?? null).catch(() => {});
    }
    return ok();
  }

  private async deleteVoice(body: Record<string, unknown>, now: number): Promise<Response> {
    const voiceId = String(body.voiceId ?? "");
    if (body.scope === "global") {
      await this.state.storage.delete(`gvoice:${voiceId}`);
      return ok();
    }
    const deviceId = String(body.deviceId ?? "");
    const devices = await this.state.storage.get<Record<string, DeviceRecord>>("devices") ?? {};
    if (devices[deviceId]?.revoked) return denied("revoked");
    const mapping = await this.state.storage.get<VoiceMapping>(`dvoice:${deviceId}:${voiceId}`);
    const binding = typeof body.bindingId === "string" && DEVICE_ID_RE.test(body.bindingId) ? body.bindingId : null;
    if (binding) {
      const deleted = await this.deletedBindings(deviceId, now);
      deleted[binding] = now;
      const recent = Object.entries(deleted).sort(([, a], [, b]) => b - a).slice(0, 256);
      await this.state.storage.put(`dropped:${deviceId}`, Object.fromEntries(recent));
      if (mapping?.bindingId && mapping.bindingId !== binding) {
        await this.clearOldAudio(deviceId, voiceId, mapping.audioKey ?? null);
        return ok();
      }
    }
    await this.removeVoice(deviceId, voiceId);
    return ok();
  }

  private async deletedBindings(deviceId: string, now: number): Promise<Record<string, number>> {
    const deleted = await this.state.storage.get<Record<string, number>>(`dropped:${deviceId}`) ?? {};
    return Object.fromEntries(Object.entries(deleted).filter(([, at]) => now - at < 24 * 60 * 60 * 1000));
  }

  private async queueAudioCleanup(deviceId: string, voiceId: string, keys: string[]): Promise<void> {
    const key = `cleanup:${deviceId}:${voiceId}`;
    const pending = await this.state.storage.get<string[]>(key) ?? [];
    await this.state.storage.put(key, [...new Set([...pending, ...keys])]);
  }

  private async clearOldAudio(deviceId: string, voiceId: string, active: string | null): Promise<void> {
    const key = `cleanup:${deviceId}:${voiceId}`;
    const pending = await this.state.storage.get<string[]>(key) ?? [];
    for (const audioKey of pending) {
      if (audioKey !== active) await this.env.VOICES.delete(audioKey);
    }
    await this.state.storage.delete(key);
  }

  private async removeVoice(deviceId: string, voiceId: string): Promise<void> {
    const key = `dvoice:${deviceId}:${voiceId}`;
    const mapping = await this.state.storage.get<VoiceMapping>(key);
    const legacyKey = `audio:${deviceId}:${voiceId}`;
    await this.queueAudioCleanup(deviceId, voiceId, [legacyKey, ...(mapping?.audioKey ? [mapping.audioKey] : [])]);
    await this.state.storage.delete(key);
    await this.clearOldAudio(deviceId, voiceId, null);
  }

  private async removeDeviceVoices(deviceId: string): Promise<void> {
    const voices = new Set<string>();
    for (const prefix of [`dvoice:${deviceId}:`, `cleanup:${deviceId}:`]) {
      const entries = await this.state.storage.list({ prefix });
      for (const key of entries.keys()) voices.add(key.slice(prefix.length));
    }
    for (const voiceId of voices) await this.removeVoice(deviceId, voiceId);
    // Legacy revoked records lost their mappings before KV was removed.
    // This scoped prefix also recovers interrupted uploads and old orphans.
    const page = await this.env.VOICES.list({ prefix: `audio:${deviceId}:`, limit: 20 });
    for (const item of page.keys) await this.env.VOICES.delete(item.name);
    if (!page.list_complete) throw new Error("more voice cleanup pending");
  }

  private async retentionCandidates(now: number): Promise<Response> {
    const devices = await this.state.storage.get<Record<string, DeviceRecord>>("devices") ?? {};
    const expired = Object.entries(devices)
      .filter(([, record]) => record.revoked ? !record.voiceCleaned : record.lastSeen <= now - DEVICE_RETENTION_MS)
      .map(([id]) => id);
    return ok({ expired });
  }

  private async retireDevice(deviceId: string, now: number): Promise<Response> {
    const devices = await this.state.storage.get<Record<string, DeviceRecord>>("devices") ?? {};
    const record = devices[deviceId];
    if (!record || (!record.revoked && record.lastSeen > now - DEVICE_RETENTION_MS)) return ok({ retired: false });
    await this.removeDeviceVoices(deviceId);
    await this.state.storage.delete(`dropped:${deviceId}`);
    if (record.revoked) record.voiceCleaned = true;
    else delete devices[deviceId];
    await this.state.storage.put("devices", devices);
    return ok({ retired: true });
  }

  private async checkOrphan(key: string, remove: boolean, now: number): Promise<Response> {
    const [prefix, deviceId, voiceId, revision, extra] = key.split(":");
    if (prefix !== "audio" || !DEVICE_ID_RE.test(deviceId ?? "") || !VOICE_ID_RE.test(voiceId ?? "") ||
        extra !== undefined || (revision !== undefined && !DEVICE_ID_RE.test(revision))) {
      return ok({ orphan: false });
    }
    const mapping = await this.state.storage.get<VoiceMapping>(`dvoice:${deviceId}:${voiceId}`);
    const active = mapping && !mapping.fishModelId ? mapping.audioKey ?? `audio:${deviceId}:${voiceId}` : null;
    if (active === key) return ok({ orphan: false });
    const devices = await this.state.storage.get<Record<string, DeviceRecord>>("devices") ?? {};
    const record = devices[deviceId];
    const knownUnused = !!mapping || !!record?.revoked || !!record && record.lastSeen <= now - DEVICE_RETENTION_MS;
    if (remove && knownUnused) await this.env.VOICES.delete(key);
    return ok({ orphan: knownUnused, unclassified: !knownUnused });
  }

  private async listVoices(body: Record<string, unknown>, now: number): Promise<Response> {
    const deviceId = String(body.deviceId ?? "");
    const active = await this.touch({ deviceId }, now);
    const status = await active.json<{ ok: boolean }>();
    if (!status.ok) return Response.json(status);
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
    // Pages and the Worker that hosts this object deploy independently.
    // Calls from the previous Pages version carry no lease fields.
    const leaseId = body.leaseId === undefined ? null : String(body.leaseId);
    const expires = leaseId === null ? now + TTS_LEASE_MS : Number(body.expires);
    if (leaseId === "" || !Number.isFinite(expires) || expires <= now) return denied("internal");
    const lease = await this.state.storage.get<DeviceLease | null>("ttsLease");
    // Older releases persisted a boolean with no recovery deadline. A fresh
    // request replaces that record; only a live, owned lease blocks admission.
    if (lease && lease.expires > now) return denied("device_busy");
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
    await this.state.storage.put({ busy: false, ttsLease: { leaseId, expires }, recent: [...fresh, now] });
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
    const lease = await this.state.storage.get<DeviceLease | null>("ttsLease");
    const leaseId = body.leaseId ?? null;
    // A retry or a late completion cannot clear or charge against the next job.
    // Also drain an old Pages request that acquired the pre-upgrade boolean.
    const ownsLease = lease
      ? lease.leaseId === leaseId
      : leaseId === null && await this.state.storage.get<boolean>("busy") === true;
    if (!ownsLease) return ok();
    const chars = Number(body.chars ?? 0);
    const success = body.success === true;
    if (success && chars > 0) {
      const usage = (await this.state.storage.get<DayUsage>("usage")) ?? { day: utcDay(now), chars: 0 };
      const day = utcDay(now);
      const next: DayUsage = usage.day === day ? { day, chars: usage.chars + chars } : { day, chars };
      await this.state.storage.put({ usage: next, busy: false, ttsLease: null });
    } else {
      await this.state.storage.put({ busy: false, ttsLease: null });
    }
    return ok();
  }
}
