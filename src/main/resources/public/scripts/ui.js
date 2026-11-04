"use strict";
/* Shared helpers: API client, DOM builder, icons, avatars, toasts and modals. */
window.AV = window.AV || {};

AV.api = async function (method, path, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(path, options);
  } catch (e) {
    throw new Error("Sem conexão com o aplicativo.");
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
  if (!response.ok) {
    const error = new Error((data && (data.title || data.message)) || text || "Falha na operação.");
    error.status = response.status;
    throw error;
  }
  return data;
};

AV.el = function (tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === false || value === null || value === undefined) continue;
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "dataset") Object.assign(node.dataset, value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
};

AV.fill = function (node, ...children) {
  node.replaceChildren();
  for (const child of children.flat(3)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return node;
};

AV.icon = function (name, extra) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "ic" + (extra ? " " + extra : ""));
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
};

AV.colorFor = function (id) {
  let hash = 0;
  for (const ch of String(id)) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return "hsl(" + (hash % 360) + " 52% 62%)";
};

AV.avatarVersion = function (id) {
  const s = AV.state;
  if (!s) return "";
  if (s.me && s.me.id === id) return s.me.avatar || "";
  const peer = (s.peers || []).find((p) => p.id === id);
  return peer ? peer.avatar || "" : "";
};

AV.avatar = function (id, name, size, online, unstable) {
  const version = AV.avatarVersion(id);
  const node = AV.el("div", { class: "avatar " + (size || ""), style: "background:" + AV.colorFor(id), "aria-hidden": "true" },
    version ? AV.el("img", { src: "/api/avatar/" + id + "?v=" + version, alt: "", draggable: "false", class: "avatar-img" })
      : (name || "?").trim().charAt(0).toUpperCase());
  if (online !== undefined) node.append(AV.el("span", { class: "presence" + (online ? " on" : "") + (unstable ? " unstable" : "") }));
  return node;
};

AV.toast = function (message, kind) {
  const node = AV.el("div", { class: "toast " + (kind || ""), role: "status" }, message);
  document.getElementById("toasts").append(node);
  setTimeout(() => {
    node.classList.add("leaving");
    setTimeout(() => node.remove(), 220);
  }, kind === "error" ? 6000 : 3600);
};

AV.fail = function (error) {
  AV.toast(error && error.message ? error.message : "Algo deu errado.", "error");
};

AV.modal = function (title, build, actions, onClose) {
  const root = document.getElementById("overlay-root");
  const close = () => {
    if (!overlay.isConnected) return;
    overlay.remove();
    document.removeEventListener("keydown", onKey);
    if (onClose) onClose();
  };
  const onKey = (e) => { if (e.key === "Escape") close(); };
  const body = AV.el("div", { class: "mt-4" });
  build(body, close);
  const footer = AV.el("div", { class: "mt-5 flex justify-end gap-2" },
    (actions || []).map((a) => AV.el("button", {
      class: "btn " + (a.primary ? "btn-primary" : a.danger ? "btn-danger" : ""),
      onclick: () => a.run(close, body),
    }, a.label)));
  const overlay = AV.el("div", { class: "overlay", onmousedown: (e) => { if (e.target === overlay) close(); } },
    AV.el("div", { class: "modal card", role: "dialog", "aria-modal": "true", "aria-label": title },
      AV.el("div", { class: "flex items-center justify-between" },
        AV.el("h2", { class: "text-lg font-bold" }, title),
        AV.el("button", { class: "btn btn-ghost btn-icon", "aria-label": "Fechar", onclick: close }, AV.icon("x"))),
      body, footer));
  document.addEventListener("keydown", onKey);
  root.append(overlay);
  const first = overlay.querySelector("input, textarea, select, button.btn-primary");
  if (first) first.focus();
  return close;
};

AV.confirm = function (title, text, label, danger) {
  return new Promise((resolve) => {
    AV.modal(title, (body) => body.append(AV.el("p", { class: "text-muted" }, text)), [
      { label: "Cancelar", run: (close) => { close(); resolve(false); } },
      { label, primary: !danger, danger: !!danger, run: (close) => { close(); resolve(true); } },
    ]);
  });
};

AV.time = function (ts) {
  return new Date(ts).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
};

AV.day = function (ts) {
  const d = new Date(ts);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86400000);
  if (d.toDateString() === today.toDateString()) return "Hoje";
  if (d.toDateString() === yesterday.toDateString()) return "Ontem";
  return d.toLocaleDateString("pt-BR", { day: "2-digit", month: "long", year: "numeric" });
};

AV.bytes = function (n) {
  if (n >= 1048576) return (n / 1048576).toFixed(1).replace(".", ",") + " MB";
  return Math.max(1, Math.round(n / 1024)) + " KB";
};

AV.debounce = function (fn, ms) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), ms);
  };
};

/* Chromium refuses the first capture of a session with AbortError unless the window is in front, and grants the very same request right after. */
AV.capture = async function (constraints) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await navigator.mediaDevices.getUserMedia(constraints);
    } catch (e) {
      if (e.name !== "AbortError" || attempt >= 3) throw e;
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
  }
};

/* Latency is judged by what a voice call tolerates: under 100 ms it goes unnoticed, past 200 ms people talk over each other. */
AV.pingLevel = (ms) => (ms === null ? "none" : ms <= 100 ? "good" : ms <= 200 ? "mid" : "bad");
AV.pingWord = (ms) => ({ none: "indisponível", good: "boa", mid: "intermediária", bad: "ruim" }[AV.pingLevel(ms)]);

