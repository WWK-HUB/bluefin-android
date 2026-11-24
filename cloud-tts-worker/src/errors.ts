/**
 * Safe error surface with normalized failure categories.
 *
 * The client's provider manager switches engines based on `category`, which
 * is derived here from the ACTUAL vendor response semantics — never guessed
 * from a raw HTTP status on the device. Responses carry a stable machine code,
 * a category and a short user-facing Chinese message. Upstream bodies, key
 * material, token values and internal paths are never forwarded or logged.
 */

export const CATEGORIES = [
  "TEMPORARY_NETWORK_ERROR",
  "RATE_LIMITED",
  "SERVICE_UNAVAILABLE",
  "QUOTA_EXHAUSTED",
  "MODEL_UNAVAILABLE",
  "AUTH_ERROR",
  "INVALID_VOICE",
  "UNKNOWN_ERROR",
] as const;

export type Category = (typeof CATEGORIES)[number];

export interface SafeError {
  status: number;
  error: string;
  category: Category;
  message: string;
  retryAfterSeconds?: number;
}

export const ERRORS = {
  badRequest: { status: 400, error: "invalid_request", category: "UNKNOWN_ERROR", message: "请求内容不完整或格式不正确。" },
  unauthorized: { status: 401, error: "unauthorized", category: "AUTH_ERROR", message: "设备凭证无效或已过期。" },
  revoked: { status: 403, error: "device_revoked", category: "AUTH_ERROR", message: "此设备的语音服务已被停用。" },
  voiceNotFound: { status: 404, error: "voice_not_ready", category: "INVALID_VOICE", message: "音色尚未就绪，请先在设置中完成音色准备。" },
  voiceInvalid: { status: 400, error: "voice_invalid", category: "INVALID_VOICE", message: "音色无效或尚未训练完成。" },
  textTooLong: { status: 413, error: "text_too_long", category: "UNKNOWN_ERROR", message: "单次朗读文本过长。" },
  audioTooLarge: { status: 413, error: "audio_too_large", category: "UNKNOWN_ERROR", message: "参考音频过大。" },
  rateLimited: { status: 429, error: "rate_limited", category: "RATE_LIMITED", message: "请求过于频繁，请稍后再试。" },
  quotaExceeded: { status: 429, error: "quota_exceeded", category: "QUOTA_EXHAUSTED", message: "今日朗读额度已用完，明天再试。" },
  busy: { status: 429, error: "busy", category: "RATE_LIMITED", message: "语音服务正忙，请稍后再试。" },
  deviceBusy: { status: 429, error: "device_busy", category: "RATE_LIMITED", message: "本设备正在生成语音，请稍等片刻再试。" },
  upstreamBusy: { status: 429, error: "upstream_busy", category: "RATE_LIMITED", message: "语音服务繁忙，请稍后再试。" },
  upstreamError: { status: 502, error: "upstream_error", category: "SERVICE_UNAVAILABLE", message: "语音服务暂不可用，请稍后再试。" },
  upstreamNetwork: { status: 502, error: "upstream_network", category: "TEMPORARY_NETWORK_ERROR", message: "网络波动，语音服务连接失败。" },
  upstreamTimeout: { status: 504, error: "upstream_timeout", category: "TEMPORARY_NETWORK_ERROR", message: "语音生成超时，请稍后再试。" },
  freeModelUnavailable: {
    status: 503,
    error: "free_model_unavailable",
    category: "MODEL_UNAVAILABLE",
    message: "免费语音模型暂不可用，已停止本次朗读。",
  },
  serviceConfig: { status: 502, error: "service_config", category: "AUTH_ERROR", message: "语音服务暂不可用，请稍后再试。" },
  notFound: { status: 404, error: "not_found", category: "UNKNOWN_ERROR", message: "接口不存在。" },
  adminDisabled: { status: 503, error: "admin_disabled", category: "AUTH_ERROR", message: "管理接口未启用。" },
  adminUnauthorized: { status: 401, error: "unauthorized", category: "AUTH_ERROR", message: "管理凭证无效。" },
  registerLimited: { status: 429, error: "register_limited", category: "RATE_LIMITED", message: "注册过于频繁，请明天再试。" },
  deviceCapReached: { status: 503, error: "device_cap", category: "SERVICE_UNAVAILABLE", message: "设备数量已达上限，请联系应用维护者。" },
  voiceCapReached: { status: 429, error: "voice_cap", category: "UNKNOWN_ERROR", message: "音色数量已达上限。" },
} satisfies Record<string, SafeError>;

export function errorResponse(err: SafeError, retryAfterSeconds?: number): Response {
  const body: Record<string, unknown> = {
    error: err.error,
    category: err.category,
    message: err.message,
  };
  const retry = retryAfterSeconds ?? err.retryAfterSeconds;
  if (retry !== undefined) body.retryAfterSeconds = retry;
  const headers: Record<string, string> = { "Content-Type": "application/json; charset=utf-8" };
  if (err.status === 429 && retry !== undefined) headers["Retry-After"] = String(retry);
  return new Response(JSON.stringify(body), { status: err.status, headers });
}

export function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });
}
