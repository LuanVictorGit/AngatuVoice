"use strict";
/* Plays the 48 kHz stereo PCM that SystemAudio streams. A short cushion absorbs network jitter; past the cap the oldest audio is dropped so delay never grows. */
const CUSHION = 2880;
const CAP = 14400;

class SystemAudioPlayer extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ring = new Float32Array(CAP * 2 * 2);
    this.read = 0;
    this.size = 0;
    this.primed = false;
    this.port.onmessage = (e) => this.push(new Int16Array(e.data));
  }

  push(pcm) {
    const frames = pcm.length >> 1;
    const length = this.ring.length >> 1;
    for (let i = 0; i < frames; i++) {
      const at = ((this.read + this.size) % length) * 2;
      this.ring[at] = pcm[i * 2] / 32768;
      this.ring[at + 1] = pcm[i * 2 + 1] / 32768;
      if (this.size < length) this.size++;
      else this.read = (this.read + 1) % length;
    }
    if (this.size > CAP) {
      this.read = (this.read + this.size - CUSHION) % length;
      this.size = CUSHION;
    }
    if (this.size >= CUSHION) this.primed = true;
  }

  process(inputs, outputs) {
    const [left, right] = outputs[0];
    const length = this.ring.length >> 1;
    for (let i = 0; i < left.length; i++) {
      if (!this.primed || this.size === 0) {
        this.primed = false;
        left[i] = 0;
        if (right) right[i] = 0;
        continue;
      }
      left[i] = this.ring[this.read * 2];
      if (right) right[i] = this.ring[this.read * 2 + 1];
      this.read = (this.read + 1) % length;
      this.size--;
    }
    return true;
  }
}

registerProcessor("system-audio-player", SystemAudioPlayer);
