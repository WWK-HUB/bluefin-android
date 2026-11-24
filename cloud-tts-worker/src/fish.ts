/**
 * Fish Audio upstream client.
 *
 * Hard rules enforced here:
 *  - every /v1/tts call sends `model: s2.1-pro-free` explicitly (omitting it
 *    would silently bill the paid s2.1-pro),
 *  - the Authorization header is built only from the FISH_API_KEY secret,
 *  - upstream error bodies are never forwarded to clients or logged,
 *  - no code path ever retries with a different (paid) model.
 */

import { encode as msgpackEncode } from "@msgpack/msgpack";
import type { Env } from "./config.ts";
import { FISH_MODEL, upstreamUrl } from "./config.ts";

function b64ToBytes(b64: string): Uint8Array {
  const raw = atob(b64);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

export interface TtsParams {
  text: string;
  speed: number;
  /** Persistent voice model id (used when the account has one). */
  referenceId?: string;
  /**
   * One-shot zero-shot cloning: base64 audio + exact transcript, sent inline
   * with the request. Free with s2.1-pro-free at $0 balance — unlike POST
   * /model, which requires prepaid API credit.
   */
  references?: Array<{ audio: string; text: string }>;
}

export interface UpstreamResult {
  ok: boolean;
  status: number;
  body: ReadableStream<Uint8Array> | null;
  /** Safe classification for the router; never contains upstream text. */
  failure?:
    | "auth"
    | "payment"
    | "bad_request"
    | "not_found"
    | "rate"
    | "server"
    | "timeout"
    | "network";
  /** Upstream message snippet — surfaced ONLY on owner-admin diagnostics. */
  reason?: string;
}

function classify(status: number): UpstreamResult["failure"] {
  if (status === 401 || status === 403) return "auth";
  if (status === 402) return "payment";
  if (status === 400 || status === 422) return "bad_request";
  if (status === 404) return "not_found";
  if (status === 429) return "rate";
  return "server";
}

export async function fishTts(
  env: Env, params: TtsParams, timeoutMs: number, signal?: AbortSignal,
): Promise<UpstreamResult> {
  const payload: Record<string, unknown> = {
    text: params.text,
    format: "mp3",
    mp3_bitrate: 128,
    latency: "balanced",
    normalize: true,
    prosody: { speed: params.speed, volume: 0, normalize_loudness: true },
    temperature: 0.7,
    top_p: 0.7,
  };
  if (params.referenceId) payload.reference_id = params.referenceId;
  // Inline reference audio MUST travel as MessagePack binary: the JSON path
  // does not decode base64 audio (official docs: "serialize the request body
  // with MessagePack" for direct audio upload).
  const inline = params.references?.length
    ? params.references.map((r) => ({ audio: b64ToBytes(r.audio), text: r.text }))
    : null;
  if (inline) payload.references = inline;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(upstreamUrl("/v1/tts"), {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.FISH_API_KEY}`,
        "Content-Type": inline ? "application/msgpack" : "application/json",
        model: FISH_MODEL,
      },
      body: inline ? msgpackEncode(payload) : JSON.stringify(payload),
      signal: signal ? AbortSignal.any([controller.signal, signal]) : controller.signal,
    });
    if (!response.ok) {
      let reason = "";
      try {
        const data = (await response.json()) as { message?: string };
        reason = String(data.message ?? "").slice(0, 200);
      } catch {
        await response.body?.cancel().catch(() => {});
      }
      return { ok: false, status: response.status, body: null, failure: classify(response.status), reason };
    }
    return { ok: true, status: response.status, body: response.body };
  } catch (failure) {
    const aborted = (failure as Error)?.name === "AbortError";
    return {
      ok: false,
      status: aborted ? 504 : 502,
      body: null,
      failure: aborted ? "timeout" : "network",
    };
  } finally {
    clearTimeout(timer);
  }
}

export interface CreatedModel {
  id: string;
  state: string;
}

/**
 * Persistent zero-shot clone: POST /model (multipart). train_mode=fast makes
 * the voice usable almost immediately; visibility=private keeps it visible
 * only to the owner account. Model creation is not a billed operation on the
 * official pricing page — synthesis is what bills, and ours is $0.
 */
export async function fishCreateModel(
  env: Env,
  title: string,
  audio: Uint8Array,
  audioType: string,
  fileName: string,
  transcript: string,
  timeoutMs: number,
): Promise<{ ok: true; model: CreatedModel } | { ok: false; failure: UpstreamResult["failure"]; status: number; reason: string }> {
  const form = new FormData();
  form.append("type", "tts");
  form.append("train_mode", "fast");
  form.append("visibility", "private");
  form.append("title", title);
  if (transcript) form.append("texts", transcript);
  form.append("voices", new Blob([audio], { type: audioType }), fileName);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(upstreamUrl("/model"), {
      method: "POST",
      headers: { Authorization: `Bearer ${env.FISH_API_KEY}` },
      body: form,
      signal: controller.signal,
    });
    if (!response.ok) {
      // The reason snippet is surfaced ONLY on the owner-admin route so the
      // account holder can fix permission issues; device routes never see it.
      let reason = "";
      try {
        const data = (await response.json()) as { message?: string };
        reason = String(data.message ?? "").slice(0, 200);
      } catch {
        await response.body?.cancel().catch(() => {});
      }
      return { ok: false, failure: classify(response.status), status: response.status, reason };
    }
    const data = (await response.json()) as { _id?: string; state?: string };
    if (!data._id) return { ok: false, failure: "server", status: 502, reason: "no model id in response" };
    return { ok: true, model: { id: data._id, state: data.state ?? "created" } };
  } catch (failure) {
    const aborted = (failure as Error)?.name === "AbortError";
    return { ok: false, failure: aborted ? "timeout" : "network", status: aborted ? 504 : 502, reason: "" };
  } finally {
    clearTimeout(timer);
  }
}

/** Reads a model's state; also used to verify a user-supplied reference id exists. */
export async function fishGetModel(
  env: Env,
  modelId: string,
  timeoutMs: number,
): Promise<{ ok: true; state: string } | { ok: false; status: number }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(upstreamUrl(`/model/${modelId}`), {
      headers: { Authorization: `Bearer ${env.FISH_API_KEY}` },
      signal: controller.signal,
    });
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return { ok: false, status: response.status };
    }
    const data = (await response.json()) as { state?: string };
    return { ok: true, state: data.state ?? "trained" };
  } catch {
    return { ok: false, status: 502 };
  } finally {
    clearTimeout(timer);
  }
}

export async function fishDeleteModel(env: Env, modelId: string, timeoutMs: number): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(upstreamUrl(`/model/${modelId}`), {
      method: "DELETE",
      headers: { Authorization: `Bearer ${env.FISH_API_KEY}` },
      signal: controller.signal,
    });
    await response.body?.cancel().catch(() => {});
    return response.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}
