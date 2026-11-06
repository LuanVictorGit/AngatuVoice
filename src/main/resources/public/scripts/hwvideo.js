"use strict";
/*
 * Screen sharing through the video card. This Chromium build has no H.264 in WebRTC, but WebCodecs reaches the hardware
 * encoder and decoder (NVENC, Quick Sync, AMF), so the picture is encoded here and carried over a data channel.
 * Measured at 1080p60 on a GTX 1660 SUPER: H.264 hardware 56 FPS at 7.6 Mbps, against VP9 24 FPS and AV1 9 FPS in software.
 * One encoder serves every viewer, where WebRTC costs one software encoder per viewer.
 * The heavy work lives in hwvideo-worker.js; this side only moves packets, because a data channel cannot leave this thread.
 */
(function () {
  // High, then Main, then Baseline: older integrated cards do not always take the first ones.
  const CODECS = ["avc1.640033", "avc1.4d0033", "avc1.420033"];
  const PROBE = { width: 1920, height: 1080, framerate: 30, bitrate: 4000000, hardwareAcceleration: "prefer-hardware", latencyMode: "realtime", avc: { format: "annexb" } };
  const WORKER = "/scripts/hwvideo-worker.js";
  const KIND_KEY_REQUEST = 2;
  const CONGESTED_BYTES = 1024 * 1024;
  const OVERFLOW_BYTES = 4 * 1024 * 1024;
  const ADAPT_EVERY_MS = 1000;
  const RECOVER_AFTER_MS = 6000;
  const MIN_BITRATE = 1500000;
  const BITRATE_DOWN = 0.7;
  const BITRATE_UP = 1.15;

  const hw = AV.hwVideo = {};
  let support = null;

  async function encoderCodec() {
    for (const codec of CODECS) {
      try {
        if ((await VideoEncoder.isConfigSupported({ ...PROBE, codec })).supported) return codec;
      } catch (e) {
        console.warn("encoder probe failed", codec, e);
      }
    }
    return null;
  }

  /* VideoDecoder.isConfigSupported says no here even though the card decodes it; this is the answer that matches what happens. */
  async function decodesH264() {
    try {
      const info = await navigator.mediaCapabilities.decodingInfo({ type: "webrtc", video: { contentType: "video/H264;profile-level-id=640033;packetization-mode=1", width: 1920, height: 1080, bitrate: 8000000, framerate: 60 } });
      return info.supported && info.powerEfficient;
    } catch (e) {
      console.warn("decoder probe failed", e);
      return false;
    }
  }

  /* Without H.264 in this build a supported answer can only mean the video card, so no software path hides behind it. */
  hw.support = function () {
    if (!support) {
      support = (async () => {
        if (!window.VideoEncoder || !window.VideoDecoder || !window.MediaStreamTrackProcessor || !window.MediaStreamTrackGenerator || !window.Worker) return { encode: null, decode: false };
        return { encode: await encoderCodec(), decode: await decodesH264() };
      })();
    }
    return support;
  };

  /* ---- sending ---- */

  hw.createSender = function ({ track, codec, target, onfail }) {
    const viewers = new Map();
    const worker = new Worker(WORKER);
    const readable = new MediaStreamTrackProcessor({ track }).readable;
    let size = null;
    let running = true;
    const first = target();
    let bitrate = first.bitrate;
    let topBitrate = first.bitrate;
    let calmSince = 0;

    function sendFrame(m) {
      viewers.forEach((v) => {
        if (v.channel.readyState !== "open") return;
        // A viewer whose path is full gets nothing until the next keyframe, so it never decodes a frame with a hole behind it.
        if (v.channel.bufferedAmount > OVERFLOW_BYTES) { v.skipping = true; worker.postMessage({ type: "key" }); return; }
        if (v.skipping && !m.key) return;
        v.skipping = false;
        try {
          for (const packet of m.packets) v.channel.send(packet);
          v.frames++;
          v.bytes += m.bytes;
        } catch (e) {
          v.skipping = true;
        }
      });
    }

    function stop() {
      if (!running) return;
      running = false;
      clearInterval(adaptTimer);
      worker.postMessage({ type: "stop" });
      viewers.clear();
    }

    function fail(reason) {
      if (!running) return;
      console.warn("hardware encoder failed", reason);
      stop();
      worker.terminate();
      onfail();
    }

    /* The bitrate is shared, so it follows the viewer with the narrowest path; the buffer filling up is how that path shows itself. */
    function adapt() {
      let buffered = 0;
      viewers.forEach((v) => { buffered = Math.max(buffered, v.channel.bufferedAmount); });
      const now = performance.now();
      let next = bitrate;
      if (buffered > CONGESTED_BYTES) {
        calmSince = now;
        next = Math.max(MIN_BITRATE, Math.round(bitrate * BITRATE_DOWN));
      } else if (bitrate < topBitrate && now - calmSince >= RECOVER_AFTER_MS) {
        calmSince = now;
        next = Math.min(topBitrate, Math.round(bitrate * BITRATE_UP));
      }
      if (next !== bitrate) {
        bitrate = next;
        worker.postMessage({ type: "bitrate", value: bitrate });
      }
    }

    const adaptTimer = setInterval(adapt, ADAPT_EVERY_MS);
    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "frame") sendFrame(m);
      else if (m.type === "size") size = { width: m.width, height: m.height };
      else if (m.type === "failed") fail(m.reason);
    };
    worker.onerror = (e) => fail(e.message);
    worker.postMessage({ type: "encode", readable, codec, target: first }, [readable]);

    return {
      add(id, channel) {
        if (!viewers.has(id)) viewers.set(id, { channel, skipping: false, frames: 0, bytes: 0 });
        worker.postMessage({ type: "key" });
      },
      remove(id) { viewers.delete(id); },
      has: (id) => viewers.has(id),
      empty: () => viewers.size === 0,
      counters: (id) => {
        const v = viewers.get(id);
        return v && size ? { frames: v.frames, bytes: v.bytes, width: size.width, height: size.height } : null;
      },
      requestKey() { worker.postMessage({ type: "key" }); },
      retarget() {
        const next = target();
        topBitrate = next.bitrate;
        bitrate = next.bitrate;
        worker.postMessage({ type: "target", target: next });
      },
      stop,
    };
  };

  hw.requestKey = function (channel) {
    if (channel.readyState !== "open") return;
    try {
      channel.send(new Uint8Array([KIND_KEY_REQUEST]));
    } catch (e) {
      console.warn("key request not sent", e);
    }
  };

  /* ---- receiving ---- */

  hw.createReceiver = function ({ channel, onfail }) {
    const generator = new MediaStreamTrackGenerator({ kind: "video" });
    const writable = generator.writable;
    const worker = new Worker(WORKER);
    const counters = { decoded: 0, bytes: 0, width: 0, height: 0 };
    let closed = false;

    function fail(reason) {
      if (closed) return;
      console.warn("hardware decoder failed", reason);
      onfail();
    }

    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "counters") Object.assign(counters, m.counters);
      else if (m.type === "needKey") hw.requestKey(channel);
      else if (m.type === "failed") fail(m.reason);
    };
    worker.onerror = (e) => fail(e.message);
    worker.postMessage({ type: "decode", writable }, [writable]);

    return {
      stream: new MediaStream([generator]),
      counters,
      take(buffer) {
        if (!closed) worker.postMessage({ type: "data", buffer }, [buffer]);
      },
      askForKey() {
        if (!closed) worker.postMessage({ type: "askKey" });
      },
      close() {
        if (closed) return;
        closed = true;
        worker.postMessage({ type: "stop" });
        generator.stop();
      },
    };
  };

  hw.isKeyRequest = (buffer) => buffer.byteLength === 1 && new Uint8Array(buffer)[0] === KIND_KEY_REQUEST;
})();
