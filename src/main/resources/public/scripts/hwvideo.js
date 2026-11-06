"use strict";
/*
 * Shared screens go through the server, encoded on the video card. This Chromium build has no H.264 in WebRTC, but WebCodecs
 * reaches the hardware encoder and decoder (NVENC, Quick Sync, AMF). Measured at 1080p60 on a GTX 1660 SUPER: H.264 hardware
 * 56 FPS at 7.6 Mbps, against VP9 24 FPS and AV1 9 FPS in software. One upload serves everyone watching.
 * The heavy work and the socket live in hwvideo-worker.js, on threads of their own; this side only starts and stops them.
 */
(function () {
  // High, then Main, then Baseline: older integrated cards do not always take the first ones.
  const CODECS = ["avc1.640033", "avc1.4d0033", "avc1.420033"];
  const PROBE = { width: 1920, height: 1080, framerate: 30, bitrate: 4000000, hardwareAcceleration: "prefer-hardware", latencyMode: "realtime", avc: { format: "annexb" } };
  const WORKER = "/scripts/hwvideo-worker.js";

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

  function newStreamId() {
    return [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  hw.createSender = function ({ url, track, codec, target, onfail }) {
    const viewers = new Set();
    const worker = new Worker(WORKER);
    const readable = new MediaStreamTrackProcessor({ track }).readable;
    const id = newStreamId();
    const totals = { frames: 0, bytes: 0, width: 0, height: 0 };
    let running = true;

    function stop() {
      if (!running) return;
      running = false;
      worker.postMessage({ type: "stop" });
      viewers.clear();
    }

    function fail(reason) {
      if (!running) return;
      console.warn("share through the server failed", reason);
      stop();
      worker.terminate();
      onfail();
    }

    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "counters") { totals.frames = m.frames; totals.bytes = m.bytes; }
      else if (m.type === "size") { totals.width = m.width; totals.height = m.height; }
      else if (m.type === "failed") fail(m.reason);
    };
    worker.onerror = (e) => fail(e.message);
    worker.postMessage({ type: "encode", url, stream: id, readable, codec, target: target() }, [readable]);

    return {
      id,
      add(viewer) { viewers.add(viewer); },
      remove(viewer) { viewers.delete(viewer); },
      has: (viewer) => viewers.has(viewer),
      empty: () => viewers.size === 0,
      counters: (viewer) => (viewers.has(viewer) && totals.width ? { ...totals } : null),
      retarget() { worker.postMessage({ type: "target", target: target() }); },
      stop,
    };
  };

  hw.createReceiver = function ({ url, stream, onfail }) {
    const generator = new MediaStreamTrackGenerator({ kind: "video" });
    const writable = generator.writable;
    const worker = new Worker(WORKER);
    const counters = { decoded: 0, bytes: 0, width: 0, height: 0, received: 0, keys: 0 };
    let closed = false;

    function fail(reason) {
      if (closed) return;
      console.warn("hardware decoder failed", reason);
      onfail();
    }

    worker.onmessage = (e) => {
      const m = e.data;
      if (m.type === "counters") Object.assign(counters, m.counters);
      else if (m.type === "failed") fail(m.reason);
    };
    worker.onerror = (e) => fail(e.message);
    worker.postMessage({ type: "decode", url, stream, writable }, [writable]);

    return {
      stream: new MediaStream([generator]),
      counters,
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
})();