AV.pingBadge = function (ms, text) {
  return AV.el("span", { class: "ping ping-" + AV.pingLevel(ms) },
    AV.el("span", { class: "ping-bars", "aria-hidden": "true" }, AV.el("i"), AV.el("i"), AV.el("i")),
    AV.el("span", { class: "mono" }, text || (ms === null ? "—" : ms + " ms")));
};

AV.cueContext = (function () {
  let ctx = null;
  return function () {
    if (!ctx) ctx = new AudioContext();
    if (ctx.state === "suspended") ctx.resume();
    const s = AV.state && AV.state.settings;
    if (s && ctx.setSinkId) ctx.setSinkId(s["audio.output"] || "").catch(() => {});
    return ctx;
  };
})();

/* The sounds of a call: the ringtone is a song streamed by one audio element, and the softer signals are synthesised into a looping buffer. All stop the instant they are told to. */
AV.ringtone = (function () {
  const SONG = "/assets/ringtone.mp3";
  const PREVIEW_MS = 6000;
  const PATTERNS = {
    waiting: { seconds: 4, gain: 0.45, notes: [[830.61, 0, 0.25], [987.77, 0.2, 0.35]] },
    outgoing: { seconds: 3.4, gain: 0.5, notes: [[440, 0, 1.1], [554.37, 0, 1.1]] },
  };
  let playing = null;
  let song = null;

  function render(ctx, pattern) {
    const buffer = ctx.createBuffer(1, Math.ceil(ctx.sampleRate * pattern.seconds), ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (const [freq, at, length] of pattern.notes) {
      const start = Math.floor(at * ctx.sampleRate);
      const samples = Math.floor(length * ctx.sampleRate);
      for (let i = 0; i < samples && start + i < data.length; i++) {
        const t = i / ctx.sampleRate;
        const envelope = Math.min(1, t / 0.012) * Math.exp(-3.2 * t / length);
        const tone = 0.72 * Math.sin(2 * Math.PI * freq * t) + 0.2 * Math.sin(4 * Math.PI * freq * t) + 0.08 * Math.sin(6 * Math.PI * freq * t);
        data[start + i] += 0.33 * envelope * tone;
      }
    }
    return buffer;
  }

  function playPattern(kind) {
    const ctx = AV.cueContext();
    const pattern = PATTERNS[kind];
    const source = ctx.createBufferSource();
    source.buffer = render(ctx, pattern);
    source.loop = true;
    const gain = ctx.createGain();
    gain.gain.value = pattern.gain * Number(AV.state.settings["sounds.volume"]) / 100;
    source.connect(gain);
    gain.connect(ctx.destination);
    source.start();
    playing = {
      kind,
      stop() {
        gain.gain.setTargetAtTime(0, ctx.currentTime, 0.008);
        source.stop(ctx.currentTime + 0.04);
      },
    };
  }

  function playSong() {
    if (!song) {
      song = new Audio(SONG);
      song.loop = true;
      song.preload = "auto";
    }
    const settings = AV.state.settings;
    if (song.setSinkId) song.setSinkId(settings["audio.output"] || "").catch(() => {});
    song.volume = Number(settings["sounds.volume"]) / 100;
    song.currentTime = 0;
    song.play().catch((e) => {
      // Answering at once pauses the song before it starts, and that is not a failure.
      if (e.name !== "AbortError") console.warn("ringtone not played", e);
    });
    playing = {
      kind: "incoming",
      stop() {
        song.pause();
      },
    };
  }

  function play(kind) {
    if (kind === "incoming") playSong();
    else playPattern(kind);
  }

  function stop(kind) {
    if (!playing || (kind && playing.kind !== kind)) return;
    const current = playing;
    playing = null;
    current.stop();
  }

  function start(kind) {
    if (playing && playing.kind === kind) return;
    stop();
    if (AV.state.settings["sounds.ring"] === "true") play(kind);
  }

  function preview() {
    stop();
    play("incoming");
    setTimeout(() => stop("incoming"), PREVIEW_MS);
  }

  return { start, stop, preview, active: () => (playing ? playing.kind : null) };
})();

/* Short interface cues, synthesised so nothing is downloaded or cached. */
AV.sound = (function () {
  const CUES = {
    mute: [[740, 0, 0.07], [494, 0.08, 0.1]],
    unmute: [[494, 0, 0.07], [740, 0.08, 0.1]],
    deafen: [[587, 0, 0.08], [440, 0.09, 0.08], [330, 0.18, 0.12]],
    undeafen: [[330, 0, 0.08], [440, 0.09, 0.08], [587, 0.18, 0.12]],
    join: [[523, 0, 0.09], [784, 0.1, 0.16]],
    leave: [[784, 0, 0.09], [523, 0.1, 0.16]],
    peerJoin: [[660, 0, 0.12]],
    peerLeave: [[440, 0, 0.12]],
    message: [[880, 0, 0.05], [1175, 0.06, 0.08]],
  };
  return function (name) {
    const s = AV.state && AV.state.settings;
    if (!s || s["sounds.enabled"] !== "true" || !CUES[name]) return;
    const ctx = AV.cueContext();
    const volume = 0.22 * Number(s["sounds.volume"]) / 100;
    const now = ctx.currentTime + 0.01;
    for (const [freq, at, length] of CUES[name]) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0, now + at);
      gain.gain.linearRampToValueAtTime(volume, now + at + 0.012);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + at + length);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + at);
      osc.stop(now + at + length + 0.02);
    }
  };
})();