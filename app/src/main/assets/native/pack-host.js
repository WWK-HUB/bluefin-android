// Host for an imported Coopanion-compatible figure pack.
// The pack's own entry module is imported from /packs/<id>/ and runs against
// the already-shipped kit. No pack code is bundled with the app; only the
// user's app-private copy is read.
import * as kit from "../bluefin/kit/body.js";

const params = new URLSearchParams(location.search);
const packId = String(params.get("pack") || "");
const bundled = params.get("bundled") === "1";
const requestedScheme = String(params.get("scheme") || "");
const stage = document.getElementById("stage");
const root = document.getElementById("root");
const errorBox = document.getElementById("pack-error");
const events = [];
let body = null;
let stopped = false;
let last = 0;
let soundMap = {};
let packBaseUrl = null;

function report(message) {
  try {
    if (window.BluefinNative && window.BluefinNative.report) {
      window.BluefinNative.report(String(message).slice(0, 300));
    }
  } catch (_) {}
}

function showError(message) {
  if (errorBox) {
    errorBox.textContent = message;
    errorBox.style.display = "block";
  }
  report("pack: " + message);
}

function clamp(value, min, max) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.max(min, Math.min(max, n)) : min;
}

function userScale() {
  return clamp(window.__bluefinForceScale, 0.5, 1.3);
}

function bounds() {
  const s = userScale();
  return {
    W: stage.clientWidth || window.innerWidth,
    H: stage.clientHeight || window.innerHeight,
    floorY: (stage.clientHeight || window.innerHeight) - 40,
    S: 0.42 * s,
  };
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = "anonymous";
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error("image " + url));
    image.src = String(url);
  });
}

const host = {
  root,
  bounds,
  emit(kind, detail) {
    events.push({ kind: String(kind || ""), detail });
    if (events.length > 160) events.splice(0, events.length - 160);
  },
  sound(name, kind) {
    try {
      if (!(window.BluefinNative && window.BluefinNative.soundEnabled && window.BluefinNative.soundEnabled())) return;
      const key = String(name || "");
      const cue = soundMap[key] || soundMap[String(kind || "")];
      if (cue && typeof cue.file === "string" && packBaseUrl) {
        const audio = new Audio(new URL(cue.file.replace(/\\/g, "/"), packBaseUrl).href);
        const volume = Number(cue.volume);
        audio.volume = Number.isFinite(volume) ? Math.max(0, Math.min(1, volume)) : 1;
        const played = audio.play();
        if (played && typeof played.catch === "function") played.catch(() => {});
        return;
      }
      window.BluefinNative.play(key, String(kind || ""));
    } catch (_) {}
  },
  start: { x: (stage.clientWidth || window.innerWidth) / 2, facing: 1, enter: true },
};

function packBase() {
  if (bundled) {
    const directory = packId === "bluefin-native" ? "bluefin" : "characters/" + encodeURIComponent(packId);
    return new URL("/assets/" + directory + "/", location.origin);
  }
  return new URL("/packs/" + encodeURIComponent(packId) + "/", location.origin);
}

function ready() {
  window.__BLUEFIN_READY = true;
  window.qa = window.qa || {};
  window.qa.ready = true;
  window.qa.events = events;
  window.qa.body = body;
  window.qa.manual = window.qa.manual || function () {};
  window.qa.reset = function () {
    try { if (body && typeof body.do === "function") body.do("stand"); } catch (_) {}
  };
  window.bluefin = {
    ready: true,
    do(word) {
      try {
        if (body && typeof body.do === "function") {
          body.do(String(word || ""));
          return "pack";
        }
      } catch (error) {
        return "error:" + (error && error.message ? error.message : error);
      }
      return "missing";
    },
    reset() {
      window.qa.reset();
      return "pack";
    },
    setSound() {},
  };
}

function tick(now) {
  if (stopped || !body) return;
  if (!last) last = now;
  const dt = Math.max(0, Math.min(0.05, (now - last) / 1000));
  last = now;
  try {
    body.step(dt, true);
  } catch (error) {
    stopped = true;
    showError("角色动作出错：" + (error && error.message ? error.message : error));
    return;
  }
  requestAnimationFrame(tick);
}

async function boot() {
  if (!/^[a-z0-9][a-z0-9-]{0,31}$/.test(packId)) throw new Error("角色包编号无效");
  const base = packBase();
  packBaseUrl = base;
  const response = await fetch(new URL("figure.json", base), { cache: "no-store" });
  if (!response.ok) throw new Error("找不到 figure.json");
  const manifest = await response.json();
  soundMap = manifest.sounds && typeof manifest.sounds === "object" ? manifest.sounds : {};
  const entry = String(manifest.entry || "").replace(/\\/g, "/");
  if (!entry) throw new Error("入口为空");
  const moduleUrl = new URL(entry, base);
  const module = await import(moduleUrl.href);
  const make = module[String(manifest.export || "")];
  if (typeof make !== "function") throw new Error("入口没有导出工厂函数");
  const scheme = requestedScheme || (Array.isArray(manifest.presets) && manifest.presets[0] && manifest.presets[0].id) ||
    (Array.isArray(manifest.axes) ? manifest.axes.map((axis) => axis.options && axis.options[0] && axis.options[0].id).filter(Boolean).join("-") : "");
  let model;
  if (typeof manifest.model === "string") {
    try {
      model = await (await fetch(new URL(manifest.model.replace(/\\/g, "/"), base), { cache: "no-store" })).json();
    } catch (_) {
      model = undefined;
    }
  }
  body = await make(base, {
    model,
    scheme,
    kit,
    loadImage,
    asset: (path) => new URL(String(path), base),
    host,
  });
  for (const name of ["step", "layout", "do"]) {
    if (!body || typeof body[name] !== "function") throw new Error("角色主体缺少 " + name);
  }
  ready();
  try {
    const canWalk = !(manifest.can && manifest.can.walk === false);
    body.set && body.set({ roam: canWalk ? "free" : "off" });
  } catch (_) {}
  requestAnimationFrame(tick);
}

window.addEventListener("resize", () => {
  try { if (body && typeof body.resize === "function") body.resize(); } catch (_) {}
});
window.addEventListener("error", (event) => report("pack error: " + String(event.message || "error")));
window.addEventListener("unhandledrejection", (event) => report("pack rejection: " + String(event.reason || "rejection")));

boot().catch((error) => {
  stopped = true;
  window.__BLUEFIN_READY = true;
  window.qa = window.qa || { events, body: null, ready: true };
  window.qa.ready = true;
  window.qa.events = events;
  window.qa.body = null;
  showError("角色包加载失败，可切换其他角色。（" + (error && error.message ? error.message : error) + "）");
});
