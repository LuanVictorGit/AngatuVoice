"use strict";
/*
 * Runs the video card's encoder or decoder on a thread of its own: the picture keeps its pace while the interface is busy,
 * and the interface never waits for a frame. One worker per direction: one for the share, one per share being watched.
 */
const CODEC = "avc1.640033";
const HEADER_BYTES = 18;
const FRAGMENT_BYTES = 16 * 1024;
const KIND_FRAGMENT = 1;
const KEYFRAME_EVERY_MS = 5000;
const KEY_REQUEST_EVERY_MS = 1000;
const MAX_ENCODE_QUEUE = 2;
const MAX_DECODE_QUEUE = 4;
const PACING_SLACK = 0.8;
const REPORT_EVERY_MS = 1000;

let role = null;

onmessage = (e) => {
  const m = e.data;
  if (m.type === "encode") role = encoderRole(m);
  else if (m.type === "decode") role = decoderRole(m);
  else if (role) role(m);
};

function encoderRole({ readable, codec, target: firstTarget }) {
  let target = firstTarget;
  let bitrate = target.bitrate;
  let source = null;
  let frameId = 0;
  let forceKey = true;
  let lastKeyAt = 0;
  let lastEncodedAt = 0;
  let configureAgain = false;
  let running = true;
  let pending = 0;
  const reader = readable.getReader();

  const encoder = new VideoEncoder({
    output(chunk) {
      pending--;
      const id = ++frameId;
      const key = chunk.type === "key";
      const body = new Uint8Array(chunk.byteLength);
      chunk.copyTo(body);
      const count = Math.max(1, Math.ceil(body.length / FRAGMENT_BYTES));
      const packets = [];
      for (let i = 0; i < count; i++) {
        const piece = body.subarray(i * FRAGMENT_BYTES, Math.min(body.length, (i + 1) * FRAGMENT_BYTES));
        const packet = new Uint8Array(HEADER_BYTES + piece.length);
        const view = new DataView(packet.buffer);
        view.setUint8(0, KIND_FRAGMENT);
        view.setUint8(1, key ? 1 : 0);
        view.setUint32(2, id);
        view.setUint16(6, i);
        view.setUint16(8, count);
        view.setFloat64(10, chunk.timestamp);
        packet.set(piece, HEADER_BYTES);
        packets.push(packet.buffer);
      }
      postMessage({ type: "frame", key, packets, bytes: body.length }, packets);
    },
    error(error) {
      if (running) postMessage({ type: "failed", reason: String(error) });
      running = false;
    },
  });

  function configure(frame) {
    const height = Math.min(frame.displayHeight, target.height);
    const width = Math.round((frame.displayWidth * height) / frame.displayHeight);
    const size = { width: width & ~1, height: height & ~1 };
    source = { width: frame.displayWidth, height: frame.displayHeight };
    bitrate = Math.min(bitrate, target.bitrate);
    encoder.configure({ codec, ...size, framerate: target.fps, bitrate, bitrateMode: "variable", latencyMode: "realtime", hardwareAcceleration: "prefer-hardware", avc: { format: "annexb" } });
    forceKey = true;
    postMessage({ type: "size", ...size });
  }

  (async () => {
    while (running) {
      const { value: frame, done } = await reader.read();
      if (done) return;
      if (!running) { frame.close(); return; }
      if (!source || configureAgain || frame.displayWidth !== source.width || frame.displayHeight !== source.height) {
        configureAgain = false;
        configure(frame);
      }
      // Behind already: this frame goes, instead of every later one arriving late.
      if (pending >= MAX_ENCODE_QUEUE) { frame.close(); continue; }
      const now = performance.now();
      // The capture runs ahead of the target on purpose; the small slack keeps a frame a hair early from being thrown away.
      if (now - lastEncodedAt < (1000 / target.fps) * PACING_SLACK) { frame.close(); continue; }
      lastEncodedAt = now;
      const keyFrame = forceKey || now - lastKeyAt >= KEYFRAME_EVERY_MS;
      if (keyFrame) { forceKey = false; lastKeyAt = now; }
      pending++;
      encoder.encode(frame, { keyFrame });
      frame.close();
    }
  })().catch((error) => {
    if (running) postMessage({ type: "failed", reason: String(error) });
  });

  return (m) => {
    if (m.type === "key") forceKey = true;
    else if (m.type === "bitrate") { bitrate = m.value; configureAgain = true; }
    else if (m.type === "target") { target = m.target; bitrate = target.bitrate; configureAgain = true; }
    else if (m.type === "stop") {
      running = false;
      reader.cancel().catch(() => {});
      if (encoder.state !== "closed") encoder.close();
      close();
    }
  };
}

function decoderRole({ writable }) {
  const writer = writable.getWriter();
  const partial = new Map();
  const counters = { decoded: 0, bytes: 0, width: 0, height: 0 };
  let lastComplete = 0;
  let waitingKey = true;
  let keyAskedAt = 0;
  let closed = false;

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
    if (now - keyAskedAt >= KEY_REQUEST_EVERY_MS) {
      keyAskedAt = now;
      postMessage({ type: "needKey" });
    }
  }

  function decode(entry) {
    if (waitingKey && !entry.key) { askForKey(); return; }
    if (decoder.decodeQueueSize > MAX_DECODE_QUEUE && !entry.key) { askForKey(); return; }
    if (decoder.state !== "configured") {
      if (!entry.key) return;
      decoder.configure({ codec: CODEC, hardwareAcceleration: "prefer-hardware", optimizeForLatency: true });
    }
    const data = new Uint8Array(entry.total);
    let at = 0;
    for (const part of entry.parts) { data.set(part, at); at += part.length; }
    counters.bytes += entry.total;
    waitingKey = false;
    decoder.decode(new EncodedVideoChunk({ type: entry.key ? "key" : "delta", timestamp: entry.timestamp, data }));
  }

  function take(buffer) {
    if (buffer.byteLength < HEADER_BYTES) return;
    const view = new DataView(buffer);
    if (view.getUint8(0) !== KIND_FRAGMENT) return;
    const id = view.getUint32(2);
    const index = view.getUint16(6);
    const count = view.getUint16(8);
    if (id <= lastComplete || index >= count) return;
    let entry = partial.get(id);
    if (!entry) {
      entry = { parts: new Array(count), got: 0, total: 0, key: view.getUint8(1) === 1, timestamp: view.getFloat64(10) };
      partial.set(id, entry);
    }
    if (entry.parts[index]) return;
    const piece = new Uint8Array(buffer, HEADER_BYTES);
    entry.parts[index] = piece;
    entry.got++;
    entry.total += piece.length;
    if (entry.got < count) return;
    // The channel is unordered, so an older frame can still be on its way; only one far behind is given up as lost.
    partial.delete(id);
    if (lastComplete !== 0 && id !== lastComplete + 1 && !entry.key) waitingKey = true;
    lastComplete = id;
    for (const older of [...partial.keys()]) {
      if (older < id) partial.delete(older);
    }
    decode(entry);
  }

  return (m) => {
    if (m.type === "data") take(m.buffer);
    else if (m.type === "askKey") askForKey();
    else if (m.type === "stop") {
      closed = true;
      clearInterval(report);
      if (decoder.state !== "closed") decoder.close();
      writer.close().catch(() => {});
      partial.clear();
      close();
    }
  };
}
