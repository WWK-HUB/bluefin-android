/**
 * Device session tokens: short-lived, HMAC-signed credentials.
 *
 * The app registers a random device UUID once and receives a token signed with
 * TOKEN_SIGNING_KEY (a Worker secret). Every TTS call presents the token; the
 * Worker verifies the signature and expiry statelessly, then checks the device
 * registry for revocation. The Fish Audio key is never part of any token.
 *
 * Token format: v1.<base64url(payload)>.<base64url(hmac-sha256)>
 * payload: {"d":"<deviceId>","e":<expiryEpochSeconds>}
 */

const encoder = new TextEncoder();

function b64urlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlDecode(text: string): Uint8Array {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(padded + "=".repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

let cachedKey: CryptoKey | null = null;
let cachedSecret = "";

async function signingKey(secret: string): Promise<CryptoKey> {
  if (cachedKey && cachedSecret === secret) return cachedKey;
  cachedKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
  cachedSecret = secret;
  return cachedKey;
}

async function hmac(secret: string, data: string): Promise<Uint8Array> {
  const key = await signingKey(secret);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(data));
  return new Uint8Array(signature);
}

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export interface TokenClaims {
  deviceId: string;
  expiresAt: number;
}

export async function issueToken(
  secret: string,
  deviceId: string,
  ttlSeconds: number,
  nowMs: number,
): Promise<{ token: string; expiresAt: number }> {
  const expiresAt = Math.floor(nowMs / 1000) + ttlSeconds;
  const payload = b64urlEncode(encoder.encode(JSON.stringify({ d: deviceId, e: expiresAt })));
  const signature = b64urlEncode(await hmac(secret, `v1.${payload}`));
  return { token: `v1.${payload}.${signature}`, expiresAt };
}

/** Returns the claims when the token is well-formed, signed and unexpired. */
export async function verifyToken(
  secret: string,
  token: string,
  nowMs: number,
): Promise<TokenClaims | null> {
  const parts = token.split(".");
  if (parts.length !== 3 || parts[0] !== "v1") return null;
  let expected: Uint8Array;
  try {
    expected = await hmac(secret, `v1.${parts[1]}`);
  } catch {
    return null;
  }
  let presented: Uint8Array;
  let claims: { d?: unknown; e?: unknown };
  try {
    presented = b64urlDecode(parts[2]);
    claims = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[1])));
  } catch {
    return null;
  }
  if (!timingSafeEqual(expected, presented)) return null;
  if (typeof claims.d !== "string" || typeof claims.e !== "number") return null;
  if (claims.e <= Math.floor(nowMs / 1000)) return null;
  return { deviceId: claims.d, expiresAt: claims.e };
}

/** Extracts a Bearer credential without ever logging it. */
export function bearer(request: Request): string {
  const header = request.headers.get("Authorization") ?? "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

/** Stable short hash for logs and Fish model titles; never reversible to the id. */
export async function shortHash(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(text));
  return b64urlEncode(new Uint8Array(digest)).slice(0, 10);
}