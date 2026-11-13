(function () {
  if (window.__bluefinNative) return;
  if (typeof window.__bluefinHitsDisabled !== "boolean") window.__bluefinHitsDisabled = false;

  // BEGIN shared gesture classifier (keep identical in native/bridge.js and native/overlay-stage.js)
  var GESTURE_HOLD_MS = 520;
  var GESTURE_MOVE_SLOP = 10;
  var GESTURE_TAP_MS = 400;
  var GESTURE_TAP_NET = 10;

  function gesturePoint(p) {
    return { x: p.x, y: p.y, t: p.t };
  }
  function gestureHits(b) {
    if (!b || typeof b.layout !== "function") return [];
    var layout = b.layout();
    return layout && layout.hit ? layout.hit : [];
  }
  function gestureOnHead(p, hits) {
    var head = hits && hits.length ? hits[0] : null;
    return !!head && Math.hypot(p.x - head.x, p.y - head.y) <= head.r;
  }
  function createGesture(hooks) {
    hooks = hooks || {};
    var track = null;
    function emit(name, a, b) {
      if (typeof hooks[name] === "function") hooks[name](a, b);
    }
    function stopTimer() {
      if (!track || !track.timer) return;
      clearTimeout(track.timer);
      track.timer = 0;
    }
    function down(p, hits) {
      if (track) return false;
      var start = gesturePoint(p);
      track = {
        start: start, last: start, path: 0, net: 0,
        lifted: false, petting: false, onHead: gestureOnHead(start, hits), timer: 0,
      };
      track.timer = setTimeout(function () {
        if (!track || track.lifted) return;
        track.timer = 0;
        // A head scrub that has already started moving is a pet, never a delayed lift.
        if (track.path > GESTURE_MOVE_SLOP) return;
        track.lifted = true;
        emit("grab", track.start, null);
      }, GESTURE_HOLD_MS);
      return true;
    }
    function move(p) {
      if (!track) return false;
      var at = gesturePoint(p);
      track.path += Math.hypot(at.x - track.last.x, at.y - track.last.y);
      track.last = at;
      track.net = Math.hypot(at.x - track.start.x, at.y - track.start.y);
      if (track.lifted) {
        emit("move", at);
        return true;
      }
      if (track.path > GESTURE_MOVE_SLOP) {
        stopTimer();
        // Only the head pets; a moving contact there never becomes a lift.
        if (track.onHead) {
          track.petting = true;
          emit("pet", at);
          return true;
        }
      }
      return false;
    }
    function up(p, cancel) {
      if (!track) return false;
      var current = track;
      stopTimer();
      var at = gesturePoint(p);
      var dt = at.t - current.start.t;
      if (current.lifted) emit("end", cancel ? "cancel" : "up", at);
      else if (current.petting) emit("end", "leave", at);
      else if (!cancel && dt < GESTURE_TAP_MS && current.net < GESTURE_TAP_NET) emit("poke", current.start, at);
      track = null;
      return true;
    }
    return { down: down, move: move, up: up, active: function () { return !!track; } };
  }
  // END shared gesture classifier

  function safeId(id) {
    return String(id || "").replace(/[^a-z0-9_-]/gi, "");
  }

  function act(id) {
    var word = safeId(id);
    try {
      if (window.bluefin && typeof window.bluefin.do === "function") {
        window.bluefin.do(word);
        return "bluefin";
      }
      var btn = document.querySelector('button[data-word="' + word + '"]');
      if (btn) {
        btn.click();
        return "click";
      }
      var body = window.qa && window.qa.body;
      if (body && typeof body.do === "function") {
        body.do(word);
        return "do";
      }
      return "missing";
    } catch (error) {
      return "error:" + (error && error.message ? error.message : error);
    }
  }

  function reset() {
    try {
      if (window.bluefin && typeof window.bluefin.reset === "function") {
        window.bluefin.reset();
        return "bluefin";
      }
      var btn = document.getElementById("reset");
      if (btn) {
        btn.click();
        return "click";
      }
      if (window.qa && typeof window.qa.reset === "function") {
        window.qa.reset();
        return "qa";
      }
      return "missing";
    } catch (error) {
      return "error:" + (error && error.message ? error.message : error);
    }
  }

  function ready() {
    if (window.__BLUEFIN_READY === true) return true;
    if (window.bluefin && window.bluefin.ready === true) return true;
    if (window.qa && window.qa.ready === true) return true;
    return false;
  }

  function setSound(on) {
    var enabled = !!on;
    window.__bluefinSoundOn = enabled;
    var media = document.querySelectorAll("audio,video");
    for (var i = 0; i < media.length; i++) media[i].muted = !enabled;
    if (window.bluefin && typeof window.bluefin.setSound === "function") {
      window.bluefin.setSound(enabled);
    }
    if (window.qa && typeof window.qa.setSound === "function") {
      window.qa.setSound(enabled);
    }
  }

  // Continuous stage box for the unchanged pack: width is the WebView (≥360 CSS px on
  // phones), height is linearly extrapolated from the anchors S 0.70 -> 220px and
  // S 1.00 -> 290px (S 0.50 -> 173, S 0.85 -> 255, S 1.30 -> 360). The pack's
  // own floorY = stageHeight - 40 stays 40px above the stage bottom. The live
  // scale band is S 0.50–1.30; the anchors are only height math.
  function normalizeScale(value) {
    var named = { small: 0.70, medium: 0.85, large: 1.00 };
    var key = String(value == null ? "" : value).trim().toLowerCase();
    var parsed = Object.prototype.hasOwnProperty.call(named, key) ? named[key] : Number(value);
    if (!isFinite(parsed)) parsed = 0.85;
    return Math.max(0.50, Math.min(1.30, Math.round(parsed * 100) / 100));
  }

  var IN_APP_CHROME_PX = 48;
  var STAGE_FLOOR_PX = 40;

  function viewportHeight() {
    var viewport = Number(window.innerHeight);
    if (!isFinite(viewport) || viewport <= 0) {
      var current = document.querySelector("#stage");
      var parent = current && current.parentElement;
      viewport = parent && parent.clientHeight ? Number(parent.clientHeight) : NaN;
    }
    return viewport;
  }

  function availableStageHeight() {
    var viewport = viewportHeight();
    if (!isFinite(viewport) || viewport <= 0) return NaN;
    // The stage sits above the 40px bottom floor inset.
    return Math.max(1, viewport - STAGE_FLOOR_PX);
  }

  function stageHeight(scale, availableHeight) {
    var s = normalizeScale(scale);
    var desired = Math.round(220 + (s - 0.70) * (70 / 0.30));
    if (typeof availableHeight === "number" && isFinite(availableHeight) && availableHeight > 0) {
      return Math.max(1, Math.min(desired, Math.floor(availableHeight)));
    }
    return desired;
  }

  // The native top bar is 48dp plus the status-bar safe area. Measure the
  // resolved CSS top when possible, except where unit tests have no layout.
  function inAppTopReserve() {
    try {
      if (typeof window.getComputedStyle === "function") {
        var stage = document.querySelector("#stage");
        var top = stage ? parseFloat(window.getComputedStyle(stage).top) : NaN;
        if (isFinite(top) && top > 0) return top;
      }
    } catch (err) {}
    return IN_APP_CHROME_PX;
  }

  // In-app the stage fills what remains below the native top bar. The saved
  // scale and window.__bluefinForceScale are never rewritten by this cap.
  function displayStageHeight(scale) {
    if (window.__bluefinOverlay) return stageHeight(scale, availableStageHeight());
    var viewport = viewportHeight();
    if (!isFinite(viewport) || viewport <= 0) return stageHeight(scale, NaN);
    var remaining = viewport - inAppTopReserve() - STAGE_FLOOR_PX;
    var capped = Math.min(remaining, viewport - STAGE_FLOOR_PX);
    return Math.max(1, Math.floor(capped));
  }

  // Pack chrome is hidden in both hosts. In the overlay, overlay-stage.js owns
  // the fixed full-stage geometry; an in-app fit must never undo those rules.
  var HIDE_CHROME_RULES = [
    "header,#panel,#hint,#status,#events,.notes,.tools,.buttons,",
    "h1,.sub,#title,#ver,#sub,#expressions,#motions,h2,#stage:after",
    "{display:none!important}"
  ].join("");
  var OVERLAY_STAGE_RULES = [
    "#stage{position:fixed!important;left:0!important;top:0!important;right:0!important;",
    "bottom:0!important;width:100%!important;height:100%!important;",
    "min-width:0!important;min-height:0!important;",
    "background:transparent!important;border:0!important}"
  ].join("");
  var IN_APP_STAGE_RULES = [
    "#stage{position:absolute!important;z-index:1;left:0;right:0;",
    "top:calc(" + IN_APP_CHROME_PX + "px + env(safe-area-inset-top, 0px))!important;",
    "bottom:max(" + STAGE_FLOOR_PX + "px, env(safe-area-inset-bottom, 0px))!important;",
    "width:auto!important;min-height:0!important;min-width:100%!important}"
  ].join("");

  function fitStage() {
    var style = document.getElementById("bluefin-native-fit");
    if (!style) {
      style = document.createElement("style");
      style.id = "bluefin-native-fit";
      document.head.appendChild(style);
    }
    var overlay = !!window.__bluefinOverlay;
    var rules = HIDE_CHROME_RULES + (overlay ? OVERLAY_STAGE_RULES : IN_APP_STAGE_RULES);
    style.textContent = rules;
    if (overlay) {
      // overlay-stage.js owns the fixed full-height stage; drop any in-app
      // inline height that a pre-overlay fit may have written.
      var overlayStage = document.querySelector("#stage");
      if (overlayStage && overlayStage.style) overlayStage.style.height = "";
      return;
    }
    // Read the resolved top after the position rule lands, then fill the rest.
    var height = displayStageHeight(window.__bluefinSize);
    style.textContent = rules + "#stage{height:" + height + "px}";
    var stage = document.querySelector("#stage");
    if (stage) stage.style.height = height + "px";
  }

  function desiredRoam() {
    if (window.__bluefinHidden || window.__bluefinRoam === "off") return "off";
    return "free";
  }

  var boundBody = null;
  function applyRoam(body) {
    if (!body || typeof body.set !== "function") return;
    var mode = desiredRoam();
    body.set({ roam: mode });
    boundBody = body;
    if (typeof body.do !== "function" || !body.layout) return;
    try {
      var now = body.layout().mode;
      if (mode === "free" && (!now || now === "idle")) body.do("walk");
      if (mode === "off" && !window.__bluefinAllowRest && (now === "walk" || now === "run" || now === "sit" || now === "sleep")) body.do("stand");
    } catch (err) {}
  }

  function applySize(scale) {
    var s = normalizeScale(scale);
    // These stay the user/saved scale even when the display box is capped.
    window.__bluefinSize = s;
    window.__bluefinForceScale = s;
    fitStage();
    var stage = document.querySelector("#stage");
    if (stage) {
      if (window.__bluefinOverlay) {
        // overlay-stage.js sets the fixed full-height stage geometry.
        stage.style.height = "";
      } else {
        stage.style.height = displayStageHeight(s) + "px";
      }
    }
    window.dispatchEvent(new Event("resize"));
  }

  // Character drawing opacity only. This is deliberately applied to the figure
  // root, never to the WebView or the full-screen overlay window alpha. At 0 the
  // character is invisible, so its hit/gesture surface is disabled too.
  function applyCharacterOpacity(value) {
    var opacity = Number(value);
    if (!isFinite(opacity)) opacity = 1;
    opacity = Math.max(0, Math.min(1, opacity));
    window.__bluefinForceOpacity = opacity;
    window.__bluefinCharacterOpacity = opacity;
    window.__bluefinHitsDisabled = opacity <= 0;
    var root = document.querySelector("#root");
    if (root && root.style) {
      root.style.opacity = String(opacity);
      root.style.pointerEvents = opacity <= 0 ? "none" : "";
    }
    var stage = document.querySelector("#stage");
    if (stage && stage.style) {
      stage.style.pointerEvents = opacity <= 0 ? "none" : "";
    }
    return opacity;
  }

  function applyPrefs(roamOn, scale, hidden, opacity) {
    window.__bluefinHidden = !!hidden;
    window.__bluefinRoam = roamOn ? "free" : "off";
    var stage = document.querySelector("#stage");
    if (stage) stage.style.visibility = hidden ? "hidden" : "visible";
    if (window.qa && typeof window.qa.manual === "function") window.qa.manual(!!hidden);
    applySize(scale);
    applyCharacterOpacity(opacity == null ? window.__bluefinForceOpacity : opacity);
    applyRoam(window.qa && window.qa.body);
  }

  setInterval(function () {
    var body = window.qa && window.qa.body;
    if (body && body !== boundBody) applyRoam(body);
  }, 300);

  // Finger contact always includes pointerdown, but the engine pets only on
  // move-without-press and turns a >6px drag into a lift. Classify first:
  // tap = poke, head scrub = pet, and only a deliberate press-hold can become a lift.
  function installStageGestures() {
    var stage = document.querySelector("#stage");
    if (!stage || stage.dataset.bfGestures === "1") return;
    stage.dataset.bfGestures = "1";
    var activeId = null;
    function petBody() {
      return window.qa && window.qa.body;
    }
    function pt(e) {
      var rect = stage.getBoundingClientRect();
      return { x: e.clientX - rect.left, y: e.clientY - rect.top, t: e.timeStamp };
    }
    var gestures = createGesture({
      grab: function (start) {
        var b = petBody();
        if (!b) return;
        b.pointer("down", start);
        b.pointer("move", { x: start.x + 10, y: start.y + 8, t: start.t + GESTURE_HOLD_MS });
      },
      move: function (at) {
        var b = petBody();
        if (b) b.pointer("move", at);
      },
      pet: function (at) {
        var b = petBody();
        if (b) b.pointer("move", at);
      },
      end: function (type, at) {
        var b = petBody();
        if (b) b.pointer(type, at);
      },
      poke: function (start, end) {
        var b = petBody();
        if (!b) return;
        b.pointer("down", start);
        b.pointer("up", end);
      },
    });
    function onDown(e) {
      if (window.__bluefinHitsDisabled) return;
      var b = petBody();
      if (!b || gestures.active()) return;
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      var p = pt(e);
      activeId = e.pointerId;
      try { stage.setPointerCapture(e.pointerId); } catch (err) {}
      gestures.down(p, gestureHits(b));
    }
    function onMove(e) {
      if (activeId === null || e.pointerId !== activeId) return;
      var b = petBody();
      if (!b) return;
      if (e.cancelable) e.preventDefault();
      e.stopPropagation();
      gestures.move(pt(e));
    }
    function finish(e, cancel) {
      if (activeId === null || e.pointerId !== activeId) return;
      e.stopPropagation();
      var p = pt(e);
      activeId = null;
      gestures.up(p, cancel);
    }
    stage.addEventListener("pointerdown", onDown, true);
    stage.addEventListener("pointermove", onMove, true);
    stage.addEventListener("pointerup", function (e) { finish(e, false); }, true);
    stage.addEventListener("pointercancel", function (e) { finish(e, true); }, true);
  }

  window.addEventListener("error", function (event) {
    if (window.BluefinNative && window.BluefinNative.report) {
      window.BluefinNative.report(String(event.message || "error"));
    }
  });
  window.addEventListener("unhandledrejection", function (event) {
    if (window.BluefinNative && window.BluefinNative.report) {
      window.BluefinNative.report(String(event.reason || "rejection"));
    }
  });

  fitStage();
  installStageGestures();
  (function watchTouches() {
    var seen = 0;
    setInterval(function () {
      var ev = window.qa && window.qa.events;
      var body = window.qa && window.qa.body;
      if (!ev || !body || !window.BluefinNative || !window.BluefinNative.report) return;
      while (seen < ev.length) {
        var item = ev[seen++];
        var reaction = item && item.kind;
        if (["poke", "pet", "grab", "throw", "drop"].indexOf(reaction) < 0) continue;
        var hits = [];
        var mode = "";
        try {
          var layout = body.layout();
          mode = layout.mode || "";
          hits = (layout.hit || []).map(function (h) {
            return Math.round(h.x) + "," + Math.round(h.y) + ",r" + Math.round(h.r);
          });
        } catch (err) {}
        var surface = window.__bluefinOverlay ? "overlay" : "in-app";
        window.BluefinNative.report(
          "touch surface=" + surface +
          " reaction=" + reaction +
          " mode=" + mode +
          " scale=" + (window.__bluefinScale || "") +
          " hits=" + hits.join(";")
        );
      }
    }, 200);
  })();
  window.__bluefinNative = {
    act: act,
    reset: reset,
    ready: ready,
    setSound: setSound,
    fitStage: fitStage,
    applySize: applySize,
    applyPrefs: applyPrefs,
    applyOpacity: applyCharacterOpacity
  };
  window.__bluefinApplySound = setSound;
})();
