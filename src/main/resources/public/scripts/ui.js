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
  if (AV.sound) AV.sound("error");
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

/* The sounds of a call: the incoming ringtone is the preserved song, and other call signals use contextual MP3 audio. */
AV.ringtone = (function () {
  const SONG = "/assets/ringtone.mp3";
  const PREVIEW_MS = 6000;
  const TONES = {
    waiting: "/assets/audio/calls/call-waiting.mp3",
    outgoing: "/assets/audio/calls/call-waiting.mp3",
    busy: "/assets/audio/calls/call-busy.mp3",
  };
  let playing = null;
  let song = null;
  let toneAudio = null;

  function playSong() {
    if (!song) {
      song = new Audio(SONG);
      song.loop = true;
      song.preload = "auto";
    }
    const settings = (AV.state && AV.state.settings) || {};
    if (song.setSinkId) song.setSinkId(settings["audio.output"] || "").catch(() => {});
    song.volume = Number(settings["sounds.volume"] ?? 100) / 100;
    song.currentTime = 0;
    song.play().catch((e) => {
      if (e.name !== "AbortError") console.warn("ringtone not played", e);
    });
    playing = {
      kind: "incoming",
      stop() {
        song.pause();
      },
    };
  }

  function playTone(kind) {
    const url = TONES[kind] || TONES.waiting;
    if (!toneAudio) {
      toneAudio = new Audio();
    }
    const settings = (AV.state && AV.state.settings) || {};
    if (toneAudio.setSinkId) toneAudio.setSinkId(settings["audio.output"] || "").catch(() => {});
    toneAudio.src = url;
    toneAudio.loop = (kind !== "busy");
    toneAudio.volume = 0.55 * (Number(settings["sounds.volume"] ?? 100) / 100);
    toneAudio.currentTime = 0;
    toneAudio.play().catch((e) => {
      if (e.name !== "AbortError") console.warn("tone not played", e);
    });
    playing = {
      kind,
      stop() {
        toneAudio.pause();
      },
    };
  }

  function play(kind) {
    if (kind === "incoming") playSong();
    else playTone(kind);
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
    if (AV.state && AV.state.settings && AV.state.settings["sounds.ring"] === "true") play(kind);
  }

  function preview() {
    stop();
    play("incoming");
    setTimeout(() => stop("incoming"), PREVIEW_MS);
  }

  return { start, stop, preview, active: () => (playing ? playing.kind : null) };
})();

/* Standardized, discrete interface sound effects using CC0 audio files. */
AV.sound = (function () {
  const SOUND_FILES = {
    mute: "/assets/audio/interface/mute.mp3",
    unmute: "/assets/audio/interface/unmute.mp3",
    deafen: "/assets/audio/interface/deafen.mp3",
    undeafen: "/assets/audio/interface/undeafen.mp3",
    error: "/assets/audio/interface/error.mp3",
    join: "/assets/audio/calls/call-joined.mp3",
    leave: "/assets/audio/calls/call-ended.mp3",
    peerJoin: "/assets/audio/calls/peer-joined.mp3",
    peerLeave: "/assets/audio/calls/peer-left.mp3",
    message: "/assets/audio/notifications/message-received.mp3",
    streamStart: "/assets/audio/notifications/stream-started.mp3",
  };

  const bufferCache = new Map();

  async function load(ctx, url) {
    if (bufferCache.has(url)) return bufferCache.get(url);
    try {
      const resp = await fetch(url);
      const data = await resp.arrayBuffer();
      const decoded = await ctx.decodeAudioData(data);
      bufferCache.set(url, decoded);
      return decoded;
    } catch (e) {
      return null;
    }
  }

  // Preload sound files in the background once the page has loaded
  window.addEventListener("DOMContentLoaded", () => {
    setTimeout(() => {
      try {
        const ctx = AV.cueContext();
        Object.values(SOUND_FILES).forEach((url) => load(ctx, url));
      } catch (ignored) {}
    }, 1500);
  });

  return function (name) {
    const s = AV.state && AV.state.settings;
    if (!s || s["sounds.enabled"] !== "true" || !SOUND_FILES[name]) return;
    const ctx = AV.cueContext();
    const vol = (Number(s["sounds.volume"] ?? 100) / 100);
    const url = SOUND_FILES[name];

    const playBuf = (buf) => {
      if (!buf) return;
      const src = ctx.createBufferSource();
      src.buffer = buf;
      const gain = ctx.createGain();
      gain.gain.value = vol;
      src.connect(gain);
      gain.connect(ctx.destination);
      src.start();
    };

    if (bufferCache.has(url)) {
      playBuf(bufferCache.get(url));
    } else {
      load(ctx, url).then(playBuf);
    }
  };
})();