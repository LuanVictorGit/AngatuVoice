"use strict";
/*
 * Runs the video card's encoder or decoder on a thread of its own, talking straight to the server: the picture keeps its
 * pace while the interface is busy, and the interface never waits for a frame. One worker per direction.
 * A frame on the wire: one flags byte (bit 0 = keyframe), an 8-byte timestamp, then the encoded bytes.
 */
const CODEC = "avc1.640033";
const HEADER_BYTES = 9;
const KEYFRAME_EVERY_MS = 5000;
const KEY_REQUEST_EVERY_MS = 1000;
const MAX_ENCODE_QUEUE = 2;
const MAX_DECODE_QUEUE = 4;
const REPORT_EVERY_MS = 1000;
const PACING_SLACK = 0.8;
const CONGESTED_BYTES = 1024 * 1024;
const OVERFLOW_BYTES = 4 * 1024 * 1024;
const ADAPT_EVERY_MS = 1000;
const RECOVER_AFTER_MS = 6000;
const MIN_BITRATE = 1500000;
const BITRATE_DOWN = 0.7;
const BITRATE_UP = 1.15;
const MAX_BITRATE = 16000000;
const CONNECT_RETRIES = 3;
const ENCODER_SILENT_MS = 4000;
const CLOSE_NO_STREAM = 4404;

const KEY_ANSWER_MS = 1500;
const NAL_SPS = 7;
const NAL_PPS = 8;

let role = null;

/* The positions of each NAL unit in an Annex B buffer. */
function nalUnits(data) {
  const units = [];
  for (let i = 0; i + 3 < data.length; i++) {
    if (data[i] === 0 && data[i + 1] === 0 && (data[i + 2] === 1 || (data[i + 2] === 0 && data[i + 3] === 1))) {
      const start = data[i + 2] === 1 ? i : i;
      const header = data[i + 2] === 1 ? i + 3 : i + 4;
      if (units.length) units[units.length - 1].end = start;
      units.push({ start, type: data[header] & 0x1f, end: data.length });
      i = header;
    }
  }
  return units;
}

/*
 * Without the stream's parameters a decoder cannot start, and it says nothing: it just never produces a picture.
 * Some cards send them only with the first keyframe, so anyone joining later would never see anything. They are kept
 * from the keyframe that has them and put back in front of every keyframe that does not.
 */
function withParameters(data, kept) {
  const units = nalUnits(data);
  const has = (type) => units.some((u) => u.type === type);
  if (has(NAL_SPS) && has(NAL_PPS)) {
    const parts = units.filter((u) => u.type === NAL_SPS || u.type === NAL_PPS).map((u) => data.subarray(u.start, u.end));
    kept.bytes = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
    let at = 0;
    for (const part of parts) { kept.bytes.set(part, at); at += part.length; }
    return data;
  }
  if (!kept.bytes) return data;
  const joined = new Uint8Array(kept.bytes.length + data.length);
  joined.set(kept.bytes);
  joined.set(data, kept.bytes.length);
  return joined;
}

if (typeof module !== "undefined") module.exports = { nalUnits, withParameters };

onmessage = (e) => {
  const m = e.data;
  if (m.type === "encode") role = encoderRole(m);
  else if (m.type === "decode") role = decoderRole(m);
  else if (role) role(m);
};

