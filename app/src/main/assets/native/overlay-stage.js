(function () {
  if (window.__bluefinOverlay) return;
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

  var restTimer = 0;
  // Set when a wake should hand back to free roaming once the body is still.
  var pendingResume = false;

  function body() {
    return window.qa && window.qa.body;
  }

  function roamMode(value) {
    return value === "off" ? "off" : "free";
  }

  function roamPref() {
    if (window.__bluefinRoamPref === "free" || window.__bluefinRoamPref === "off") {
      return window.__bluefinRoamPref;
    }
    return roamMode(window.__bluefinRoam);
  }

  function rememberRoam() {
    if (window.__bluefinRoam === "free" || window.__bluefinRoam === "off") {
      window.__bluefinRoamPref = window.__bluefinRoam;
    } else {
      window.__bluefinRoamPref = roamPref();
    }
  }

  function restoreRoam() {
    var mode = roamPref();
    window.__bluefinRoam = mode;
    var b = body();
    if (b && typeof b.set === "function") b.set({ roam: mode });
  }

  var restPrefs = false;
  // applyPrefs also carries the user's roam preference, so remember it whenever
  // the native side pushes settings (but not when rest itself forces roam off).
  function watchPrefs() {
    var native = window.__bluefinNative;
    if (!native || typeof native.applyPrefs !== "function" || native.applyPrefs.__bluefinRoamWatched) return;
    var apply = native.applyPrefs;
    var watched = function (roamOn) {
      if (!restPrefs) {
        window.__bluefinRoamPref = roamOn ? "free" : "off";
        if (!roamOn) pendingResume = false;
      }
      return apply.apply(this, arguments);
    };
    watched.__bluefinRoamWatched = true;
    native.applyPrefs = watched;
  }

  function pt(x, y) {
    return { x: x, y: y, t: Date.now() };
  }

  function grab(start, at) {
    var b = body();
    if (!b || typeof b.pointer !== "function") return;
    b.pointer("down", start);
    b.pointer("move", at || { x: start.x + 10, y: start.y + 8, t: start.t + GESTURE_HOLD_MS });
  }

  function forwardMove(at) {
    var b = body();
    if (b && typeof b.pointer === "function") b.pointer("move", at);
  }

  var gestures = createGesture({
    grab: function (start) {
      grab(start, { x: start.x + 10, y: start.y + 8, t: start.t + GESTURE_HOLD_MS });
    },
    move: forwardMove,
    pet: forwardMove,
    end: function (type, at) {
      var b = body();
      if (b && typeof b.pointer === "function") b.pointer(type, at);
    },
    poke: function (start, end) {
      var b = body();
      if (!b || typeof b.pointer !== "function") return;
      b.pointer("down", start);
      b.pointer("up", end);
    },
  });

  function pointer(type, x, y) {
    if (window.__bluefinHitsDisabled) return;
    var b = body();
    if (!b) return;
    if (type === "down") {
      if (gestures.active()) return;
      var start = pt(x, y);
      window.__bluefinAllowRest = false;
      if (window.__bluefinAsleep) {
        window.__bluefinAsleep = "";
        restoreRoam();
        pendingResume = roamPref() === "free";
      }
      gestures.down(start, gestureHits(b));
      return;
    }
    if (type === "move") {
      gestures.move(pt(x, y));
      return;
    }
    if (type === "up" || type === "cancel") {
      gestures.up(pt(x, y), type === "cancel");
    }
  }

  function walkTo(x, run) {
    var b = body();
    if (!b || typeof b.walk !== "function") return;
    // Automatic edge-drag walking must honor the saved roam preference and stay
    // put while resting. Explicit drawer walk/run goes through act() instead.
    if (window.__bluefinAsleep || roamPref() === "off") return;
    var mode = b.layout && b.layout().mode;
    if (mode === "drag" || mode === "air" || mode === "sleep" || mode === "sit" || mode === "dizzy") return;
    b.walk(x, !!run, 0);
  }

  function act(word) {
    var b = body();
    if (!b) return;
    var asleep = !!window.__bluefinAsleep;
    window.__bluefinAllowRest = false;
    window.__bluefinAsleep = "";
    if (asleep) {
      restoreRoam();
      pendingResume = roamPref() === "free";
    }
    if (window.__bluefinNative && window.__bluefinNative.act) window.__bluefinNative.act(word);
    else if (typeof b.do === "function") b.do(word);
  }

  function rest() {
    var b = body();
    if (!b) return;
    pendingResume = false;
    watchPrefs();
    if (!window.__bluefinAsleep) rememberRoam();
    window.__bluefinAllowRest = true;
    window.__bluefinRoam = "off";
    window.__bluefinAsleep = "sitting";
    if (window.__bluefinNative && window.__bluefinNative.applyPrefs) {
      restPrefs = true;
      try {
        window.__bluefinNative.applyPrefs(
          false,
          typeof window.__bluefinForceScale === "number" ? window.__bluefinForceScale : (window.__bluefinSize || 0.85),
          false,
          typeof window.__bluefinForceOpacity === "number" ? window.__bluefinForceOpacity : 1
        );
      } finally {
        restPrefs = false;
      }
    }
    if (typeof b.set === "function") b.set({ roam: "off" });
    if (typeof b.do === "function") b.do("sit");
    clearTimeout(restTimer);
    restTimer = setTimeout(function () {
      if (window.__bluefinAsleep !== "sitting") return;
      var live = body();
      if (!live) return;
      window.__bluefinAsleep = "sleep";
      if (typeof live.do === "function") live.do("sleep");
    }, 1400);
  }

  function setScale(value) {
    var s = Number(value);
    if (!isFinite(s)) s = 0.85;
    s = Math.max(0.50, Math.min(1.30, Math.round(s * 100) / 100));
    window.__bluefinForceScale = s;
    var b = body();
    if (b && typeof b.resize === "function") b.resize();
    publish();
  }

  // Character drawing opacity only; the overlay window alpha stays untouched.
  // At 0 the character is invisible, so its hits are dropped from the overlay
  // publish and gestures are disabled until the user raises opacity again.
  function setOpacity(value) {
    var o = Number(value);
    if (!isFinite(o)) o = 1;
    o = Math.max(0, Math.min(1, o));
    window.__bluefinForceOpacity = o;
    window.__bluefinCharacterOpacity = o;
    window.__bluefinHitsDisabled = o <= 0;
    var root = document.querySelector("#root");
    if (root && root.style) {
      root.style.opacity = String(o);
      root.style.pointerEvents = o <= 0 ? "none" : "";
    }
    var stage = document.querySelector("#stage");
    if (stage && stage.style) stage.style.pointerEvents = o <= 0 ? "none" : "";
  }

  function publish() {
    var b = body();
    if (!b || !b.layout || !window.BluefinOverlay || !window.BluefinOverlay.layout) return;
    var L = b.layout();
    if (pendingResume) {
      if (window.__bluefinAsleep || roamPref() === "off") pendingResume = false;
      else if (L.mode === "idle" && !L.moving) {
        pendingResume = false;
        if (typeof b.do === "function") b.do("walk");
      }
    }
    var stage = document.querySelector("#stage");
    var s = (typeof window.__bluefinForceScale === "number") ? window.__bluefinForceScale : 0.85;
    var minX = 104 * s + 8;
    var maxX = Math.max(minX + 1, (stage ? stage.clientWidth : 0) - 104 * s - 8);
    var floorY = (stage ? stage.clientHeight : 0) - 40;
    var hits = window.__bluefinHitsDisabled ? [] : (L.hit || []).map(function (h) {
      return { x: h.x, y: h.y, r: h.r };
    });
    window.BluefinOverlay.layout(JSON.stringify({
      mode: L.mode || "",
      facing: L.facing > 0 ? 1 : -1,
      x: L.x || 0,
      minX: minX,
      maxX: maxX,
      floorY: floorY,
      hits: hits
    }));
  }

  function fit() {
    var style = document.getElementById("bluefin-overlay-style");
    if (!style) {
      style = document.createElement("style");
      style.id = "bluefin-overlay-style";
      document.head.appendChild(style);
    }
    style.textContent = [
      "html,body{background:transparent!important;overflow:hidden!important;margin:0!important}",
      "main{padding:0!important;height:100%!important;display:block!important;background:transparent!important}",
      "#wrap{height:100%!important;display:block!important;background:transparent!important}",
      "header,#panel,#hint,#events,#stage:after{display:none!important}",
      "#stage{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;",
      "width:100%!important;height:100%!important;min-width:0!important;min-height:0!important;",
      "background:transparent!important;border:0!important}"
    ].join("");
    window.dispatchEvent(new Event("resize"));
  }

  watchPrefs();
  fit();
  setInterval(function () {
    fit();
    publish();
  }, 80);

  window.__bluefinOverlay = {
    pointer: pointer,
    walkTo: walkTo,
    act: act,
    rest: rest,
    setScale: setScale,
    setOpacity: setOpacity,
    publish: publish
  };
})();
