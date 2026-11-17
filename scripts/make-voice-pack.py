#!/usr/bin/env python3
"""Cut the bundled voice packs out of the two source recordings.

Each pack is a 3-5 second reference clip plus the word-for-word transcript of
that clip. ZipVoice zero-shot cloning uses the clip as the timbre prompt, so the
transcript must match the audio character by character (full-width punctuation
included) and the clip must stay short: the model fails when the reference mel
is longer than the synthesised frames.

The source wavs are 48 kHz 16-bit mono. Output is 24 kHz 16-bit mono, which is
the sample rate the ZipVoice vocoder expects.

Usage:
    python scripts/make-voice-pack.py --check
    python scripts/make-voice-pack.py --source-dir D:/ --out app/src/main/assets/voice-packs
"""

import argparse
import array
import hashlib
import json
import pathlib
import sys
import wave

OUT_SAMPLE_RATE = 24_000
FADE_SECONDS = 0.02

SOURCE_CE96 = "inworld-tts-2_grand-puffin-3495__design-voice-ce960942_11-17-2026 16-37-19.wav"
SOURCE_178D = "inworld-tts-2_grand-puffin-3495__design-voice-178dcf37_11-17-2026 16-28-38.wav"

# The transcript is shared by both clips: each one covers the same two sentences.
REFERENCE_TEXT = "我才没有一直等你呢，只是刚好看了一眼时间而已"

PACKS = [
    {
        "id": "timbre1",
        "name": "音色1",
        "source": SOURCE_CE96,
        "start": 3.38,
        "end": 6.64,
        "about": "偏低的中位基频（约 149 Hz），语气更收着。",
    },
    {
        "id": "timbre2",
        "name": "音色2",
        "source": SOURCE_178D,
        "start": 2.64,
        "end": 5.70,
        "about": "偏亮的中位基频（约 164 Hz），语气更跳。",
    },
]


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        rate, channels, width, frames = (
            w.getframerate(),
            w.getnchannels(),
            w.getsampwidth(),
            w.getnframes(),
        )
        raw = w.readframes(frames)
    if width != 2:
        raise SystemExit(f"{path}: expected 16-bit PCM, got {width * 8}-bit")
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder == "big":
        samples.byteswap()
    if channels == 2:
        samples = array.array(
            "h", [(samples[2 * i] + samples[2 * i + 1]) // 2 for i in range(len(samples) // 2)]
        )
    return [v / 32768.0 for v in samples], rate


def resample(values, source_rate, target_rate):
    if source_rate == target_rate:
        return values
    count = int(len(values) * target_rate / source_rate)
    step = source_rate / target_rate
    out = [0.0] * count
    for i in range(count):
        position = i * step
        base = int(position)
        fraction = position - base
        low = values[base] if base < len(values) else 0.0
        high = values[base + 1] if base + 1 < len(values) else 0.0
        out[i] = low + (high - low) * fraction
    return out


def cut(path, start, end):
    values, rate = read_wav(path)
    segment = values[int(start * rate) : int(end * rate)]
    if not segment:
        raise SystemExit(f"{path}: {start}-{end}s is empty")
    fade = min(int(FADE_SECONDS * rate), len(segment) // 4)
    for i in range(fade):
        gain = i / fade
        segment[i] *= gain
        segment[-1 - i] *= gain
    return resample(segment, rate, OUT_SAMPLE_RATE), OUT_SAMPLE_RATE


def write_wav(path, samples, rate):
    pcm = array.array("h", [max(-32768, min(32767, int(round(v * 32767)))) for v in samples])
    if sys.byteorder == "big":
        pcm.byteswap()
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def build_pack(pack, out_root):
    source = pathlib.Path(pack["source"])
    values, rate = cut(source, pack["start"], pack["end"])
    pack_dir = out_root / pack["id"]
    wav_path = pack_dir / "reference.wav"
    write_wav(wav_path, values, rate)

    manifest = {
        "format": 1,
        "id": pack["id"],
        "name": pack["name"],
        "kind": "zipvoice",
        "reference": "reference.wav",
        "referenceText": REFERENCE_TEXT,
        "sampleRate": rate,
        "speed": 1.0,
        "numSteps": 4,
        "about": pack["about"],
    }
    (pack_dir / "voice.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    digest = hashlib.sha256(wav_path.read_bytes()).hexdigest()
    return wav_path, len(values) / rate, digest, manifest


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--source-dir", default="D:/", help="directory holding the two source wavs")
    parser.add_argument(
        "--out",
        default="app/src/main/assets/voice-packs",
        help="output root for the bundled packs",
    )
    parser.add_argument("--check", action="store_true", help="only report what would be written")
    args = parser.parse_args()

    for pack in PACKS:
        pack["source"] = str(pathlib.Path(args.source_dir) / pack["source"])

    out_root = pathlib.Path(args.out)
    for pack in PACKS:
        source = pathlib.Path(pack["source"])
        if not source.is_file():
            raise SystemExit(f"missing source wav: {source}")
        if args.check:
            print(
                f"{pack['id']}  {pack['end'] - pack['start']:5.2f}s  "
                f"{pack['start']}-{pack['end']}s  -> {out_root / pack['id']}"
            )
            continue
        wav_path, seconds, digest, _manifest = build_pack(pack, out_root)
        print(
            f"{pack['id']}  {seconds:5.2f}s  {wav_path}  "
            f"{wav_path.stat().st_size} B  sha256 {digest[:16]}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
