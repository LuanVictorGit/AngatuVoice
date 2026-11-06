"use strict";
/* Voice over WebRTC through the server's TURN relay, and shared screens through the server's stream channel; Java only relays signalling. */
(function () {
  const peers = new Map();
  const VOICE_BITRATE = 64000;
  const VAD_TICK_MS = 50;
  const STATS_TICK_MS = 2000;
  const STATE_EVERY_MS = 2000;
  const STATE_STALE_MS = 5000;
  const GATE_HOLD_MS = 500;
  const SPEAK_HOLD_MS = 600;
  const SPEAK_ATTACK_TICKS = 2;
  const RELEASE_RATIO = 0.6;
  const PRE_ROLL_S = 0.05;
  const REMOTE_SPEECH_LEVEL = 0.012;
  const DUCK_GAIN = 0.06;
  const CALL_TIMEOUT_MS = 45000;
  const STATE_CHANNEL_ID = 7;
  // Only reached without a usable video card, or with a viewer on an older version: VP8 is the software codec that keeps its frame rate here.
  const CODEC_ORDER = ["video/VP8", "video/VP9", "video/AV1"];
  const HW_FIRST_FRAME_MS = 8000;
  // A picture that stopped while the sound goes on: the viewer subscribes again instead of staying on a black screen.
  const PICTURE_STALLED_MS = 4000;
  const CAPTURE_HEADROOM_FPS = 120;
  // Below this many threads a software encoder at full size takes the computer down with it.
  const WEAK_CPU_THREADS = 4;
  const WEAK_CPU_HEIGHT = 720;
  const START_KBPS = 6000;
  const MIN_KBPS = 800;
  const LOWER_RUNGS = [1440, 1080, 720, 540, 360];
  // Below this the picture stutters: under pressure the share gives up resolution, never frames.
  const MIN_FPS = 30;
  const FPS_SLACK = 0.85;
  // A short receive buffer evens out frame arrival, so the picture plays at a steady rate instead of in bursts.
  const VIDEO_JITTER_MS = 60;
  const FULL_DESKTOP = "screen:-1:0";
  const SETTLE_MS = 10000;
  const PRESSURE_TICKS = 3;
  const UP_HOLD_MS = 20000;
  const UP_HOLD_MAX_MS = 120000;
  const RESTART_AFTER_MS = 6000;
  const RESET_AFTER_MS = 25000;
  const RESET_JITTER_MS = 5000;

  const rtc = AV.rtc = {
    room: null, muted: false, deafened: false, muteAll: false, pttDown: false, calling: false,
    localShare: null, watching: new Set(), selfPreview: false, speaking: {}, onchange: () => {}, onspeaking: () => {}, onstats: () => {},
  };
  let ctx = null;
  let master = null;
  let mic = null;
  let shareAudio = null;
  let shareProbe = null;
  let shareHeight = 0;
  let hwSender = null;
  let hwEncoderCodec = null;
  let hwDecodes = false;
  let ducked = false;
  let vadTimer = null;
  let statsTimer = null;
  let callTimer = null;
  let gateOpenUntil = 0;
  let lastStateSent = 0;
  let sendChain = Promise.resolve();
  let receiveChain = Promise.resolve();
  const cfg = () => AV.state.settings;
  const myId = () => AV.state.me.id;
  const num = (key) => Number(cfg()[key]);
  const flag = (key) => cfg()[key] === "true";

  function audioContext() {
    if (!ctx) {
      ctx = new AudioContext({ latencyHint: "interactive", sampleRate: 48000 });
      master = ctx.createGain();
      master.connect(ctx.destination);
      applyOutput();
    }
    if (ctx.state === "suspended") ctx.resume();
    return ctx;
  }

  function applyOutput() {
    if (!ctx) return;
    master.gain.value = rtc.deafened ? 0 : num("audio.outputVolume") / 100;
    if (ctx.setSinkId) ctx.setSinkId(cfg()["audio.output"] || "").catch(() => {});
    peers.forEach((p) => p.audios.forEach((a) => {
      a.gain.gain.value = a.share ? shareGain(p.id) : peerGain(p.id);
    }));
  }

  rtc.mutedShares = new Set();
  rtc.isShareMuted = (id) => rtc.mutedShares.has(id);
  rtc.toggleShareMute = function (id) {
    if (rtc.mutedShares.has(id)) rtc.mutedShares.delete(id);
    else rtc.mutedShares.add(id);
    if (id === myId() && shareAudio) shareAudio.track.enabled = !rtc.mutedShares.has(id);
    applyOutput();
    rtc.onchange();
  };

  function peerVolumes() {
    try { return JSON.parse(cfg()["audio.peerVolumes"] || "{}"); } catch (e) { return {}; }
  }

  function peerMutes() {
    try { return JSON.parse(cfg()["audio.peerMutes"] || "[]"); } catch (e) { return []; }
  }

  function peerGain(id) {
    if (rtc.muteAll || rtc.isPeerMuted(id)) return 0;
    const v = peerVolumes()[id];
    return (v === undefined ? 100 : v) / 100;
  }

  rtc.isPeerMuted = (id) => peerMutes().includes(id);
  rtc.isAudible = (id) => id === myId() || (!rtc.muteAll && !rtc.isPeerMuted(id));
  rtc.togglePeerMute = function (id) {
    const muted = peerMutes();
    const next = muted.includes(id) ? muted.filter((x) => x !== id) : [...muted, id];
    cfg()["audio.peerMutes"] = JSON.stringify(next);
    applyOutput();
    persistMutes();
    AV.sound(next.includes(id) ? "peerMuted" : "peerUnmuted");
    rtc.onchange();
  };
  const persistMutes = AV.debounce(() => {
    AV.api("PUT", "/api/settings", { "audio.peerMutes": cfg()["audio.peerMutes"] }).catch(AV.fail);
  }, 300);

  rtc.setPeerVolume = function (id, percent) {
    const all = peerVolumes();
    all[id] = percent;
    cfg()["audio.peerVolumes"] = JSON.stringify(all);
    applyOutput();
    rtc.persistVolumes();
  };
  rtc.persistVolumes = AV.debounce(() => {
    AV.api("PUT", "/api/settings", { "audio.peerVolumes": cfg()["audio.peerVolumes"] }).catch(() => {});
  }, 600);
  rtc.peerVolume = (id) => { const v = peerVolumes()[id]; return v === undefined ? 100 : v; };

  /* A watched share has its own volume, apart from the person's voice: lowering a loud game must not make the friend inaudible. */
  function shareVolumes() {
    try { return JSON.parse(cfg()["audio.shareVolumes"] || "{}"); } catch (e) { return {}; }
  }

  function shareGain(id) {
    if (rtc.muteAll || rtc.isShareMuted(id) || !rtc.watching.has(id)) return 0;
    return rtc.shareVolume(id) / 100;
  }

  rtc.shareVolume = (id) => { const v = shareVolumes()[id]; return v === undefined ? 100 : v; };
  rtc.setShareVolume = function (id, percent) {
    const all = shareVolumes();
    all[id] = percent;
    cfg()["audio.shareVolumes"] = JSON.stringify(all);
    applyOutput();
    persistShareVolumes();
  };
  const persistShareVolumes = AV.debounce(() => {
    AV.api("PUT", "/api/settings", { "audio.shareVolumes": cfg()["audio.shareVolumes"] }).catch(() => {});
  }, 600);
  rtc.applyOutput = applyOutput;

  function threshold() {
    const s = num("audio.sensitivity") / 100;
    return 0.03 * (1 - s) * (1 - s);
  }

  function rms(analyser, buffer) {
    analyser.getFloatTimeDomainData(buffer);
    let sum = 0;
    for (let i = 0; i < buffer.length; i++) sum += buffer[i] * buffer[i];
    return Math.sqrt(sum / buffer.length);
  }

  function newMeter() {
    return { on: false, until: 0, hits: 0 };
  }

  /* Hysteresis so a word turns the indicator on once and it stays on through pauses, instead of blinking with the level. */
  function metered(meter, level, openAt, now) {
    if (level > (meter.on ? openAt * RELEASE_RATIO : openAt)) {
      meter.hits++;
      if (meter.on || meter.hits >= SPEAK_ATTACK_TICKS) {
        meter.on = true;
        meter.until = now + SPEAK_HOLD_MS;
      }
    } else {
      meter.hits = 0;
      if (meter.on && now >= meter.until) meter.on = false;
    }
    return meter.on;
  }

  function setSpeaking(id, value) {
    if (!!rtc.speaking[id] !== value) {
      rtc.speaking[id] = value;
      rtc.onspeaking(id, value);
    }
  }

  function updateGate() {
    if (!mic) return;
    const open = !rtc.muted && (flag("audio.ptt") ? rtc.pttDown : performance.now() < gateOpenUntil || num("audio.sensitivity") >= 100);
    if (mic.track.enabled !== open) mic.track.enabled = open;
  }

  function vadLoop() {
    const now = performance.now();
    if (mic) {
      const level = rms(mic.analyser, mic.buffer);
      const openAt = threshold();
      if (level > openAt) gateOpenUntil = now + GATE_HOLD_MS;
      updateGate();
      const talking = metered(mic.meter, level, openAt, now) && mic.track.enabled;
      if (talking !== !!rtc.speaking[myId()]) {
        setSpeaking(myId(), talking);
        broadcastState();
      }
    } else {
      setSpeaking(myId(), false);
    }
    let remoteAudible = false;
    peers.forEach((p) => {
      let voice = 0;
      p.audios.forEach((a) => {
        const level = rms(a.analyser, a.buffer);
        if (!a.share) voice = Math.max(voice, level);
        if (level > REMOTE_SPEECH_LEVEL && a.gain.gain.value > 0) remoteAudible = true;
      });
      // The sender's own word starts the indicator on time; what is heard here keeps it on, because the sound arrives later than the word that it ended.
      const heard = metered(p.voice, voice, REMOTE_SPEECH_LEVEL, now);
      const fresh = p.state && now - p.state.at < STATE_STALE_MS;
      setSpeaking(p.id, !(fresh && p.state.muted) && (heard || !!(fresh && p.state.speaking)));
    });
    duckShareAudio(remoteAudible && !rtc.deafened);
    if (now - lastStateSent >= STATE_EVERY_MS) broadcastState();
  }

  /* System audio capture also hears the app's own output, so while someone talks the shared sound is pulled down or they would hear themselves come back. */
  function duckShareAudio(duck) {
    if (!shareAudio || shareAudio.kind === "isolated" || duck === ducked) return;
    ducked = duck;
    shareAudio.duck.gain.setTargetAtTime(duck ? DUCK_GAIN : 1, ctx.currentTime, duck ? 0.01 : 0.35);
  }

  function micConstraints(device) {
    return {
      audio: {
        deviceId: device ? { exact: device } : undefined,
        echoCancellation: flag("audio.echo"), noiseSuppression: flag("audio.noise"), autoGainControl: true, channelCount: 1,
      },
    };
  }

  async function captureMic() {
    const device = cfg()["audio.input"];
    try {
      return await AV.capture(micConstraints(device));
    } catch (e) {
      // The chosen device may have been unplugged; the default one is better than a silent call.
      if (!device) throw e;
      return AV.capture(micConstraints(""));
    }
  }

  function attachMicSource(raw) {
    const source = ctx.createMediaStreamSource(raw);
    source.connect(mic.gain);
    mic.raw = raw;
    mic.source = source;
    raw.getAudioTracks()[0].onended = () => { if (mic && mic.raw === raw) reconnectMic(); };
  }

  async function reconnectMic() {
    try {
      const raw = await captureMic();
      if (!mic) { raw.getTracks().forEach((t) => t.stop()); return; }
      const old = mic.raw;
      mic.source.disconnect();
      old.getAudioTracks()[0].onended = null;
      old.getTracks().forEach((t) => t.stop());
      attachMicSource(raw);
    } catch (e) {
      AV.toast("O microfone foi desconectado.", "error");
    }
  }

  async function openMic() {
    const raw = await captureMic();
    const c = audioContext();
    const gain = c.createGain();
    gain.gain.value = num("audio.inputVolume") / 100;
    const analyser = c.createAnalyser();
    analyser.fftSize = 1024;
    // The gate opens a tick after the voice starts; this delay keeps the first syllable from being cut.
    const preRoll = c.createDelay(0.2);
    preRoll.delayTime.value = PRE_ROLL_S;
    const dest = c.createMediaStreamDestination();
    gain.connect(analyser);
    gain.connect(preRoll);
    preRoll.connect(dest);
    mic = { gain, analyser, buffer: new Float32Array(analyser.fftSize), track: dest.stream.getAudioTracks()[0], stream: dest.stream, meter: newMeter() };
    attachMicSource(raw);
    updateGate();
  }

  function closeMic() {
    if (!mic) return;
    mic.raw.getAudioTracks()[0].onended = null;
    mic.raw.getTracks().forEach((t) => t.stop());
    mic.source.disconnect();
    mic = null;
  }

  rtc.applyInput = function () { if (mic) mic.gain.gain.value = num("audio.inputVolume") / 100; };
  rtc.switchInput = function () { if (mic) reconnectMic(); };

  function signal(id, kind, data) {
    const p = peers.get(id);
    const room = rtc.room;
    if (!room) return;
    const body = { ...data, sid: p ? p.sid : "", ack: p ? p.remoteSid || "" : "" };
    // Parallel requests reach the other side in any order (a candidate before its offer, a bye after the new offer), so they go one at a time.
    sendChain = sendChain.then(() => AV.api("POST", "/api/call/signal", { room, to: id, kind, data: body })).catch(() => {});
  }

  /* Each connection tells the other side where its encoder should begin; without this it starts near 300 kbps and the first seconds of a share are mush. */
  function withVideoHints(sdp) {
    const is60 = num("video.fps") >= 60;
    const startKbps = is60 ? START_KBPS : 3500;
    const minKbps = is60 ? 1500 : MIN_KBPS;
    const hints = "x-google-start-bitrate=" + startKbps + ";x-google-min-bitrate=" + minKbps;
    const lines = sdp.split("\r\n");
    const out = [];
    let inVideo = false;
    let pending = [];
    const flush = () => { pending.forEach((pt) => out.push("a=fmtp:" + pt + " " + hints)); pending = []; };
    for (const line of lines) {
      if (line.startsWith("m=")) { flush(); inVideo = line.startsWith("m=video"); }
      if (inVideo && line.startsWith("c=IN IP4")) { out.push(line); out.push("b=AS:16000"); continue; }
      const rtpmap = inVideo && line.match(/^a=rtpmap:(\d+) (VP8|VP9|AV1)\//);
      const fmtp = inVideo && line.match(/^a=fmtp:(\d+) /);
      if (rtpmap) pending.push(rtpmap[1]);
      if (fmtp && pending.includes(fmtp[1])) {
        pending = pending.filter((pt) => pt !== fmtp[1]);
        out.push(line + ";" + hints);
        continue;
      }
      if (line === "" ) flush();
      out.push(line);
    }
    return out.join("\r\n");
  }

  function sendDescription(p) {
    const d = p.pc.localDescription;
    if (d) signal(p.id, d.type, { type: d.type, sdp: withVideoHints(d.sdp) });
  }

  function preferCodecs(transceiver) {
    const caps = RTCRtpSender.getCapabilities("video");
    if (!caps || !transceiver.setCodecPreferences) return;
    const usable = caps.codecs.filter((c) => !(c.mimeType === "video/VP9" && (c.sdpFmtpLine || "").includes("profile-id=2")));
    const rank = (c) => { const i = CODEC_ORDER.indexOf(c.mimeType); return i < 0 ? CODEC_ORDER.length : i; };
    try {
      transceiver.setCodecPreferences([...usable].sort((a, b) => rank(a) - rank(b)));
    } catch (e) {
      console.warn("codec preferences refused", e);
    }
  }

  function tuneVoice(p) {
    const sender = p.pc.getSenders().find((s) => mic && s.track === mic.track);
    if (!sender) return;
    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) return;
    params.encodings[0].maxBitrate = VOICE_BITRATE;
    params.encodings[0].priority = "high";
    params.encodings[0].networkPriority = "high";
    sender.setParameters(params).catch(() => {});
  }

  function ensurePeer(id) {
    let p = peers.get(id);
    if (p) return p;
    // Voice only ever travels through the server's TURN relay: nobody in a call sees anyone else's address.
    const turn = AV.state.net.turn;
    const pc = new RTCPeerConnection({ iceServers: turn ? [turn] : [], iceTransportPolicy: turn ? "relay" : "all", bundlePolicy: "max-bundle" });
    p = {
      id, pc, sid: crypto.randomUUID(), remoteSid: null, polite: myId() > id, makingOffer: false, ignoreOffer: false, pendingCandidates: [],
      audios: new Map(), videos: new Map(), share: null, wantsShare: false, counters: null, channel: null, state: null, voice: newMeter(), stats: null,
      level: 0, levelAt: 0, pressure: 0, upHold: UP_HOLD_MS, calmSince: 0, troubleSince: 0, restartedAt: 0,
      resetAfter: RESET_AFTER_MS + Math.random() * RESET_JITTER_MS,
    };
    peers.set(id, p);
    const peer = p;
    pc.onnegotiationneeded = async () => {
      try {
        peer.makingOffer = true;
        await pc.setLocalDescription();
        sendDescription(peer);
      } catch (e) {
        console.warn("negotiation failed", e);
      } finally {
        peer.makingOffer = false;
      }
    };
    pc.onicecandidate = (e) => {
      if (e.candidate) signal(id, "candidate", e.candidate.toJSON());
    };
    pc.oniceconnectionstatechange = () => {
      const state = pc.iceConnectionState;
      if (state === "failed" || state === "disconnected") {
        if (!peer.troubleSince) peer.troubleSince = performance.now();
        if (state === "failed" || performance.now() - peer.restartedAt > RESTART_AFTER_MS) {
          peer.restartedAt = performance.now();
          pc.restartIce();
        }
      }
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "connected") { tuneVoice(peer); peer.troubleSince = 0; }
      rtc.onchange();
    };
    pc.ontrack = (e) => {
      const stream = e.streams[0] || new MediaStream([e.track]);
      // A share switched off leaves its tracks muted, not ended: only the stream says they are gone.
      stream.onremovetrack = (ev) => dropTrack(peer, ev.track);
      e.track.onended = () => dropTrack(peer, e.track);
      if (e.track.kind === "audio") attachAudio(peer, e.track, stream.getVideoTracks().length > 0);
      else {
        preferCodecs(e.transceiver);
        if ("jitterBufferTarget" in e.receiver) e.receiver.jitterBufferTarget = VIDEO_JITTER_MS;
        attachVideo(peer, e.track, stream);
      }
    };
    peer.channel = pc.createDataChannel("av-state", { negotiated: true, id: STATE_CHANNEL_ID });
    peer.channel.onopen = () => sendState(peer);
    peer.channel.onmessage = (e) => onPeerState(peer, e.data);
    if (mic) pc.addTrack(mic.track, mic.stream);
    else pc.addTransceiver("audio", { direction: "recvonly" });
    if (rtc.localShare) openShareFor(peer);
    return p;
  }

  function onPeerState(p, raw) {
    let o;
    try { o = JSON.parse(raw); } catch (e) { return; }
    const previous = p.state;
    const viewers = Array.isArray(o.vw) ? o.vw.filter((id) => typeof id === "string").slice(0, 64) : [];
    p.state = { speaking: o.s === 1, muted: o.m === 1, deafened: o.d === 1, sharing: o.v === 1, stream: typeof o.vh === "string" ? o.vh : "", viewers, at: performance.now() };
    const decodes = o.h === 1;
    if (decodes !== p.decodesOnCard) {
      p.decodesOnCard = decodes;
      if (rtc.localShare) feedShare(p);
    }
    if (p.cardReceiver && p.cardReceiver.id !== p.state.stream) closeCardReceiver(p);
    openCardReceiver(p);
    const wants = o.w === 1;
    let changed = !previous || previous.muted !== p.state.muted || previous.deafened !== p.state.deafened
      || previous.sharing !== p.state.sharing || previous.viewers.join() !== viewers.join();
    if (previous && previous.sharing && !p.state.sharing) { rtc.watching.delete(p.id); AV.sound("streamStop"); }
    if (previous && !previous.sharing && p.state.sharing) AV.sound("streamStart");
    if (wants !== p.wantsShare) {
      p.wantsShare = wants;
      if (rtc.localShare) {
        feedShare(p);
        AV.sound(wants ? "viewerJoin" : "viewerLeave");
        // Name is looked up at call time: app.js (nameOf) loads after this file.
        const known = AV.state.peers.find((x) => x.id === p.id);
        AV.toast((known && known.name ? known.name : "Alguém") + (wants ? " começou a assistir à sua tela." : " parou de assistir à sua tela."));
        broadcastState();
      }
      changed = true;
    }
    if (changed) rtc.onchange();
  }

  function sendState(p) {
    try {
      const o = { s: rtc.speaking[myId()] ? 1 : 0, m: rtc.muted ? 1 : 0, d: rtc.deafened ? 1 : 0, w: rtc.watching.has(p.id) ? 1 : 0 };
      if (hwDecodes) o.h = 1;
      if (rtc.localShare) {
        o.v = 1;
        o.vw = rtc.viewers();
        if (hwSender && hwSender.has(p.id)) o.vh = hwSender.id;
      }
      // Voice may still be connecting, or failing, and a shared screen does not depend on it: the server carries this meanwhile.
      if (p.channel && p.channel.readyState === "open") p.channel.send(JSON.stringify(o));
      else signal(p.id, "state", { state: o });
    } catch (e) {
      console.warn("state not sent", e);
    }
  }

  function broadcastState() {
    lastStateSent = performance.now();
    peers.forEach(sendState);
  }

  function attachAudio(p, track, share) {
    // A share turned back on reuses its track: attaching it twice would play it twice.
    if (p.audios.has(track.id)) return;
    const c = audioContext();
    const stream = new MediaStream([track]);
    const keepAlive = new Audio();
    keepAlive.srcObject = stream;
    keepAlive.muted = true;
    keepAlive.play().catch(() => {});
    const source = c.createMediaStreamSource(stream);
    const gain = c.createGain();
    gain.gain.value = share ? shareGain(p.id) : peerGain(p.id);
    const analyser = c.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(gain);
    gain.connect(master);
    source.connect(analyser);
    p.audios.set(track.id, { gain, analyser, buffer: new Float32Array(analyser.fftSize), share, keepAlive, source });
  }

  /* ---- the picture through the video card (hwvideo.js) ---- */

  function openCardReceiver(p) {
    if (p.cardReceiver || !hwDecodes || !rtc.watching.has(p.id) || !p.state || !p.state.stream) return;
    const receiver = AV.hwVideo.createReceiver({ url: AV.state.net.streamUrl, stream: p.state.stream, onfail: () => giveUpOnCardDecoding(p) });
    receiver.id = p.state.stream;
    p.cardReceiver = receiver;
    p.cardFrames = 0;
    p.cardFramesAt = performance.now();
    rtc.onchange();
  }

  function closeCardReceiver(p) {
    if (!p.cardReceiver) return;
    p.cardReceiver.close();
    p.cardReceiver = null;
    rtc.onchange();
  }

  /* A card that cannot do it after all costs a moment of black picture: the sender goes back to WebRTC for this viewer. */
  function giveUpOnCardDecoding(p) {
    hwDecodes = false;
    closeCardReceiver(p);
    broadcastState();
  }

  /* Nothing on screen for too long: first try a clean subscription, and if the very first picture never comes, fall back to WebRTC. */
  function watchCardPicture(p, now) {
    const receiver = p.cardReceiver;
    if (!receiver) return;
    const decoded = receiver.counters.decoded;
    if (decoded !== p.cardFrames) {
      p.cardFrames = decoded;
      p.cardFramesAt = now;
      p.cardRetries = 0;
      return;
    }
    const silent = now - p.cardFramesAt;
    if (decoded === 0 && silent >= HW_FIRST_FRAME_MS && (p.cardRetries || 0) >= 1) {
      console.warn("no picture from the server, falling back");
      giveUpOnCardDecoding(p);
    } else if (silent >= PICTURE_STALLED_MS) {
      p.cardRetries = (p.cardRetries || 0) + 1;
      closeCardReceiver(p);
      openCardReceiver(p);
    } else {
      receiver.askForKey();
    }
  }

  /* Same safety for a picture coming over WebRTC: dropping and renewing the request makes the sender feed the track again. */
  function watchRtcPicture(p, now) {
    if (p.cardReceiver || !rtc.watching.has(p.id) || !p.state || !p.state.sharing || p.state.stream) { p.rtcStalledSince = 0; return; }
    if (p.stats && p.stats.inFps > 0) { p.rtcStalledSince = 0; return; }
    if (!p.rtcStalledSince) p.rtcStalledSince = now;
    if (now - p.rtcStalledSince >= PICTURE_STALLED_MS) {
      p.rtcStalledSince = 0;
      rtc.watching.delete(p.id);
      sendState(p);
      setTimeout(() => {
        rtc.watching.add(p.id);
        sendState(p);
      }, 500);
    }
  }

  function cardTarget() {
    const steps = ladder();
    return { height: steps[0].height, fps: steps[0].fps, bitrate: steps[0].maxKbps * 1000 };
  }

  function stopCardSender() {
    if (!hwSender) return;
    hwSender.stop();
    hwSender = null;
  }

  function attachVideo(p, track, stream) {
    p.videos.set(track.id, stream);
    rtc.onchange();
  }

  function dropTrack(p, track) {
    const a = p.audios.get(track.id);
    if (a) { a.source.disconnect(); a.keepAlive.srcObject = null; p.audios.delete(track.id); }
    if (p.videos.delete(track.id)) rtc.onchange();
  }

  function closePeer(id) {
    const p = peers.get(id);
    if (!p) return;
    p.audios.forEach((a) => { a.source.disconnect(); a.keepAlive.srcObject = null; });
    closeCardReceiver(p);
    if (hwSender) hwSender.remove(id);
    p.pc.close();
    peers.delete(id);
    rtc.watching.delete(id);
    setSpeaking(id, false);
    rtc.onchange();
  }

  /* Each signal waits for the previous one: an answer applied while its offer is still being set breaks the negotiation for good. */
  rtc.handleSignal = function (signal) {
    receiveChain = receiveChain.then(() => applySignal(signal)).catch((e) => console.warn("signal handling failed", e));
    return receiveChain;
  };

  async function applySignal({ from, room, kind, data }) {
    if (room !== rtc.room) return;
    let p = peers.get(from);
    // Signals travel as separate requests, so a bye from a connection already replaced can arrive after the new offer.
    if (kind === "bye") { if (p && (!data.sid || !p.remoteSid || data.sid === p.remoteSid)) closePeer(from); return; }
    // A new id means the other side rebuilt its connection (reload, restart): this one can never match it again.
    // If the sender already knows our current id, it rebuilt to pair with us; rebuilding again would loop forever.
    if (p && p.remoteSid && data.sid && data.sid !== p.remoteSid && data.ack !== p.sid) {
      closePeer(from);
      p = null;
    }
    // An answer to an offer from a connection already closed here would only push this one into a wrong state.
    if (p && kind === "answer" && data.ack && data.ack !== p.sid) return;
    p = p || ensurePeer(from);
    if (data.sid) p.remoteSid = data.sid;
    const pc = p.pc;
    try {
      if (kind === "candidate") {
        // Candidates can overtake the description they belong to; adding them now would throw and lose them.
        if (!pc.remoteDescription) { p.pendingCandidates.push(data); return; }
        try { await pc.addIceCandidate(data); } catch (e) { if (!p.ignoreOffer) throw e; }
        return;
      }
      if (kind === "state") { onPeerState(p, JSON.stringify(data.state)); return; }
      const collision = kind === "offer" && (p.makingOffer || pc.signalingState !== "stable");
      p.ignoreOffer = !p.polite && collision;
      if (p.ignoreOffer) return;
      await pc.setRemoteDescription({ type: data.type, sdp: data.sdp });
      const queued = p.pendingCandidates.splice(0);
      for (const c of queued) { try { await pc.addIceCandidate(c); } catch (e) { console.warn("queued candidate refused", e); } }
      if (kind === "offer") {
        await pc.setLocalDescription();
        sendDescription(p);
      }
    } catch (e) {
      console.warn("signal handling failed", kind, e);
    }
  }

  rtc.handleRoom = function ({ room, members }) {
    AV.state.calls[room] = members;
    if (room !== rtc.room) return;
    const others = members.filter((id) => id !== myId());
    const before = [...peers.keys()];
    if (others.some((id) => !before.includes(id))) AV.sound("peerJoin");
    if (before.some((id) => !others.includes(id))) AV.sound("peerLeave");
    if (others.length) stopCalling();
    others.forEach((id) => { if (!peers.has(id) && myId() < id) ensurePeer(id); });
    [...peers.keys()].filter((id) => !others.includes(id)).forEach(closePeer);
    rtc.onchange();
  };

  /* The signalling link just came back: anything sent while it was down is lost, so finish the negotiation now. */
  rtc.resync = function (id) {
    if (!rtc.room) return;
    const members = AV.state.calls[rtc.room] || [];
    const p = peers.get(id);
    if (!p) {
      if (members.includes(id) && myId() < id) ensurePeer(id);
      return;
    }
    if (p.pc.signalingState === "have-local-offer") sendDescription(p);
    else if (["failed", "disconnected"].includes(p.pc.iceConnectionState)) { p.restartedAt = performance.now(); p.pc.restartIce(); }
  };

  function startCalling() {
    rtc.calling = true;
    AV.ringtone.start("outgoing");
    callTimer = setTimeout(onNoAnswer, CALL_TIMEOUT_MS);
  }

  function stopCalling() {
    if (!rtc.calling) return;
    rtc.calling = false;
    clearTimeout(callTimer);
    AV.ringtone.stop("outgoing");
  }

  function onNoAnswer() {
    const room = rtc.room;
    stopCalling();
    if (room && room.startsWith("dm:")) {
      AV.toast("Ninguém atendeu a chamada.");
      rtc.leave();
    }
    rtc.onchange();
  }

  rtc.onDeclined = function ({ room, name }) {
    if (room !== rtc.room) return;
    AV.toast(name + " recusou a chamada.");
    if (room.startsWith("dm:")) rtc.leave("busy");
    else if (!(AV.state.calls[room] || []).some((id) => id !== myId())) { stopCalling(); AV.sound("busy"); }
  };

  rtc.join = async function (room) {
    if (rtc.room === room) return;
    if (rtc.room) await rtc.leave();
    try {
      await openMic();
    } catch (e) {
      console.warn("microphone unavailable", e);
      AV.toast("Sem acesso ao microfone: você ouvirá a chamada, mas não será ouvido.", "error");
    }
    rtc.room = room;
    hwDecodes = (await AV.hwVideo.support()).decode;
    audioContext();
    await AV.api("POST", "/api/call/state", { room, joined: true });
    const others = (AV.state.calls[room] || []).filter((id) => id !== myId());
    others.forEach(ensurePeer);
    vadTimer = setInterval(vadLoop, VAD_TICK_MS);
    statsTimer = setInterval(statsTick, STATS_TICK_MS);
    // Calling an empty room is announced by the ringback itself; a chime on top of it would only blur the first ring.
    if (others.length || cfg()["sounds.ring"] !== "true") AV.sound("join");
    if (!others.length) startCalling();
    rtc.onchange();
  };

  rtc.leave = async function (endCue = "leave") {
    const room = rtc.room;
    if (!room) return;
    stopCalling();
    if (rtc.localShare) rtc.stopShare(true);
    peers.forEach((p, id) => { signal(id, "bye", {}); });
    [...peers.keys()].forEach(closePeer);
    clearInterval(vadTimer);
    clearInterval(statsTimer);
    closeMic();
    rtc.room = null;
    rtc.pttDown = false;
    AV.sound(endCue);
    setSpeaking(myId(), false);
    try { await AV.api("POST", "/api/call/state", { room, joined: false }); } catch (e) { AV.fail(e); }
    rtc.onchange();
  };

  rtc.toggleMute = function () {
    rtc.muted = !rtc.muted;
    AV.sound(rtc.muted ? "mute" : "unmute");
    updateGate();
    broadcastState();
    rtc.onchange();
  };
  rtc.toggleDeafen = function () {
    rtc.deafened = !rtc.deafened;
    applyOutput();
    AV.sound(rtc.deafened ? "deafen" : "undeafen");
    broadcastState();
    rtc.onchange();
  };
  rtc.toggleMuteAll = function () { rtc.muteAll = !rtc.muteAll; applyOutput(); AV.sound(rtc.muteAll ? "peerMuted" : "peerUnmuted"); rtc.onchange(); };
  rtc.setPtt = function (down) { rtc.pttDown = down; updateGate(); };
  rtc.hasMic = () => !!mic;
  rtc.peerState = (id) => { const p = peers.get(id); return p && p.state ? p.state : null; };
  rtc.connectionState = (id) => { const p = peers.get(id); return p ? p.pc.connectionState : "closed"; };
  rtc.peerStats = (id) => { const p = peers.get(id); return p ? p.stats : null; };

  /* ---- screen sharing ---- */

  function sourceHeight() {
    return shareHeight || 1080;
  }

  /* A capture track reports the size it was allowed to reach, not the frames it delivers, and every scale is computed from this. */
  function watchShareSize(video) {
    shareProbe = document.createElement("video");
    shareProbe.muted = true;
    shareProbe.srcObject = new MediaStream([video]);
    const probe = shareProbe;
    return new Promise((resolve) => {
      const update = () => {
        if (!probe.videoHeight) return;
        const changed = shareHeight && probe.videoHeight !== shareHeight;
        shareHeight = probe.videoHeight;
        resolve();
        if (changed) peers.forEach(reconfigure);
      };
      probe.onloadedmetadata = update;
      probe.onresize = update;
      probe.play().catch(() => {});
      setTimeout(resolve, 2000);
    });
  }

  function stopWatchingShareSize() {
    if (!shareProbe) return;
    shareProbe.onloadedmetadata = null;
    shareProbe.onresize = null;
    shareProbe.srcObject = null;
    shareProbe = null;
    shareHeight = 0;
  }

  function weak() {
    return !hwEncoderCodec && (navigator.hardwareConcurrency || 8) <= WEAK_CPU_THREADS;
  }

  function capKbps(height, fps) {
    if (height >= 1440) return fps > 30 ? 12000 : 7500;
    if (height >= 1080) return fps > 30 ? 8000 : 4500;
    if (height >= 720) return fps > 30 ? 4500 : 2500;
    if (height >= 540) return fps > 30 ? 2500 : 1400;
    return fps > 30 ? 1200 : 600;
  }

  function ladder() {
    const source = sourceHeight();
    const fps = Math.max(MIN_FPS, weak() ? MIN_FPS : num("video.fps"));
    const motion = cfg()["video.priority"] === "motion";
    const setting = cfg()["video.resolution"];
    const wanted = Math.min(source, setting === "native" ? source : Number(setting));
    const top = weak() ? Math.min(wanted, WEAK_CPU_HEIGHT) : wanted;
    const custom = num("video.bitrate");
    let rungs;
    if (motion) {
      rungs = [
        { height: top, fps },
        ...(top > 720 ? [{ height: 720, fps }] : []),
        { height: top, fps: MIN_FPS },
        ...(top > 720 ? [{ height: 720, fps: MIN_FPS }] : []),
        { height: 540, fps: MIN_FPS },
        { height: 360, fps: 20 },
      ];
    } else {
      rungs = [
        { height: top, fps },
        ...(fps > MIN_FPS ? [{ height: top, fps: MIN_FPS }] : []),
        ...(top > 720 ? [{ height: 720, fps: MIN_FPS }] : []),
        { height: 540, fps: MIN_FPS },
        { height: 360, fps: 20 },
      ];
    }

    return rungs.map(({ height, fps: rate }) => {
      const baseKbps = custom > 0 ? Math.round(custom * (height / top) * (height / top) * (rate / fps)) : capKbps(height, rate);
      return { height, fps: rate, scale: Math.max(1.0, source / height), maxKbps: baseKbps, needKbps: Math.round(baseKbps * 0.4) };
    });
  }

  function configureSender(p, sender) {
    const steps = ladder();
    const step = steps[Math.min(p.level, steps.length - 1)];
    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) params.encodings = [{}];
    const enc = params.encodings[0];
    enc.maxBitrate = step.maxKbps * 1000;
    enc.minBitrate = Math.round(step.needKbps * 1000);
    enc.maxFramerate = step.fps;
    enc.scaleResolutionDownBy = Math.max(1.0, step.scale);
    enc.priority = "high";
    enc.networkPriority = "high";
    // maintain-resolution holds this build at 2-5 FPS: see "Compartilhamento travado" in CLAUDE.md.
    params.degradationPreference = cfg()["video.priority"] === "motion" ? "maintain-framerate" : "balanced";
    return sender.setParameters(params).catch((e) => console.warn("sender parameters refused", e));
  }

  /*
   * Each viewer costs a whole software encoder here, so the share is offered to everyone but only encoded for whoever opens it.
   * The transceivers are made once per connection and reused across shares: no new m-lines piling up on every start and stop.
   */
  function openShareFor(p) {
    const stream = rtc.localShare;
    const audio = stream.getAudioTracks()[0];
    if (!p.share) {
      const video = p.pc.addTransceiver("video", { direction: "sendonly", streams: [stream] });
      preferCodecs(video);
      p.share = { video, audio: null };
    } else {
      p.share.video.direction = "sendonly";
      if (p.share.video.sender.setStreams) p.share.video.sender.setStreams(stream);
    }
    if (audio && !p.share.audio) p.share.audio = p.pc.addTransceiver("audio", { direction: "sendonly", streams: [stream] });
    else if (p.share.audio) {
      p.share.audio.direction = audio ? "sendonly" : "inactive";
      if (p.share.audio.sender.setStreams) p.share.audio.sender.setStreams(stream);
    }
    p.level = 0;
    p.pressure = 0;
    p.upHold = UP_HOLD_MS;
    feedShare(p);
  }

  function feedShare(p) {
    if (!p.share) return;
    const stream = rtc.localShare;
    const on = !!stream && p.wantsShare;
    const viaCard = on && !!hwEncoderCodec && p.decodesOnCard;
    feedCard(p, stream, viaCard);
    const video = on && !viaCard ? stream.getVideoTracks()[0] : null;
    const audio = on ? stream.getAudioTracks()[0] || null : null;
    if (p.share.video.sender.track !== video) {
      p.share.video.sender.replaceTrack(video).catch((e) => console.warn("share video not switched", e));
      if (video) { p.level = 0; reconfigure(p); }
    }
    if (p.share.audio && p.share.audio.sender.track !== audio) p.share.audio.sender.replaceTrack(audio).catch(() => {});
  }

  function feedCard(p, stream, viaCard) {
    if (viaCard) {
      if (!hwSender) {
        hwSender = AV.hwVideo.createSender({ url: AV.state.net.streamUrl, track: stream.getVideoTracks()[0], codec: hwEncoderCodec, target: cardTarget, onfail: onCardEncoderFailed });
      }
      if (!hwSender.has(p.id)) { hwSender.add(p.id); sendState(p); }
    } else if (hwSender && hwSender.has(p.id)) {
      hwSender.remove(p.id);
      sendState(p);
      if (hwSender.empty()) stopCardSender();
    }
  }

  function onCardEncoderFailed() {
    hwSender = null;
    hwEncoderCodec = null;
    peers.forEach(feedShare);
    broadcastState();
  }

  function closeShareFor(p) {
    if (!p.share) return;
    [p.share.video, p.share.audio].forEach((tx) => {
      if (!tx) return;
      tx.sender.replaceTrack(null).catch(() => {});
      try { tx.direction = "inactive"; } catch (e) { /* connection already closed */ }
    });
  }

  rtc.viewers = () => (rtc.localShare ? [...peers.values()].filter((p) => p.wantsShare).map((p) => p.id) : []);

  rtc.watch = function (id, on) {
    if (on) rtc.watching.add(id); else rtc.watching.delete(id);
    const p = peers.get(id);
    if (p) {
      if (!on) closeCardReceiver(p);
      else openCardReceiver(p);
      sendState(p);
    }
    applyOutput();
    rtc.onchange();
  };

  rtc.isSharing = (id) => {
    if (id === myId()) return !!rtc.localShare;
    const p = peers.get(id);
    return !!p && ((p.state && p.state.sharing && performance.now() - p.state.at < STATE_STALE_MS * 2) || p.videos.size > 0);
  };

  rtc.shareViewers = (id) => {
    if (id === myId()) return rtc.viewers();
    const p = peers.get(id);
    return p && p.state ? p.state.viewers : [];
  };

  rtc.toggleSelfPreview = function () {
    rtc.selfPreview = !rtc.selfPreview;
    rtc.onchange();
  };

  /* What the share picker offers: each monitor, then all of them together. */
  rtc.shareSources = function () {
    const monitors = AV.state.monitors || [];
    if (monitors.length < 2) return [{ id: FULL_DESKTOP, label: "Tela inteira", index: -1 }];
    return [
      ...monitors.map((m, i) => ({ id: "screen:" + m.id + ":0", label: "Tela " + (i + 1) + (m.primary ? " (principal)" : ""), index: i })),
      { id: FULL_DESKTOP, label: "Todas as telas", index: -1 },
    ];
  };

  rtc.previewSource = async function (sourceId) {
    const stream = await AV.capture({ video: { mandatory: { chromeMediaSource: "desktop", chromeMediaSourceId: sourceId, maxWidth: 480, maxHeight: 270, maxFrameRate: 5 } }, audio: false });
    try {
      const video = document.createElement("video");
      video.muted = true;
      video.srcObject = stream;
      await video.play();
      if (!video.videoWidth) await new Promise((resolve) => { video.onresize = resolve; setTimeout(resolve, 1500); });
      const canvas = document.createElement("canvas");
      canvas.width = video.videoWidth || 480;
      canvas.height = video.videoHeight || 270;
      canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);
      video.srcObject = null;
      return canvas.toDataURL("image/jpeg", 0.7);
    } finally {
      stream.getTracks().forEach((t) => t.stop());
    }
  };

  function chosenSource() {
    const monitors = AV.state.monitors || [];
    if (monitors.length < 2) return FULL_DESKTOP;
    const chosen = monitors[num("video.monitor")] || monitors.find((m) => m.primary) || monitors[0];
    return "screen:" + chosen.id + ":0";
  }

  /*
   * A screen capture only delivers frames when something on screen changes, and a share whose source may idle at zero
   * lets the encoder drop to a frame or two per second on still content: that is the rate swinging between 2 and 30.
   * A minimum frame rate keeps the track repeating frames, so the encoder is fed at a steady pace.
   */
  function captureRequest(sourceId, withAudio, steady) {
    const fps = Math.max(MIN_FPS, num("video.fps"));
    // Asked for exactly 60, the capture falls on every other screen refresh: 33 FPS. Asked for more it delivers 42 on a still screen and up to 54 with motion, and the encoder keeps to its own rate.
    const video = { chromeMediaSource: "desktop", chromeMediaSourceId: sourceId, maxWidth: 7680, maxHeight: 4320, maxFrameRate: hwEncoderCodec && fps > MIN_FPS ? CAPTURE_HEADROOM_FPS : fps };
    if (steady) video.minFrameRate = fps;
    return { video: { mandatory: video }, audio: withAudio ? { mandatory: { chromeMediaSource: "desktop" } } : false };
  }

  async function captureSource(sourceId, withAudio) {
    try {
      return await AV.capture(captureRequest(sourceId, withAudio, true));
    } catch (e) {
      if (e && e.name === "NotAllowedError") throw e;
      return AV.capture(captureRequest(sourceId, withAudio, false));
    }
  }

  async function captureScreen(withAudio, picked) {
    const sourceId = picked || chosenSource();
    try {
      return await captureSource(sourceId, withAudio);
    } catch (e) {
      if (sourceId === FULL_DESKTOP) throw e;
      return captureSource(FULL_DESKTOP, withAudio);
    }
  }

  /* System sound captured by Java without anything this app plays, so call voices and ringtones never reach the share. Resolves null where Windows lacks it. */
  async function openSystemAudio() {
    const c = audioContext();
    try {
      await c.audioWorklet.addModule("/scripts/system-audio-worklet.js");
    } catch (e) {
      console.warn("system audio player unavailable", e);
      return null;
    }
    const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws/system-audio");
    ws.binaryType = "arraybuffer";
    const ready = await new Promise((resolve) => {
      const timer = setTimeout(() => resolve(false), 6000);
      ws.onmessage = (e) => { if (e.data === "ready") { clearTimeout(timer); resolve(true); } };
      ws.onclose = () => { clearTimeout(timer); resolve(false); };
    });
    if (!ready) { ws.close(); return null; }
    const player = new AudioWorkletNode(c, "system-audio-player", { numberOfInputs: 0, outputChannelCount: [2] });
    ws.onmessage = (e) => { if (e.data instanceof ArrayBuffer) player.port.postMessage(e.data, [e.data]); };
    ws.onclose = null;
    return { player, stop: () => { ws.onmessage = null; ws.close(); player.port.close(); } };
  }

  /* Mute only governs the microphone path, so nothing but the system mix may ever enter the share: a microphone here would bypass it. */
  function shareAudioFrom(source, stop, kind) {
    const c = audioContext();
    const duck = c.createGain();
    const dest = c.createMediaStreamDestination();
    source.connect(duck);
    duck.connect(dest);
    const track = dest.stream.getAudioTracks()[0];
    track.enabled = !rtc.isShareMuted(myId());
    return { source, stop, duck, track, kind };
  }

  function buildShareAudio(captured) {
    const raw = captured.getAudioTracks()[0];
    if (!raw) return null;
    if (!(raw.getSettings().deviceId || "").startsWith("loopback")) {
      raw.stop();
      AV.toast("Não foi possível capturar o áudio do sistema. A tela será compartilhada sem som.", "error");
      return null;
    }
    return shareAudioFrom(audioContext().createMediaStreamSource(new MediaStream([raw])), () => raw.stop(), "loopback");
  }

  function closeShareAudio() {
    if (!shareAudio) return;
    shareAudio.source.disconnect();
    shareAudio.stop();
    shareAudio.track.stop();
    shareAudio = null;
    ducked = false;
  }

  rtc.startShare = async function (sourceId) {
    if (!rtc.room || rtc.localShare) return;
    hwEncoderCodec = (await AV.hwVideo.support()).encode;
    const system = flag("video.systemAudio") ? await openSystemAudio() : null;
    let captured;
    try {
      captured = await captureScreen(flag("video.systemAudio") && !system, sourceId);
    } catch (e) {
      if (system) system.stop();
      throw e;
    }
    const video = captured.getVideoTracks()[0];
    video.contentHint = cfg()["video.priority"] === "motion" ? "motion" : "detail";
    video.onended = () => rtc.stopShare();
    await watchShareSize(video);
    shareAudio = system ? shareAudioFrom(system.player, system.stop, "isolated") : buildShareAudio(captured);
    rtc.localShare = new MediaStream(shareAudio ? [video, shareAudio.track] : [video]);
    peers.forEach(openShareFor);
    broadcastState();
    AV.sound("streamStart");
    rtc.onchange();
  };

  rtc.stopShare = function (quiet) {
    const stream = rtc.localShare;
    if (!stream) return;
    rtc.localShare = null;
    rtc.selfPreview = false;
    stopCardSender();
    peers.forEach(closeShareFor);
    stream.getVideoTracks().forEach((t) => t.stop());
    closeShareAudio();
    stopWatchingShareSize();
    broadcastState();
    if (!quiet) AV.sound("streamStop");
    rtc.onchange();
  };

  rtc.applyVideo = function () {
    const stream = rtc.localShare;
    if (!stream) return;
    const video = stream.getVideoTracks()[0];
    video.contentHint = cfg()["video.priority"] === "motion" ? "motion" : "detail";
    const fps = Math.max(MIN_FPS, num("video.fps"));
    if (!hwEncoderCodec) video.applyConstraints({ frameRate: { ideal: fps, max: fps } }).catch(() => {});
    peers.forEach((p) => { p.level = 0; reconfigure(p); });
    if (hwSender) hwSender.retarget();
    rtc.onchange();
  };

  function reconfigure(p) {
    p.levelAt = performance.now();
    p.pressure = 0;
    const sender = p.share && p.share.video.sender;
    if (sender && sender.track) configureSender(p, sender);
  }

  /*
   * Each viewer has its own encoder and its own path, so one weak connection never blurs the image for everyone else.
   * A busy processor alone is no reason to step down: WebRTC already trims the size itself to hold the frame rate, and
   * stepping down on top of that made the picture swing. Only frames actually lost below the target count.
   */
  function adaptShare(p, now) {
    if (!flag("video.auto") || !p.stats || !p.share || !p.share.video.sender.track || now - p.levelAt < SETTLE_MS) return;
    const steps = ladder();
    const step = steps[Math.min(p.level, steps.length - 1)];
    const s = p.stats;
    const starving = s.availKbps > 0 && s.availKbps < step.needKbps * 0.8;
    const slow = s.outFps > 0 && s.outFps < step.fps * FPS_SLACK && (s.limit === "cpu" || s.limit === "bandwidth");
    const pressured = starving || slow || s.loss > 0.05 || (s.rtt && s.rtt > 350);
    p.pressure = pressured ? p.pressure + 1 : 0;
    if (pressured) p.calmSince = 0;
    else if (!p.calmSince) p.calmSince = now;
    if (p.pressure >= PRESSURE_TICKS && p.level < steps.length - 1) {
      p.level++;
      // Going back up too soon would only fall again; each failed attempt waits longer before the next one.
      p.upHold = Math.min(p.upHold * 2, UP_HOLD_MAX_MS);
      reconfigure(p);
      rtc.onchange();
    } else if (p.level > 0 && p.calmSince && now - p.calmSince >= p.upHold) {
      p.level--;
      p.calmSince = 0;
      reconfigure(p);
      rtc.onchange();
    }
  }

  /* ---- connection watch ---- */

  function watchConnection(p, now) {
    const pc = p.pc;
    if (pc.connectionState === "connected") {
      p.troubleSince = 0;
      return;
    }
    if (!p.troubleSince) p.troubleSince = now;
    const elapsed = now - p.troubleSince;
    if (elapsed >= p.resetAfter) {
      // Start over on both ends: the bye makes the other side drop its half, and the jitter keeps both from doing this at once.
      const id = p.id;
      signal(id, "bye", {});
      closePeer(id);
      ensurePeer(id);
    } else if (elapsed >= RESTART_AFTER_MS && now - p.restartedAt >= RESTART_AFTER_MS) {
      p.restartedAt = now;
      if (pc.signalingState === "have-local-offer") sendDescription(p);
      else if (pc.signalingState === "stable") pc.restartIce();
    }
  }

  async function readStats(p) {
    const report = await p.pc.getStats();
    const s = { rtt: null, loss: 0, availKbps: 0, limit: "none", codec: "", outWidth: 0, outHeight: 0, outFps: 0, inWidth: 0, inHeight: 0, inFps: 0, inKbps: 0, outKbps: 0, at: performance.now() };
    let pair = null;
    report.forEach((r) => { if (r.type === "transport" && r.selectedCandidatePairId) pair = report.get(r.selectedCandidatePairId); });
    if (!pair) report.forEach((r) => { if (r.type === "candidate-pair" && r.nominated && r.state === "succeeded") pair = r; });
    if (pair) {
      if (pair.currentRoundTripTime !== undefined) s.rtt = Math.round(pair.currentRoundTripTime * 1000);
      s.availKbps = Math.round((pair.availableOutgoingBitrate || 0) / 1000);
    }
    // The frame rate comes from the counters over the whole interval: the instant value jumps with every late frame.
    const before = p.counters;
    const counters = { at: s.at, sent: 0, decoded: 0, bytesSent: 0, bytesReceived: 0 };
    report.forEach((r) => {
      if (r.kind !== "video") return;
      if (r.type === "outbound-rtp" && r.frameHeight) {
        counters.sent += r.framesSent || 0;
        counters.bytesSent += r.bytesSent || 0;
        s.outWidth = r.frameWidth;
        s.outHeight = r.frameHeight;
        s.limit = r.qualityLimitationReason || "none";
        const codec = report.get(r.codecId);
        s.codec = codec ? codec.mimeType.replace("video/", "") : "";
      }
      if (r.type === "remote-inbound-rtp") s.loss = r.fractionLost || 0;
      if (r.type === "inbound-rtp" && r.frameHeight) {
        counters.decoded += r.framesDecoded || 0;
        counters.bytesReceived += r.bytesReceived || 0;
        const codec = report.get(r.codecId);
        if (!s.codec && codec) s.codec = codec.mimeType.replace("video/", "");
        s.inWidth = r.frameWidth;
        s.inHeight = r.frameHeight;
      }
    });
    const out = hwSender && hwSender.counters(p.id);
    if (out) {
      counters.sent += out.frames;
      counters.bytesSent += out.bytes;
      s.outWidth = out.width;
      s.outHeight = out.height;
      s.codec = "H.264 na placa de vídeo";
    }
    const received = p.cardReceiver && p.cardReceiver.counters;
    if (received) {
      counters.decoded += received.decoded;
      counters.bytesReceived += received.bytes;
      if (received.width) {
        s.inWidth = received.width;
        s.inHeight = received.height;
        s.codec = "H.264 na placa de vídeo";
      }
    }
    p.counters = counters;
    if (before) {
      const seconds = (counters.at - before.at) / 1000;
      if (seconds > 0) {
        s.outFps = Math.max(0, Math.round((counters.sent - before.sent) / seconds));
        s.inFps = Math.max(0, Math.round((counters.decoded - before.decoded) / seconds));
        s.outKbps = Math.max(0, Math.round(((counters.bytesSent - before.bytesSent) * 8) / (seconds * 1000)));
        s.inKbps = Math.max(0, Math.round(((counters.bytesReceived - before.bytesReceived) * 8) / (seconds * 1000)));
      }
    }
    // A paused sender keeps reporting its last size: without frames moving, nothing is on the way.
    if (!s.outFps) { s.outWidth = 0; s.outHeight = 0; s.outKbps = 0; }
    if (!s.inFps) { s.inWidth = 0; s.inHeight = 0; s.inKbps = 0; }
    return s;
  }

  async function statsTick() {
    const now = performance.now();
    for (const p of [...peers.values()]) {
      try {
        p.stats = await readStats(p);
      } catch (e) {
        p.stats = null;
      }
      watchConnection(p, now);
      watchCardPicture(p, now);
      watchRtcPicture(p, now);
      if (rtc.localShare) adaptShare(p, now);
    }
    rtc.onstats();
  }

  rtc.shareInfo = function () {
    const sending = [...peers.values()].map((p) => p.stats).filter((s) => s && s.outHeight);
    if (!sending.length) return null;
    const best = sending.reduce((a, b) => (b.outHeight > a.outHeight ? b : a));
    const totalOutKbps = hwSender ? best.outKbps : sending.reduce((acc, s) => acc + (s.outKbps || 0), 0);
    return { width: best.outWidth, height: best.outHeight, fps: best.outFps, codec: best.codec, kbps: best.outKbps, totalKbps: totalOutKbps };
  };

  /* One tile per person on air. It carries a picture only while it is open: your own preview, or a share you chose to watch. */
  let previewStream = null;
  rtc.videoTiles = function () {
    const tiles = [];
    if (rtc.localShare) {
      const video = rtc.localShare.getVideoTracks()[0];
      if (!previewStream || previewStream.getVideoTracks()[0] !== video) previewStream = new MediaStream([video]);
      tiles.push({ id: myId(), key: "self", local: true, stream: rtc.selfPreview ? previewStream : null });
    } else {
      previewStream = null;
    }
    peers.forEach((p) => {
      if (!rtc.isSharing(p.id)) return;
      const stream = rtc.watching.has(p.id) ? (p.cardReceiver ? p.cardReceiver.stream : [...p.videos.values()][0] || null) : null;
      tiles.push({ id: p.id, key: "peer:" + p.id, local: false, stream });
    });
    return tiles;
  };

  rtc.participants = () => (rtc.room ? [myId(), ...peers.keys()] : []);

  rtc.diagnostics = function () {
    return {
      room: rtc.room,
      microphone: mic ? { open: mic.track.enabled, device: mic.raw.getAudioTracks()[0].label } : null,
      muted: rtc.muted,
      sharing: rtc.localShare ? { systemAudio: shareAudio ? shareAudio.kind : null, ducked, encoder: hwEncoderCodec || "software" } : null,
      decodesOnCard: hwDecodes,
      peers: [...peers.values()].map((p) => ({
        id: p.id,
        connection: p.pc.connectionState,
        ice: p.pc.iceConnectionState,
        muted: rtc.isPeerMuted(p.id),
        level: p.level,
        stats: p.stats,
        audio: [...p.audios.values()].map((a) => ({ share: a.share, gain: a.gain.gain.value, level: Number(rms(a.analyser, a.buffer).toFixed(4)) })),
      })),
    };
  };
})();
