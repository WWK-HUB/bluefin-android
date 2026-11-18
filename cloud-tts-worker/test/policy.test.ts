/**
 * Unit tests for the pure policy modules (no Workers runtime needed).
 * Run: npm test  (Node >= 23.6 strips TypeScript types natively)
 */

import test from "node:test";
import assert from "node:assert/strict";
import { issueToken, verifyToken, bearer } from "../src/auth.ts";
import { composeText, normalizeEmotion, stripDirectives, EMOTIONS } from "../src/emotion.ts";
import { limits, FISH_MODEL, FISH_HOST, isAllowedUpstreamHost, upstreamUrl, VOICE_ID_RE, DEVICE_ID_RE } from "../src/config.ts";
import { ERRORS, CATEGORIES } from "../src/errors.ts";

const SECRET = "test-signing-secret-0123456789abcdef";
const DEVICE = "3f2b8c1e-9d4a-4f6b-8c2e-1a2b3c4d5e6f";

test("token round-trips and verifies", async () => {
  const now = Date.now();
  const { token, expiresAt } = await issueToken(SECRET, DEVICE, 3600, now);
  const claims = await verifyToken(SECRET, token, now);
  assert.ok(claims);
  assert.equal(claims.deviceId, DEVICE);
  assert.equal(claims.expiresAt, expiresAt);
});

test("token rejects wrong secret, tampering and expiry", async () => {
  const now = Date.now();
  const { token } = await issueToken(SECRET, DEVICE, 3600, now);
  assert.equal(await verifyToken("another-secret", token, now), null);
  const parts = token.split(".");
  const forgedPayload = Buffer.from(JSON.stringify({ d: DEVICE, e: Math.floor(now / 1000) + 99999 }))
    .toString("base64url");
  assert.equal(await verifyToken(SECRET, `v1.${forgedPayload}.${parts[2]}`, now), null);
  assert.equal(await verifyToken(SECRET, token, now + 3_601_000), null);
  assert.equal(await verifyToken(SECRET, "garbage", now), null);
  assert.equal(await verifyToken(SECRET, "", now), null);
});

test("bearer extraction never throws", () => {
  const req = (h) => new Request("https://x.test", { headers: h ? { Authorization: h } : {} });
  assert.equal(bearer(req("Bearer abc")), "abc");
  assert.equal(bearer(req("Basic abc")), "");
  assert.equal(bearer(req(null)), "");
});

test("all eight emotions normalize; unknown degrades to neutral", () => {
  for (const e of EMOTIONS) assert.equal(normalizeEmotion(e), e);
  assert.equal(normalizeEmotion("furious"), "neutral");
  assert.equal(normalizeEmotion(undefined), "neutral");
  assert.equal(normalizeEmotion(42), "neutral");
  assert.equal(normalizeEmotion("[angry]"), "neutral");
});

test("emotion cues are prepended, neutral adds nothing", () => {
  assert.equal(composeText("你好呀。", "neutral"), "你好呀。");
  assert.equal(composeText("你好呀。", "happy"), "[happy] 你好呀。");
  assert.ok(composeText("哼。", "tsundere").startsWith("[tsundere"));
});

test("client text can never inject directives", () => {
  assert.equal(stripDirectives("[[emotion:angry]] 你好"), "你好");
  assert.equal(stripDirectives("[whisper] 悄悄话"), "悄悄话");
  assert.equal(stripDirectives("正常文本，没有标记。"), "正常文本，没有标记。");
  const cleaned = stripDirectives("[sad] 我才没有哭呢 [laugh]");
  assert.ok(!cleaned.includes("["));
});

test("model and upstream are the free constants", () => {
  assert.equal(FISH_MODEL, "s2.1-pro-free");
  assert.equal(FISH_HOST, "api.fish.audio");
  assert.equal(upstreamUrl("/v1/tts"), "https://api.fish.audio/v1/tts");
  const cfg = limits({});
  assert.equal(cfg.maxTextChars, 500);
  assert.ok(cfg.globalConcurrency <= 5); // Fish starter tier concurrency
});

test("outbound host policy rejects loopback, private and reserved addresses", () => {
  assert.ok(isAllowedUpstreamHost("api.fish.audio"));
  for (const host of [
    "localhost", "127.0.0.1", "10.0.0.1", "192.168.1.1", "172.16.0.1",
    "169.254.1.1", "0.0.0.0", "::1", "evil.example.com", "api.fish.audio.evil.com",
  ]) {
    assert.ok(!isAllowedUpstreamHost(host), host);
  }
});

test("every error carries one of the eight normalized categories", () => {
  for (const err of Object.values(ERRORS)) {
    assert.ok(CATEGORIES.includes(err.category), `${err.error} has category ${err.category}`);
  }
  assert.equal(ERRORS.upstreamTimeout.category, "TEMPORARY_NETWORK_ERROR");
  assert.equal(ERRORS.upstreamNetwork.category, "TEMPORARY_NETWORK_ERROR");
  assert.equal(ERRORS.quotaExceeded.category, "QUOTA_EXHAUSTED");
  assert.equal(ERRORS.freeModelUnavailable.category, "MODEL_UNAVAILABLE");
  assert.equal(ERRORS.voiceInvalid.category, "INVALID_VOICE");
});

test("id validators match the app rules", () => {
  assert.ok(VOICE_ID_RE.test("timbre1"));
  assert.ok(VOICE_ID_RE.test("my-voice-2"));
  assert.ok(!VOICE_ID_RE.test("../evil"));
  assert.ok(!VOICE_ID_RE.test("UPPER"));
  assert.ok(!VOICE_ID_RE.test("a".repeat(33)));
  assert.ok(DEVICE_ID_RE.test(DEVICE));
  assert.ok(!DEVICE_ID_RE.test("not-a-uuid"));
});

test("402 maps to free-model-unavailable, never a paid fallback", () => {
  assert.equal(ERRORS.freeModelUnavailable.status, 503);
  assert.equal(ERRORS.freeModelUnavailable.error, "free_model_unavailable");
});