function encoderRole({ url, stream, readable, codec, target: firstTarget }) {
  let target = firstTarget;
  let bitrate = Math.min(MAX_BITRATE, target.bitrate);
  let source = null;
  let forceKey = true;
  let skipping = false;
  let lastKeyAt = 0;
  let lastEncodedAt = 0;
  let configureAgain = false;
  let running = true;
  let fed = 0;
  let firstFedAt = 0;
  let frames = 0;
  let bytes = 0;
  let calmSince = 0;
  let socket = null;
  let attempts = 0;
  let produced = false;
  let keys = 0;
  let keyWanted = 0;
  let lastKeyOutAt = 0;
  const parameters = { bytes: null };
  const reader = readable.getReader();

  function fail(reason) {
    if (running) postMessage({ type: "failed", reason: String(reason) });
    running = false;
  }

  function connect() {
    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";
    ws.onopen = () => ws.send(JSON.stringify({ role: "publish", stream }));
    ws.onmessage = (e) => {
      if (e.data === "ready") { socket = ws; attempts = 0; forceKey = true; }
      else if (e.data === "key") forceKey = true;
      else if (e.data === "slow") lowerBitrate();
    };
    ws.onclose = () => {
      socket = null;
      if (!running) return;
      if (++attempts > CONNECT_RETRIES) fail("server unreachable");
      else setTimeout(connect, 1000 * attempts);
    };
  }

  function lowerBitrate() {
    calmSince = performance.now();
    const lowered = Math.max(MIN_BITRATE, Math.round(bitrate * BITRATE_DOWN));
    if (lowered !== bitrate) { bitrate = lowered; configureAgain = true; }
  }

  /* The upload is one path shared by everyone watching; a full buffer here is how a narrow one shows itself. */
  const adapter = setInterval(() => {
    const now = performance.now();
    if (socket && socket.bufferedAmount > CONGESTED_BYTES) lowerBitrate();
    else if (bitrate < Math.min(MAX_BITRATE, target.bitrate) && now - calmSince >= RECOVER_AFTER_MS) {
      calmSince = now;
      bitrate = Math.min(MAX_BITRATE, target.bitrate, Math.round(bitrate * BITRATE_UP));
      configureAgain = true;
    }
    // A card that accepts frames and never returns one must not leave people looking at nothing: WebRTC takes over.
    if (!produced && fed > 0 && now - firstFedAt > ENCODER_SILENT_MS) fail("encoder took " + fed + " frames and returned none");
    postMessage({ type: "counters", frames, bytes, keys });
  }, ADAPT_EVERY_MS);

  const encoder = new VideoEncoder({
    output(chunk) {
      produced = true;
      const key = chunk.type === "key";
      if (key) { keyWanted = 0; lastKeyOutAt = performance.now(); }
      if (!socket) return;
      if (socket.bufferedAmount > OVERFLOW_BYTES) { skipping = true; forceKey = true; return; }
      // Once a frame is left behind, everything until the next keyframe is useless to whoever decodes this.
      if (skipping && !key) return;
      skipping = false;
      let data = new Uint8Array(chunk.byteLength);
      chunk.copyTo(data);
      if (key) data = withParameters(data, parameters);
      const packet = new Uint8Array(HEADER_BYTES + data.length);
      const view = new DataView(packet.buffer);
      view.setUint8(0, key ? 1 : 0);
      view.setFloat64(1, chunk.timestamp);
      packet.set(data, HEADER_BYTES);
      socket.send(packet);
      frames++;
      if (key) keys++;
      bytes += data.length;
    },
    error: fail,
  });

  function configure(frame) {
    const height = Math.min(frame.displayHeight, target.height);
    const width = Math.round((frame.displayWidth * height) / frame.displayHeight);
    const size = { width: width & ~1, height: height & ~1 };
    source = { width: frame.displayWidth, height: frame.displayHeight };
    encoder.configure({ codec, ...size, framerate: target.fps, bitrate, bitrateMode: "variable", latencyMode: "realtime", hardwareAcceleration: "prefer-hardware", avc: { format: "annexb" } });
    forceKey = true;
    postMessage({ type: "size", ...size });
  }

  connect();
  (async () => {
    while (running) {
      const { value: frame, done } = await reader.read();
      if (done) return;
      if (!running) { frame.close(); return; }
      const now = performance.now();
      // Some cards ignore the keyframe flag, and a viewer without one sees nothing. A fresh encoder always opens with a keyframe.
      if (keyWanted && now - keyWanted > KEY_ANSWER_MS) {
        keyWanted = 0;
        encoder.reset();
        source = null;
      }
      if (!source || configureAgain || frame.displayWidth !== source.width || frame.displayHeight !== source.height) {
        configureAgain = false;
        configure(frame);
      }
      // Behind already, nobody connected, or ahead of the target rate: this frame goes.
      // The count that matters is frames waiting to enter: Intel and AMD cards keep several inside before the first comes out,
      // and counting those as "behind" starves the encoder of the very frames it is waiting for.
      if (!socket || encoder.encodeQueueSize >= MAX_ENCODE_QUEUE || now - lastEncodedAt < (1000 / target.fps) * PACING_SLACK) { frame.close(); continue; }
      lastEncodedAt = now;
      const keyFrame = forceKey || now - lastKeyAt >= KEYFRAME_EVERY_MS;
      if (keyFrame) { forceKey = false; lastKeyAt = now; if (!keyWanted) keyWanted = now; }
      if (!fed++) firstFedAt = now;
      encoder.encode(frame, { keyFrame });
      frame.close();
    }
  })().catch(fail);

  return (m) => {
    if (m.type === "key") forceKey = true;
    else if (m.type === "target") { target = m.target; bitrate = Math.min(MAX_BITRATE, target.bitrate); configureAgain = true; }
    else if (m.type === "stop") {
      running = false;
      clearInterval(adapter);
      reader.cancel().catch(() => {});
      if (socket) socket.close();
      if (encoder.state !== "closed") encoder.close();
      close();
    }
  };
}

