/**
 * Deployer configuration. Everything here is either a hardcoded policy or a
 * Worker env var set by the owner — no value is ever taken from a client
 * request. The upstream host and model are constants on purpose: a client
 * must not be able to aim this proxy at another vendor or a paid model.
 */

export interface Env {
  GATE: DurableObjectNamespace;
  /** Reference audio blobs for device-uploaded inline voices (base64 text). */
  VOICES: KVNamespace;
  FISH_API_KEY?: string;
  TOKEN_SIGNING_KEY?: string;
  ADMIN_TOKEN?: string;
  MAX_TEXT_CHARS?: string;
  DEVICE_DAILY_CHARS?: string;
  DEVICE_RATE_MAX?: string;
  DEVICE_RATE_WINDOW_MS?: string;
  GLOBAL_CONCURRENCY?: string;
  MAX_DEVICES?: string;
  REGISTER_PER_IP_DAILY?: string;
  MAX_VOICES_PER_DEVICE?: string;
  MAX_AUDIO_BYTES?: string;
  UPSTREAM_TIMEOUT_MS?: string;
  TOKEN_TTL_SECONDS?: string;
  PROBE_MIN_INTERVAL_MS?: string;
}

/** The only upstream host this proxy will ever call. */
export const FISH_HOST = "api.fish.audio";

/**
 * The only model this proxy will ever request. Fish Audio falls back to the
 * PAID s2.1-pro when the header is missing or unrecognized, so the header is
 * always sent explicitly and never derived from user input.
 */
export const FISH_MODEL = "s2.1-pro-free";

export interface Limits {
  maxTextChars: number;
  deviceDailyChars: number;
  deviceRateMax: number;
  deviceRateWindowMs: number;
  globalConcurrency: number;
  maxDevices: number;
  registerPerIpDaily: number;
  maxVoicesPerDevice: number;
  maxAudioBytes: number;
  upstreamTimeoutMs: number;
  tokenTtlSeconds: number;
  probeMinIntervalMs: number;
}

function intVar(raw: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function limits(env: Env): Limits {
  return {
    maxTextChars: intVar(env.MAX_TEXT_CHARS, 500),
    deviceDailyChars: intVar(env.DEVICE_DAILY_CHARS, 20_000),
    deviceRateMax: intVar(env.DEVICE_RATE_MAX, 12),
    deviceRateWindowMs: intVar(env.DEVICE_RATE_WINDOW_MS, 60_000),
    globalConcurrency: intVar(env.GLOBAL_CONCURRENCY, 4),
    maxDevices: intVar(env.MAX_DEVICES, 100),
    registerPerIpDaily: intVar(env.REGISTER_PER_IP_DAILY, 5),
    maxVoicesPerDevice: intVar(env.MAX_VOICES_PER_DEVICE, 20),
    maxAudioBytes: intVar(env.MAX_AUDIO_BYTES, 4 * 1024 * 1024),
    upstreamTimeoutMs: intVar(env.UPSTREAM_TIMEOUT_MS, 30_000),
    tokenTtlSeconds: intVar(env.TOKEN_TTL_SECONDS, 7 * 24 * 3600),
    probeMinIntervalMs: intVar(env.PROBE_MIN_INTERVAL_MS, 60_000),
  };
}

/**
 * Outbound URL policy: https only, and the host must be the one public vendor
 * host this service is allowed to talk to. Loopback, private and reserved
 * addresses are rejected outright — there is no deployer or client override.
 */
export function isAllowedUpstreamHost(host: string): boolean {
  const normalized = host.toLowerCase().replace(/^\[|\]$/g, "");
  if (normalized !== FISH_HOST) return false;
  if (normalized === "localhost" || normalized.endsWith(".localhost")) return false;
  if (normalized === "::1" || normalized.startsWith("127.")) return false;
  if (/^(10\.|192\.168\.|169\.254\.|0\.)/.test(normalized)) return false;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(normalized)) return false;
  return true;
}

export function upstreamUrl(path: string): string {
  if (!isAllowedUpstreamHost(FISH_HOST)) throw new Error("upstream host not allowed");
  return `https://${FISH_HOST}${path}`;
}

/** Same id rule as the app's voice packs: lowercase letters, digits, hyphen. */
export const VOICE_ID_RE = /^[a-z0-9][a-z0-9-]{0,31}$/;
export const DEVICE_ID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const FISH_MODEL_ID_RE = /^[0-9a-f]{24,64}$/;

export function utcDay(nowMs: number): string {
  return new Date(nowMs).toISOString().slice(0, 10);
}