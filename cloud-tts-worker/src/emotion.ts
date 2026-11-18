/**
 * Emotion handling for Fish Audio S2.1.
 *
 * S2.1 understands natural-language [bracket] cues (official docs: "Natural
 * language control with [bracket] syntax, not limited to a fixed set"). The
 * cue is interpreted as delivery direction, not read aloud. We map the app's
 * eight emotion fields to short, restrained cues here on the server so:
 *  - the client can never inject arbitrary upstream parameters,
 *  - unknown emotions degrade safely to neutral (no cue),
 *  - the base voice (reference_id) is untouched by emotion changes.
 */

export const EMOTIONS = [
  "neutral",
  "happy",
  "excited",
  "shy",
  "tsundere",
  "sad",
  "angry",
  "gentle",
] as const;

export type Emotion = (typeof EMOTIONS)[number];

const CUES: Record<Emotion, string> = {
  neutral: "",
  happy: "[happy]",
  excited: "[excited]",
  shy: "[shy]",
  tsundere: "[tsundere, cute and slightly defiant]",
  sad: "[sad]",
  angry: "[angry]",
  gentle: "[gentle, soft tone]",
};

export function normalizeEmotion(raw: unknown): Emotion {
  return typeof raw === "string" && (EMOTIONS as readonly string[]).includes(raw)
    ? (raw as Emotion)
    : "neutral";
}

/**
 * Strips any bracket directives the model may have emitted on its own
 * ([[emotion:...]] markers and single [cue] tags). They must never reach the
 * synthesizer as client-controlled directions, and must never be read aloud.
 */
export function stripDirectives(text: string): string {
  return text
    .replace(/\[\[[^\]]*\]\]/g, " ")
    .replace(/\[[^\]\n]{0,80}\]/g, " ")
    .replace(/[ \t\u00A0]+/g, " ")
    .trim();
}

/** Server-side composition: fixed cue + cleaned text. Neutral adds nothing. */
export function composeText(text: string, emotion: Emotion): string {
  const cue = CUES[emotion];
  return cue ? `${cue} ${text}` : text;
}