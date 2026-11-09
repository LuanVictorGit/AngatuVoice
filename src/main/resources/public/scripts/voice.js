"use strict";
/* Voice messages. Recording: hold the microphone and let go to send, slide left to cancel, slide up to keep recording
   hands-free; a plain click starts hands-free right away. Playing: a waveform drawn from the audio itself, click or drag
   on it to jump, and 1×, 1,5× or 2× speed. Recordings go up as ordinary attachments; the server keeps them like any file. */
(function () {
  const E = AV.el;
  const MAX_MS = 10 * 60 * 1000;
  const HOLD_MS = 350;
  const CANCEL_PX = 110;
  const LOCK_PX = 70;
  const BARS = 44;
  const LIVE_BARS = 48;
  const SPEEDS = [1, 1.5, 2];
  const NAME = "Mensagem de voz";

  const clock = (ms) => {
    const s = Math.max(0, Math.round(ms / 1000));
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  };

  /* ---- recording ---- */

  let rec = null;

  function mimeType() {
    return ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/webm"].find((t) => MediaRecorder.isTypeSupported(t)) || "";
  }

  async function microphone() {
    const device = (AV.state.settings || {})["audio.input"];
    return navigator.mediaDevices.getUserMedia({ audio: {
      deviceId: device ? { exact: device } : undefined, echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 } });
  }

  /* ui: { box, strip, onSend(file, ms) } */
  async function start(ui, locked) {
    if (rec) return;
    rec = { ui, locked, chunks: [], startedAt: 0, pausedFor: 0, pausedAt: 0, cancelled: false, levels: [] };
    const current = rec;
    let stream;
    try {
      stream = await microphone();
    } catch (e) {
      rec = null;
      AV.toast(e && e.name === "NotAllowedError" ? "O acesso ao microfone foi negado." : "Não foi possível usar o microfone.", "error");
      return;
    }
    if (current !== rec || current.cancelled) { stream.getTracks().forEach((t) => t.stop()); return; }
    const type = mimeType();
    current.stream = stream;
    current.recorder = new MediaRecorder(stream, { mimeType: type || undefined, audioBitsPerSecond: 32000 });
    current.type = (current.recorder.mimeType || type || "audio/webm").split(";")[0];
    current.recorder.ondataavailable = (e) => { if (e.data && e.data.size) current.chunks.push(e.data); };
    const ctx = AV.cueContext();
    current.source = ctx.createMediaStreamSource(stream);
    current.analyser = ctx.createAnalyser();
    current.analyser.fftSize = 512;
    current.source.connect(current.analyser);
    current.recorder.start(250);
    current.startedAt = performance.now();
    ui.box.classList.add("recording");
    draw(current);
    current.timer = setInterval(() => { if (elapsed(current) >= MAX_MS) finish(true); }, 250);
  }

  function elapsed(r) {
    if (!r.startedAt) return 0;
    const pausedNow = r.pausedAt ? performance.now() - r.pausedAt : 0;
    return performance.now() - r.startedAt - r.pausedFor - pausedNow;
  }

  function paused() {
    return !!(rec && rec.pausedAt);
  }

  function togglePause() {
    if (!rec || !rec.recorder) return;
    if (rec.pausedAt) {
      rec.pausedFor += performance.now() - rec.pausedAt;
      rec.pausedAt = 0;
      rec.recorder.resume();
    } else {
      rec.pausedAt = performance.now();
      rec.recorder.pause();
    }
    draw(rec);
  }

  /* send=false throws the recording away. */
  function finish(send) {
    const r = rec;
    if (!r) return;
    rec = null;
    r.cancelled = !send;
    clearInterval(r.timer);
    cancelAnimationFrame(r.frame);
    r.ui.box.classList.remove("recording", "recording-locked");
    r.ui.strip.replaceChildren();
    if (!r.recorder) return;
    const ms = elapsed(r);
    r.recorder.onstop = () => {
      r.stream.getTracks().forEach((t) => t.stop());
      try { r.source.disconnect(); } catch (e) { /* already gone */ }
      if (!send) return;
      if (ms < 800) { AV.toast("Segure o microfone um pouco mais para gravar."); return; }
      const ext = r.type.includes("ogg") ? ".ogg" : ".webm";
      r.ui.onSend(new File(r.chunks, NAME + ext, { type: r.type }), ms);
    };
    if (r.recorder.state !== "inactive") r.recorder.stop(); else r.recorder.onstop();
  }

  function lock() {
    if (!rec || rec.locked) return;
    rec.locked = true;
    draw(rec);
  }

  /* The strip redraws itself every frame: timer, live level bars, and the controls of the current mode. */
  function draw(r) {
    cancelAnimationFrame(r.frame);
    const ui = r.ui;
    ui.box.classList.toggle("recording-locked", r.locked);
    const time = E("span", { class: "voice-rec-time", "aria-live": "off" }, clock(elapsed(r)));
    const canvas = E("canvas", { class: "voice-rec-wave", height: 28, "aria-hidden": "true" });
    const dot = E("span", { class: "voice-rec-dot" + (r.pausedAt ? " paused" : ""), "aria-hidden": "true" });
    if (r.locked) {
      AV.fill(ui.strip,
        E("button", { class: "btn btn-ghost btn-icon text-danger", title: "Descartar gravação", "aria-label": "Descartar gravação", onclick: () => finish(false) }, AV.icon("trash")),
        dot, time, canvas,
        E("button", { class: "btn btn-ghost btn-icon", title: r.pausedAt ? "Continuar gravando" : "Pausar", "aria-label": r.pausedAt ? "Continuar gravando" : "Pausar gravação", onclick: togglePause }, AV.icon(r.pausedAt ? "mic" : "pause")),
        E("button", { class: "btn btn-primary btn-icon", title: "Enviar mensagem de voz", "aria-label": "Enviar mensagem de voz", onclick: () => finish(true) }, AV.icon("send")));
    } else {
      AV.fill(ui.strip, dot, time, canvas,
        E("span", { class: "voice-rec-hint" }, "‹ Arraste para cancelar"),
        E("span", { class: "voice-rec-lock", title: "Arraste para cima para gravar sem segurar" }, AV.icon("lock", "ic-sm"), "↑"));
    }
    const data = new Uint8Array(r.analyser ? r.analyser.fftSize : 0);
    let last = 0;
    const frame = (now) => {
      if (rec !== r) return;
      time.textContent = clock(elapsed(r));
      if (r.analyser && now - last > 60 && !r.pausedAt) {
        last = now;
        r.analyser.getByteTimeDomainData(data);
        let peak = 0;
        for (let i = 0; i < data.length; i++) peak = Math.max(peak, Math.abs(data[i] - 128) / 128);
        r.levels.push(Math.min(1, peak * 1.8));
        if (r.levels.length > LIVE_BARS) r.levels.shift();
      }
      const w = canvas.clientWidth;
      if (w && canvas.width !== w) canvas.width = w;
      const g = canvas.getContext("2d");
      g.clearRect(0, 0, canvas.width, canvas.height);
      g.fillStyle = getComputedStyle(canvas).color;
      const step = canvas.width / LIVE_BARS;
      r.levels.forEach((v, i) => {
        const h = Math.max(3, v * canvas.height);
        g.fillRect(canvas.width - (r.levels.length - i) * step + 1, (canvas.height - h) / 2, Math.max(2, step - 2), h);
      });
      r.frame = requestAnimationFrame(frame);
    };
    r.frame = requestAnimationFrame(frame);
  }

  /* Hold to record, release to send; a quick click records hands-free. Pointer capture keeps the gesture while sliding. */
  function bind(button, ui) {
    let down = null;
    button.addEventListener("pointerdown", (e) => {
      if (e.button !== 0 || button.disabled || rec) return;
      e.preventDefault();
      button.setPointerCapture(e.pointerId);
      down = { x: e.clientX, y: e.clientY, at: performance.now() };
      start(ui, false);
    });
    button.addEventListener("pointermove", (e) => {
      if (!down || !rec || rec.locked) return;
      const dx = e.clientX - down.x;
      const dy = e.clientY - down.y;
      if (dx < -CANCEL_PX) { down = null; finish(false); AV.toast("Gravação descartada."); return; }
      if (dy < -LOCK_PX) lock();
      const hint = ui.strip.querySelector(".voice-rec-hint");
      if (hint) hint.style.transform = "translateX(" + Math.min(0, dx) + "px)";
    });
    const release = () => {
      if (!down) return;
      const quick = performance.now() - down.at < HOLD_MS;
      down = null;
      if (!rec) return;
      if (quick) lock();
      else if (!rec.locked) finish(true);
    };
    button.addEventListener("pointerup", release);
    button.addEventListener("pointercancel", () => { if (down) { down = null; if (rec && !rec.locked) finish(false); } });
    button.addEventListener("keydown", (e) => {
      if ((e.key === "Enter" || e.key === " ") && !rec) { e.preventDefault(); start(ui, true); }
    });
  }

  /* ---- playing ---- */

  const decoded = new Map();
  let playing = null;

  function peaksOf(buffer) {
    const data = buffer.getChannelData(0);
    const size = Math.floor(data.length / BARS) || 1;
    const out = [];
    for (let b = 0; b < BARS; b++) {
      let sum = 0;
      const from = b * size;
      for (let i = from; i < from + size && i < data.length; i++) sum += data[i] * data[i];
      out.push(Math.sqrt(sum / size));
    }
    const max = Math.max(...out, 0.0001);
    return out.map((v) => Math.max(0.08, v / max));
  }

  /* Fetched once per page: the blob URL makes seeking work, and the decoded audio gives the waveform and true length. */
  function load(a) {
    if (!decoded.has(a.id)) {
      decoded.set(a.id, fetch("/api/files/" + encodeURIComponent(a.id)).then((r) => {
        if (!r.ok) throw new Error("Áudio indisponível.");
        return r.arrayBuffer();
      }).then(async (buf) => {
        const url = URL.createObjectURL(new Blob([buf], { type: a.type }));
        let peaks = null;
        let duration = 0;
        try {
          const audio = await AV.cueContext().decodeAudioData(buf);
          peaks = peaksOf(audio);
          duration = audio.duration;
        } catch (e) { /* An unusual format still plays; it just has no waveform. */ }
        return { url, peaks, duration };
      }).catch((e) => { decoded.delete(a.id); throw e; }));
    }
    return decoded.get(a.id);
  }

  function player(a, from) {
    const voice = a.name.startsWith(NAME);
    const bars = Array.from({ length: BARS }, () => E("i", { style: "height:20%" }));
    const wave = E("div", { class: "voice-wave", role: "slider", tabindex: 0, "aria-label": "Posição do áudio", "aria-valuemin": 0, "aria-valuemax": 0, "aria-valuenow": 0 }, bars);
    const time = E("span", { class: "voice-time" }, "0:00");
    const playBtn = E("button", { class: "voice-play", "aria-label": "Reproduzir" }, AV.icon("play"));
    const speedBtn = E("button", { class: "voice-speed", title: "Velocidade", "aria-label": "Velocidade de reprodução" }, "1×");
    let audio = null;
    let info = null;
    let speed = 0;
    let frame = 0;

    const paint = () => {
      const d = info && info.duration ? info.duration : 0;
      const t = audio ? audio.currentTime : 0;
      const f = d ? Math.min(1, t / d) : 0;
      const lit = Math.round(f * BARS);
      bars.forEach((b, i) => b.classList.toggle("on", i < lit));
      time.textContent = audio && (t > 0 || !audio.paused) ? clock(t * 1000) : clock(d * 1000);
      wave.setAttribute("aria-valuenow", String(Math.round(t)));
      wave.setAttribute("aria-valuetext", clock(t * 1000) + " de " + clock(d * 1000));
    };
    const loop = () => { paint(); if (audio && !audio.paused) frame = requestAnimationFrame(loop); };
    const setIcon = (on) => { AV.fill(playBtn, AV.icon(on ? "pause" : "play")); playBtn.setAttribute("aria-label", on ? "Pausar" : "Reproduzir"); };

    const ready = () => load(a).then((got) => {
      info = got;
      if (got.peaks) got.peaks.forEach((v, i) => { bars[i].style.height = Math.round(v * 100) + "%"; });
      wave.setAttribute("aria-valuemax", String(Math.round(got.duration)));
      if (!audio) {
        audio = new Audio(got.url);
        audio.playbackRate = SPEEDS[speed];
        audio.addEventListener("play", () => { setIcon(true); loop(); });
        audio.addEventListener("pause", () => { setIcon(false); cancelAnimationFrame(frame); paint(); });
        audio.addEventListener("ended", () => { audio.currentTime = 0; setIcon(false); paint(); if (playing === audio) playing = null; });
        const sink = (AV.state.settings || {})["audio.output"];
        if (sink && audio.setSinkId) audio.setSinkId(sink).catch(() => {});
      }
      paint();
      return audio;
    });

    playBtn.addEventListener("click", async () => {
      try {
        const a2 = await ready();
        if (a2.paused) {
          if (playing && playing !== a2) playing.pause();
          playing = a2;
          await a2.play();
        } else {
          a2.pause();
        }
      } catch (e) { AV.fail(e); }
    });
    speedBtn.addEventListener("click", () => {
      speed = (speed + 1) % SPEEDS.length;
      speedBtn.textContent = String(SPEEDS[speed]).replace(".", ",") + "×";
      if (audio) audio.playbackRate = SPEEDS[speed];
    });
    const seek = async (clientX) => {
      const a2 = await ready();
      const r = wave.getBoundingClientRect();
      const f = Math.min(1, Math.max(0, (clientX - r.left) / r.width));
      if (info.duration) a2.currentTime = f * info.duration;
      paint();
    };
    let dragging = false;
    wave.addEventListener("pointerdown", (e) => { dragging = true; wave.setPointerCapture(e.pointerId); seek(e.clientX).catch(AV.fail); });
    wave.addEventListener("pointermove", (e) => { if (dragging) seek(e.clientX).catch(() => {}); });
    wave.addEventListener("pointerup", () => { dragging = false; });
    wave.addEventListener("keydown", async (e) => {
      if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
      e.preventDefault();
      const a2 = await ready();
      a2.currentTime = Math.max(0, Math.min(info.duration || 0, a2.currentTime + (e.key === "ArrowRight" ? 5 : -5)));
      paint();
    });

    ready().catch(() => { time.textContent = "—"; });
    return E("div", { class: "voice card" },
      voice && from ? E("div", { class: "voice-face" }, AV.avatar(from.id, from.name, "avatar-sm"), E("span", { class: "voice-mic" }, AV.icon("mic", "ic-sm"))) : null,
      playBtn,
      E("div", { class: "voice-main" }, wave, E("div", { class: "voice-meta" }, time, !voice && E("span", { class: "truncate" }, a.name))),
      speedBtn);
  }

  AV.voice = { bind, player, recording: () => !!rec, paused, finish };
})();