function decoderRole({ url, stream, writable }) {
  const writer = writable.getWriter();
  const counters = { decoded: 0, bytes: 0, width: 0, height: 0, received: 0, keys: 0 };
  let waitingKey = true;
  let keyAskedAt = 0;
  let closed = false;
  let socket = null;

  const decoder = new VideoDecoder({
    output(frame) {
      counters.decoded++;
      counters.width = frame.displayWidth;
      counters.height = frame.displayHeight;
      // A late picture is worth less than a stutter: when the element cannot keep up, this frame goes.
      if (writer.desiredSize !== null && writer.desiredSize <= -2) { frame.close(); return; }
      writer.write(frame).catch(() => {});
    },
    error(error) {
      if (!closed) postMessage({ type: "failed", reason: String(error) });
    },
  });

  const report = setInterval(() => postMessage({ type: "counters", counters: { ...counters } }), REPORT_EVERY_MS);

  function askForKey() {
    waitingKey = true;
    const now = performance.now();
    if (now - keyAskedAt >= KEY_REQUEST_EVERY_MS && socket && socket.readyState === WebSocket.OPEN) {
      keyAskedAt = now;
      socket.send("key");
    }
  }

  function take(buffer) {
    if (buffer.byteLength <= HEADER_BYTES) return;
    const view = new DataView(buffer);
    const key = (view.getUint8(0) & 1) === 1;
    counters.received++;
    if (key) counters.keys++;
    if ((waitingKey && !key) || (decoder.decodeQueueSize > MAX_DECODE_QUEUE && !key)) { askForKey(); return; }
    if (decoder.state !== "configured") {
      if (!key) return;
      decoder.configure({ codec: CODEC, hardwareAcceleration: "prefer-hardware", optimizeForLatency: true });
    }
    counters.bytes += buffer.byteLength - HEADER_BYTES;
    waitingKey = false;
    decoder.decode(new EncodedVideoChunk({ type: key ? "key" : "delta", timestamp: view.getFloat64(1), data: new Uint8Array(buffer, HEADER_BYTES) }));
  }

  function connect() {
    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";
    socket = ws;
    ws.onopen = () => ws.send(JSON.stringify({ role: "watch", stream }));
    ws.onmessage = (e) => { if (e.data instanceof ArrayBuffer) take(e.data); };
    // The person may not have reached the server yet; a dropped socket picks up at the next keyframe.
    ws.onclose = () => {
      if (closed) return;
      waitingKey = true;
      setTimeout(() => { if (!closed) connect(); }, 1000);
    };
  }

  connect();

  return (m) => {
    if (m.type === "askKey") askForKey();
    else if (m.type === "stop") {
      closed = true;
      clearInterval(report);
      if (socket) socket.close();
      if (decoder.state !== "closed") decoder.close();
      writer.close().catch(() => {});
      close();
    }
  };
}
