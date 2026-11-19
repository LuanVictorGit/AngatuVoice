"use strict";
/*
 * The sound wheel: eight short sounds played into the call, so everyone in it hears them. Right Shift held opens the wheel
 * (the app draws it over games, this file draws it inside the window), the mouse or the keys 1 to 8 pick, letting go plays.
 * The original sounds are recordings in the public domain or under open licences; any slot can take an audio file of the
 * person's own instead.
 */
(function () {
  const SLOTS = 8;
  const HOLD_MS = 180;
  const COOLDOWN_MS = 1200;
  const PEAK = 0.7;

  const sb = AV.soundboard = {};
  const buffers = new Map();
  let custom = new Set();
  let customLoaded = null;
  let lastPlayed = 0;

  /* ---- the original sounds: recordings under public licences, credited in assets/audio/LICENSES.md and in About ---- */

  const ORIGINALS = ["pato.wav", "fracasso.wav", "game-over.ogg", "ba-dum-tss.wav", "aplausos.wav", "berinjela.wav", "perdedor.ogg", "vitoria.ogg"]
    .map((file) => "/assets/audio/soundboard/" + file);

  function normalise(buffer) {
    for (let c = 0; c < buffer.numberOfChannels; c++) {
      const data = buffer.getChannelData(c);
      let peak = 0;
      for (let i = 0; i < data.length; i++) peak = Math.max(peak, Math.abs(data[i]));
      if (peak > 0) for (let i = 0; i < data.length; i++) data[i] *= PEAK / peak;
    }
    return buffer;
  }

  /* ---- loading ---- */

  sb.names = () => Array.from({ length: SLOTS }, (_, i) => (AV.state.settings || {})["soundboard." + i] || "Som " + (i + 1));
  sb.isCustom = (slot) => custom.has(slot);

  sb.reloadCustom = function () {
    buffers.clear();
    customLoaded = AV.api("GET", "/api/soundboard").then((r) => { custom = new Set(r.custom || []); }).catch(() => { custom = new Set(); });
    return customLoaded;
  };

  async function buffer(slot) {
    if (!customLoaded) sb.reloadCustom();
    await customLoaded;
    if (buffers.has(slot)) return buffers.get(slot);
    const res = await fetch(custom.has(slot) ? "/api/soundboard/" + slot : ORIGINALS[slot], { cache: "no-store" });
    if (!res.ok) throw new Error("Som indisponível.");
    const made = normalise(await AV.cueContext().decodeAudioData(await res.arrayBuffer()));
    buffers.set(slot, made);
    return made;
  }

  /* Into the call: everyone in it hears it, even with the microphone muted. */
  sb.play = async function (slot) {
    if (!(slot >= 0 && slot < SLOTS)) return;
    if (!AV.rtc || !AV.rtc.room) { AV.toast("Entre numa chamada para tocar sons.", "error"); return; }
    const now = performance.now();
    if (now - lastPlayed < COOLDOWN_MS) return;
    lastPlayed = now;
    try {
      AV.rtc.playFx(await buffer(slot));
    } catch (e) {
      AV.toast("Não foi possível tocar este som.", "error");
    }
  };

  /* Only for you, from the settings. */
  sb.preview = async function (slot) {
    try {
      const ctx = AV.cueContext();
      const src = ctx.createBufferSource();
      src.buffer = await buffer(slot);
      src.connect(ctx.destination);
      src.start();
    } catch (e) {
      AV.toast("Não foi possível tocar este som.", "error");
    }
  };

  AV.on && AV.on("soundboard.play", ({ slot }) => sb.play(Number(slot)));

  /* ---- the wheel inside the window ---- */

  let armedAt = 0;
  let wheel = null;

  function enabled() {
    const s = AV.state && AV.state.settings;
    return !!s && s["soundboard.enabled"] === "true" && !!(AV.rtc && AV.rtc.room);
  }

  function openWheel(x, y) {
    const names = sb.names();
    const size = 400;
    const outer = 180;
    const inner = 64;
    const c = size / 2;
    const step = (Math.PI * 2) / SLOTS;
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 " + size + " " + size);
    svg.setAttribute("class", "sound-wheel-svg");
    const slices = [];
    for (let i = 0; i < SLOTS; i++) {
      const a0 = i * step - Math.PI / 2 - step / 2 + 0.01;
      const a1 = a0 + step - 0.02;
      const p = (r, a) => (c + Math.cos(a) * r).toFixed(1) + " " + (c + Math.sin(a) * r).toFixed(1);
      const path = document.createElementNS(svg.namespaceURI, "path");
      path.setAttribute("d", "M " + p(inner, a0) + " L " + p(outer, a0) + " A " + outer + " " + outer + " 0 0 1 " + p(outer, a1)
        + " L " + p(inner, a1) + " A " + inner + " " + inner + " 0 0 0 " + p(inner, a0) + " Z");
      path.setAttribute("class", "sound-slice");
      path.addEventListener("click", () => { choose(i); closeWheel(true); });
      svg.append(path);
      const mid = i * step - Math.PI / 2;
      const r = (outer + inner) / 2;
      const key = document.createElementNS(svg.namespaceURI, "text");
      key.setAttribute("x", (c + Math.cos(mid) * r).toFixed(1));
      key.setAttribute("y", (c + Math.sin(mid) * r - 12).toFixed(1));
      key.setAttribute("class", "sound-key");
      key.textContent = String(i + 1);
      const label = document.createElementNS(svg.namespaceURI, "text");
      label.setAttribute("x", (c + Math.cos(mid) * r).toFixed(1));
      label.setAttribute("y", (c + Math.sin(mid) * r + 6).toFixed(1));
      label.setAttribute("class", "sound-label");
      label.textContent = names[i];
      svg.append(key, label);
      slices.push([path, key, label]);
    }
    const hint = document.createElementNS(svg.namespaceURI, "text");
    hint.setAttribute("x", c);
    hint.setAttribute("y", c + 4);
    hint.setAttribute("class", "sound-hint");
    hint.textContent = "Escolha um som";
    svg.append(hint);
    const node = AV.el("div", { class: "sound-wheel", role: "dialog", "aria-label": "Painel de sons" }, svg);
    document.body.append(node);
    wheel = { node, slices, hint, chosen: -1 };
    aim(x, y);
  }

  function choose(i) {
    if (!wheel || wheel.chosen === i) return;
    wheel.chosen = i;
    wheel.slices.forEach((parts, n) => parts.forEach((el) => el.classList.toggle("on", n === i)));
    wheel.hint.textContent = "Solte para tocar";
  }

  function closeWheel(play) {
    if (!wheel) return;
    const slot = wheel.chosen;
    wheel.node.remove();
    wheel = null;
    if (play && slot >= 0) sb.play(slot);
  }

  let pointer = { x: window.innerWidth / 2, y: window.innerHeight / 2 };
  /* Like Rust: the slice the cursor is on, or the one it points at from the centre; the middle circle chooses nothing. */
  function aim(x, y) {
    if (!wheel) return;
    const box = wheel.node.querySelector("svg").getBoundingClientRect();
    const dx = x - (box.left + box.width / 2);
    const dy = y - (box.top + box.height / 2);
    const dead = (box.width / 400) * 64;
    if (dx * dx + dy * dy < dead * dead) return;
    const angle = Math.atan2(dy, dx) + Math.PI / 2;
    choose(((Math.round(angle / ((Math.PI * 2) / SLOTS)) % SLOTS) + SLOTS) % SLOTS);
  }

  window.addEventListener("pointermove", (e) => {
    pointer = { x: e.clientX, y: e.clientY };
    aim(e.clientX, e.clientY);
  }, { passive: true });

  window.addEventListener("keydown", (e) => {
    if (wheel) {
      const n = /^Digit[1-8]$/.test(e.code) ? Number(e.code.slice(5)) : /^Numpad[1-8]$/.test(e.code) ? Number(e.code.slice(6)) : 0;
      if (n) { e.preventDefault(); e.stopPropagation(); choose(n - 1); return; }
      if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); closeWheel(false); return; }
    }
    if (e.code === "ShiftRight") {
      if (!e.repeat && !armedAt && enabled()) {
        const at = armedAt = performance.now();
        setTimeout(() => { if (armedAt === at && !wheel) openWheel(pointer.x, pointer.y); }, HOLD_MS);
      }
      return;
    }
    // Any other key while Shift is down means typing a capital, not opening the wheel.
    if (!wheel) armedAt = 0;
  }, { capture: true });

  window.addEventListener("keyup", (e) => {
    if (e.code !== "ShiftRight") return;
    armedAt = 0;
    closeWheel(true);
  }, { capture: true });

  window.addEventListener("blur", () => { armedAt = 0; closeWheel(false); });
})();
