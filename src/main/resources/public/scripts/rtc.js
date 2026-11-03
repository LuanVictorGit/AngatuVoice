"use strict";
/* Voice and screen sharing over WebRTC. Media flows peer to peer across the Radmin adapter; Java only relays signalling. */
(function () {
  const peers = new Map();
  const BASE_BITRATE = { "720-30": 2500000, "720-60": 4000000, "1080-30": 5000000, "1080-60": 8000000 };
  const VOICE_BITRATE = 40000;
  const HOLD_MS = 280;

  const rtc = AV.rtc = {
    room: null, muted: false, deafened: false, muteAll: false, pttDown: false,
    localShare: null, speaking: {}, onchange: () => {}, onspeaking: () => {},
  };
  let ctx = null;
  let master = null;
  let mic = null;
  let vadTimer = null;
  let adaptTimer = null;
  let level = 0;
  let good = 0;
  let voiceOpenUntil = 0;
  const cfg = () => AV.state.settings;
  const myId = () => AV.state.me.id;
  const num = (key) => Number(cfg()[key]);
  const flag = (key) => cfg()[key] === "true";

  function audioContext() {
    if (!ctx) {
      ctx = new AudioContext({ latencyHint: "interactive" });
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
    peers.forEach((p) => p.audios.forEach((a) => { a.gain.gain.value = peerGain(p.id); }));
  }

  function peerVolumes() {
    try { return JSON.parse(cfg()["audio.peerVolumes"] || "{}"); } catch (e) { return {}; }
  }

  function peerGain(id) {
    if (rtc.muteAll) return 0;
    const v = peerVolumes()[id];
    return (v === undefined ? 100 : v) / 100;
  }

  rtc.setPeerVolume = function (id, percent) {
    const all = peerVolumes();
    all[id] = percent;
    AV.state.settings["audio.peerVolumes"] = JSON.stringify(all);
    applyOutput();
    AV.rtc.persistVolumes();
  };
  rtc.persistVolumes = AV.debounce(() => {
    AV.api("PUT", "/api/settings", { "audio.peerVolumes": AV.state.settings["audio.peerVolumes"] }).catch(() => {});
  }, 600);
  rtc.peerVolume = (id) => { const v = peerVolumes()[id]; return v === undefined ? 100 : v; };
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

  function setSpeaking(id, value) {
    if (!!rtc.speaking[id] !== value) {
      rtc.speaking[id] = value;
      rtc.onspeaking(id, value);
    }
  }

  function updateGate() {
    if (!mic) return;
    const open = !rtc.muted && (flag("audio.ptt") ? rtc.pttDown : performance.now() < voiceOpenUntil || num("audio.sensitivity") >= 100);
    if (mic.track.enabled !== open) mic.track.enabled = open;
  }

  function vadLoop() {
    const now = performance.now();
    if (mic) {
      const loud = rms(mic.analyser, mic.buffer) > threshold();
      if (loud) voiceOpenUntil = now + HOLD_MS;
      updateGate();
      setSpeaking(myId(), mic.track.enabled && now < voiceOpenUntil);
    }
    peers.forEach((p) => {
      let talking = false;
      p.audios.forEach((a) => { if (!a.share && rms(a.analyser, a.buffer) > 0.012) talking = true; });
      setSpeaking(p.id, talking && !rtc.muteAll);
    });
  }

  async function openMic() {
    const device = cfg()["audio.input"];
    const raw = await navigator.mediaDevices.getUserMedia({
      audio: {
        deviceId: device ? { exact: device } : undefined,
        echoCancellation: flag("audio.echo"), noiseSuppression: flag("audio.noise"), autoGainControl: true, channelCount: 1,
      },
    });
    const c = audioContext();
    const source = c.createMediaStreamSource(raw);
    const gain = c.createGain();
    gain.gain.value = num("audio.inputVolume") / 100;
    const analyser = c.createAnalyser();
    analyser.fftSize = 1024;
    const dest = c.createMediaStreamDestination();
    source.connect(gain);
    gain.connect(analyser);
    gain.connect(dest);
    mic = { raw, gain, analyser, buffer: new Float32Array(analyser.fftSize), track: dest.stream.getAudioTracks()[0], stream: dest.stream, source };
    updateGate();
  }

  function closeMic() {
    if (!mic) return;
    mic.raw.getTracks().forEach((t) => t.stop());
    mic.source.disconnect();
    mic = null;
  }

  rtc.applyInput = function () { if (mic) mic.gain.gain.value = num("audio.inputVolume") / 100; };

  function signal(to, kind, data) {
    AV.api("POST", "/api/call/signal", { room: rtc.room, to, kind, data }).catch(() => {});
  }

  function allowedCandidate(candidate) {
    const radmin = AV.state.radmin;
    if (!radmin || radmin.adapter === "dev-loopback" || !radmin.ip) return true;
    const address = (candidate.candidate || "").split(" ")[4] || "";
    return address.startsWith(radmin.ip.split(".")[0] + ".");
  }

  function ensurePeer(id) {
    let p = peers.get(id);
    if (p) return p;
    const pc = new RTCPeerConnection({ iceServers: [], bundlePolicy: "max-bundle" });
    p = { id, pc, polite: myId() > id, makingOffer: false, ignoreOffer: false, audios: new Map(), videos: new Map(), shareSenders: [] };
    peers.set(id, p);
    const peer = p;
    pc.onnegotiationneeded = async () => {
      try {
        peer.makingOffer = true;
        await pc.setLocalDescription();
        signal(id, pc.localDescription.type, { type: pc.localDescription.type, sdp: pc.localDescription.sdp });
      } catch (e) {
        console.warn("negotiation failed", e);
      } finally {
        peer.makingOffer = false;
      }
    };
    pc.onicecandidate = (e) => { if (e.candidate && allowedCandidate(e.candidate)) signal(id, "candidate", e.candidate.toJSON()); };
    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === "failed") pc.restartIce();
    };
    pc.ontrack = (e) => {
      const stream = e.streams[0] || new MediaStream([e.track]);
      if (e.track.kind === "audio") attachAudio(peer, e.track, stream.getVideoTracks().length > 0);
      else attachVideo(peer, e.track, stream);
    };
    if (mic) {
      const sender = pc.addTrack(mic.track, mic.stream);
      const params = sender.getParameters();
      params.encodings = [{ maxBitrate: VOICE_BITRATE }];
      sender.setParameters(params).catch(() => {});
    } else {
      pc.addTransceiver("audio", { direction: "recvonly" });
    }
    if (rtc.localShare) addShareTracks(peer);
    return p;
  }

  function attachAudio(p, track, share) {
    const c = audioContext();
    const stream = new MediaStream([track]);
    const keepAlive = new Audio();
    keepAlive.srcObject = stream;
    keepAlive.muted = true;
    keepAlive.play().catch(() => {});
    const source = c.createMediaStreamSource(stream);
    const gain = c.createGain();
    gain.gain.value = peerGain(p.id);
    const analyser = c.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(gain);
    gain.connect(master);
    source.connect(analyser);
    p.audios.set(track.id, { gain, analyser, buffer: new Float32Array(analyser.fftSize), share, keepAlive, source });
    track.onended = () => {
      const a = p.audios.get(track.id);
      if (a) { a.source.disconnect(); a.keepAlive.srcObject = null; p.audios.delete(track.id); }
    };
  }

  function attachVideo(p, track, stream) {
    p.videos.set(track.id, stream);
    const drop = () => { p.videos.delete(track.id); rtc.onchange(); };
    track.onended = drop;
    stream.onremovetrack = drop;
    rtc.onchange();
  }

  function closePeer(id) {
    const p = peers.get(id);
    if (!p) return;
    p.audios.forEach((a) => { a.source.disconnect(); a.keepAlive.srcObject = null; });
    p.pc.close();
    peers.delete(id);
    setSpeaking(id, false);
    rtc.onchange();
  }

  rtc.handleSignal = async function ({ from, room, kind, data }) {
    if (room !== rtc.room) return;
    const p = ensurePeer(from);
    const pc = p.pc;
    try {
      if (kind === "bye") { closePeer(from); return; }
      if (kind === "candidate") {
        try { await pc.addIceCandidate(data); } catch (e) { if (!p.ignoreOffer) throw e; }
        return;
      }
      const collision = kind === "offer" && (p.makingOffer || pc.signalingState !== "stable");
      p.ignoreOffer = !p.polite && collision;
      if (p.ignoreOffer) return;
      await pc.setRemoteDescription(data);
      if (kind === "offer") {
        await pc.setLocalDescription();
        signal(from, "answer", { type: pc.localDescription.type, sdp: pc.localDescription.sdp });
      }
    } catch (e) {
      console.warn("signal handling failed", kind, e);
    }
  };

  rtc.handleRoom = function ({ room, members }) {
    AV.state.calls[room] = members;
    if (room !== rtc.room) return;
    const others = members.filter((id) => id !== myId());
    others.forEach((id) => { if (!peers.has(id) && myId() < id) ensurePeer(id); });
    [...peers.keys()].filter((id) => !others.includes(id)).forEach(closePeer);
    rtc.onchange();
  };

  rtc.join = async function (room) {
    if (rtc.room === room) return;
    if (rtc.room) await rtc.leave();
    try {
      await openMic();
    } catch (e) {
      AV.toast("Sem acesso ao microfone: você ouvirá a chamada, mas não será ouvido.", "error");
    }
    rtc.room = room;
    audioContext();
    await AV.api("POST", "/api/call/state", { room, joined: true });
    (AV.state.calls[room] || []).filter((id) => id !== myId()).forEach(ensurePeer);
    vadTimer = setInterval(vadLoop, 80);
    rtc.onchange();
  };

  rtc.leave = async function () {
    const room = rtc.room;
    if (!room) return;
    if (rtc.localShare) rtc.stopShare();
    peers.forEach((p, id) => { signal(id, "bye", {}); });
    [...peers.keys()].forEach(closePeer);
    clearInterval(vadTimer);
    closeMic();
    rtc.room = null;
    rtc.pttDown = false;
    setSpeaking(myId(), false);
    try { await AV.api("POST", "/api/call/state", { room, joined: false }); } catch (e) { AV.fail(e); }
    rtc.onchange();
  };

  rtc.toggleMute = function () { rtc.muted = !rtc.muted; updateGate(); rtc.onchange(); };
  rtc.toggleDeafen = function () { rtc.deafened = !rtc.deafened; applyOutput(); rtc.onchange(); };
  rtc.toggleMuteAll = function () { rtc.muteAll = !rtc.muteAll; applyOutput(); rtc.onchange(); };
  rtc.setPtt = function (down) { rtc.pttDown = down; updateGate(); };
  rtc.hasMic = () => !!mic;

  function bitrateFor(step) {
    const res = cfg()["video.resolution"];
    const fps = num("video.fps");
    const custom = num("video.bitrate");
    return (custom > 0 ? custom * 1000 : BASE_BITRATE[res + "-" + fps]) * step.mult;
  }

  function ladder() {
    const fps = num("video.fps");
    return [
      { scale: 1, fps, mult: 1 },
      { scale: 1, fps: Math.min(fps, 30), mult: 0.7 },
      { scale: 1.5, fps: 30, mult: 0.5 },
      { scale: 2, fps: 24, mult: 0.35 },
      { scale: 3, fps: 15, mult: 0.2 },
    ];
  }

  function configureSender(sender, step) {
    const params = sender.getParameters();
    if (!params.encodings || !params.encodings.length) params.encodings = [{}];
    params.encodings[0].maxBitrate = Math.round(bitrateFor(step));
    params.encodings[0].maxFramerate = step.fps;
    params.encodings[0].scaleResolutionDownBy = step.scale;
    params.degradationPreference = "balanced";
    return sender.setParameters(params).catch(() => {});
  }

  function addShareTracks(p) {
    rtc.localShare.getTracks().forEach((t) => {
      const sender = p.pc.addTrack(t, rtc.localShare);
      p.shareSenders.push(sender);
      if (t.kind === "video") configureSender(sender, ladder()[level]);
    });
  }

  rtc.startShare = async function () {
    if (!rtc.room || rtc.localShare) return;
    const hd = cfg()["video.resolution"] === "1080";
    const fps = num("video.fps");
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: { width: { ideal: hd ? 1920 : 1280 }, height: { ideal: hd ? 1080 : 720 }, frameRate: { ideal: fps, max: fps } },
      audio: flag("video.systemAudio"),
    });
    const video = stream.getVideoTracks()[0];
    video.contentHint = "motion";
    video.onended = () => rtc.stopShare();
    rtc.localShare = stream;
    level = 0;
    good = 0;
    peers.forEach(addShareTracks);
    if (flag("video.auto")) adaptTimer = setInterval(adapt, 3000);
    rtc.onchange();
  };

  rtc.stopShare = function () {
    const stream = rtc.localShare;
    if (!stream) return;
    rtc.localShare = null;
    clearInterval(adaptTimer);
    peers.forEach((p) => {
      p.shareSenders.forEach((s) => { try { p.pc.removeTrack(s); } catch (e) { /* connection already closed */ } });
      p.shareSenders = [];
    });
    stream.getTracks().forEach((t) => t.stop());
    rtc.onchange();
  };

  async function adapt() {
    const first = [...peers.values()].find((p) => p.shareSenders.some((s) => s.track && s.track.kind === "video"));
    if (!first) return;
    const stats = await first.pc.getStats();
    let loss = 0;
    let rtt = 0;
    let limited = false;
    stats.forEach((r) => {
      if (r.type === "remote-inbound-rtp" && r.kind === "video") { loss = r.fractionLost || 0; rtt = r.roundTripTime || 0; }
      if (r.type === "outbound-rtp" && r.kind === "video" && r.qualityLimitationReason === "bandwidth") limited = true;
    });
    const steps = ladder();
    if (loss > 0.06 || limited || rtt > 0.4) {
      good = 0;
      if (level < steps.length - 1) { level++; applyLevel(steps[level]); }
    } else if (++good >= 5 && level > 0) {
      good = 0;
      level--;
      applyLevel(steps[level]);
    }
  }

  function applyLevel(step) {
    peers.forEach((p) => p.shareSenders.forEach((s) => { if (s.track && s.track.kind === "video") configureSender(s, step); }));
    rtc.onchange();
  }

  rtc.quality = () => {
    const s = ladder()[level];
    return { level, scale: s.scale, fps: s.fps };
  };

  rtc.videoTiles = function () {
    const tiles = [];
    if (rtc.localShare) tiles.push({ id: myId(), local: true, stream: rtc.localShare });
    peers.forEach((p) => p.videos.forEach((stream, key) => tiles.push({ id: p.id, key, local: false, stream })));
    return tiles;
  };

  rtc.participants = () => (rtc.room ? [myId(), ...peers.keys()] : []);

})();
