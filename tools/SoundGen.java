import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;

/**
 * Generates every interface sound of the app from one set of rules, so that a sound's meaning can be read from its shape.
 * Rising means on, in or started; falling means off, out or ended. Timbre tells who it is about and note count tells how much it matters.
 * Run: java tools/SoundGen.java src/main/resources/public/assets/audio (needs ffmpeg on the PATH).
 *
 * @author Angatu Sistemas
 */
public class SoundGen {
    static final int RATE = 44100;
    static final double G3 = 196.00, D4 = 293.66, E4 = 329.63, G4 = 392.00, A4 = 440.00, B4 = 493.88,
        C5 = 523.25, D5 = 587.33, E5 = 659.25, G5 = 783.99, A5 = 880.00, C6 = 1046.50;

    record Timbre(double[] partials, double decay, double attack) {}

    static final Timbre SELF = new Timbre(new double[]{1, 0.30, 0.08}, 0.11, 0.004);          // your own microphone and headset
    static final Timbre SESSION = new Timbre(new double[]{1, 0.45, 0.18, 0.06}, 0.30, 0.004); // you entering or ending a call
    static final Timbre PEOPLE = new Timbre(new double[]{1, 0.12, 0.35, 0.05}, 0.20, 0.006);  // other people, hollow and wooden
    static final Timbre SHARE = new Timbre(new double[]{1, 0.55, 0.30, 0.15, 0.08}, 0.18, 0.012); // screen sharing, reedy
    static final Timbre BELL = new Timbre(new double[]{1, 0.20, 0.42, 0.10, 0.16}, 0.42, 0.003);  // something asking for attention
    static final Timbre BUZZ = new Timbre(new double[]{1, 0.70, 0.55, 0.40, 0.25, 0.15}, 0.09, 0.003); // an error
    static final Timbre TICK = new Timbre(new double[]{1, 0.15}, 0.05, 0.002);                // a minor local choice

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        // Your own controls: off falls and on rises. The headset sits an octave below the microphone, with one more note.
        cue(root, "interface/mute", -21, notes(SELF, 0.075, G5, D5));
        cue(root, "interface/unmute", -21, notes(SELF, 0.075, D5, G5));
        cue(root, "interface/deafen", -21, notes(SELF, 0.115, G4, D4, G3));
        cue(root, "interface/undeafen", -21, notes(SELF, 0.115, G3, D4, G4));
        // Silencing someone else only changes what you hear, so it must not sound like your own microphone.
        cue(root, "interface/peer-muted", -25, notes(TICK, 0.05, A4));
        cue(root, "interface/peer-unmuted", -25, notes(TICK, 0.05, E5));
        // Low and dissonant: a semitone clash, the one sound here that is meant to be unpleasant.
        cue(root, "interface/error", -19, chordHit(BUZZ, 0.13, G3, 207.65), chordHit(BUZZ, 0.16, G3, 207.65));
        // You in a call: a major arpeggio and its exact mirror.
        cue(root, "calls/call-joined", -20, notes(SESSION, 0.085, C5, E5, G5));
        cue(root, "calls/call-ended", -20, notes(SESSION, 0.085, G5, E5, C5));
        // Someone else in the call: a wider interval, another timbre and a lower register than your own controls.
        cue(root, "calls/peer-joined", -21, notes(PEOPLE, 0.10, E4, B4));
        cue(root, "calls/peer-left", -21, notes(PEOPLE, 0.10, B4, E4));
        // Screen sharing: stacked fifths for a broadcast, single notes for one viewer.
        cue(root, "notifications/stream-started", -22, chordRun(SHARE, 0.11, new double[][]{{D4, A4}, {G4, D5}, {C5, G5}}));
        cue(root, "notifications/stream-stopped", -22, chordRun(SHARE, 0.11, new double[][]{{C5, G5}, {G4, D5}, {D4, A4}}));
        cue(root, "notifications/viewer-joined", -24, notes(SHARE, 0.07, G4, D5));
        cue(root, "notifications/viewer-left", -24, notes(SHARE, 0.07, D5, G4));
        // Bells ask for attention: a message rises softly, a missed call falls, the network climbs to its octave.
        cue(root, "notifications/message-received", -22, notes(BELL, 0.09, E5, A5));
        cue(root, "notifications/call-missed", -21, notes(BELL, 0.16, C6, A5, E5));
        cue(root, "notifications/network-connected", -21, notes(BELL, 0.12, C5, G5, C6));
        // The Brazilian telephone tones of Anatel Resolution 473/2007: 425 Hz, ringing 1 s on and 4 s off, busy 250 ms on and off.
        cue(root, "calls/call-ringback", -20, tone(425, 1.0), silence(4.0));
        cue(root, "calls/call-busy", -20, tone(425, 0.25), silence(0.25), tone(425, 0.25), silence(0.25), tone(425, 0.25), silence(0.05));
        // A second call while you talk: two short soft beeps that stay out of the way of the voices, then a long silence.
        cue(root, "calls/call-waiting", -26, tone(425, 0.14), silence(0.14), tone(425, 0.14), silence(5.0));
    }

    static double[] notes(Timbre t, double step, double... frequencies) {
        double tail = t.decay() * 1.6;
        double[] out = new double[(int) ((step * (frequencies.length - 1) + tail) * RATE)];
        for (int i = 0; i < frequencies.length; i++) add(out, voice(t, frequencies[i], tail), (int) (i * step * RATE));
        return out;
    }

    static double[] chordHit(Timbre t, double length, double... frequencies) {
        double[] out = new double[(int) (length * RATE)];
        for (double f : frequencies) add(out, voice(t, f, length), 0);
        return out;
    }

    static double[] chordRun(Timbre t, double step, double[][] chords) {
        double tail = t.decay() * 1.6;
        double[] out = new double[(int) ((step * (chords.length - 1) + tail) * RATE)];
        for (int i = 0; i < chords.length; i++) {
            for (double f : chords[i]) add(out, voice(t, f, tail), (int) (i * step * RATE));
        }
        return out;
    }

    // One struck note; high partials die first, as they do on a real instrument.
    static double[] voice(Timbre t, double frequency, double length) {
        double[] out = new double[(int) (length * RATE)];
        for (int n = 0; n < out.length; n++) {
            double time = (double) n / RATE;
            double sum = 0;
            for (int p = 0; p < t.partials().length; p++) {
                double h = p + 1;
                sum += t.partials()[p] * Math.exp(-time * h * 0.7 / t.decay()) * Math.sin(2 * Math.PI * frequency * h * time);
            }
            double attack = Math.min(1, time / t.attack());
            double release = Math.min(1, (length - time) / 0.006);
            out[n] = sum * attack * release * Math.exp(-time / t.decay());
        }
        return out;
    }

    // A plain telephone sine with short ramps, so that it starts and stops without a click.
    static double[] tone(double frequency, double length) {
        double[] out = new double[(int) (length * RATE)];
        for (int n = 0; n < out.length; n++) {
            double time = (double) n / RATE;
            double ramp = Math.min(1, Math.min(time, length - time) / 0.008);
            out[n] = ramp * Math.sin(2 * Math.PI * frequency * time);
        }
        return out;
    }

    static double[] silence(double length) {
        return new double[(int) (length * RATE)];
    }

    static void add(double[] target, double[] source, int at) {
        for (int i = 0; i < source.length && at + i < target.length; i++) target[at + i] += source[i];
    }

    // Joins the parts, levels what actually sounds to the given RMS, keeps 3 dB of headroom and encodes the MP3.
    static void cue(File root, String name, double rmsDb, double[]... parts) throws Exception {
        int length = 0;
        for (double[] part : parts) length += part.length;
        double[] all = new double[length];
        int at = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        double energy = 0, peak = 0;
        int active = 0;
        for (double v : all) {
            peak = Math.max(peak, Math.abs(v));
            if (Math.abs(v) > 1e-3) {
                energy += v * v;
                active++;
            }
        }
        double gain = Math.min(Math.pow(10, rmsDb / 20) / Math.sqrt(energy / active), Math.pow(10, -3.0 / 20) / peak);
        File wav = File.createTempFile("angatu-sound", ".wav");
        try {
            writeWav(wav, all, gain);
            File target = new File(root, name + ".mp3");
            Process ffmpeg = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-i", wav.getPath(), "-codec:a", "libmp3lame", "-b:a", "128k", "-ac", "1", target.getPath())
                .inheritIO().start();
            if (ffmpeg.waitFor() != 0) throw new IllegalStateException("ffmpeg failed for " + name);
        } finally {
            Files.deleteIfExists(wav.toPath());
        }
    }

    static void writeWav(File file, double[] samples, double gain) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(samples.length * 2 + 44);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + samples.length * 2));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeInt(Integer.reverseBytes(RATE));
            out.writeInt(Integer.reverseBytes(RATE * 2));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(samples.length * 2));
            for (double v : samples) out.writeShort(Short.reverseBytes((short) Math.round(v * gain * 32767)));
        }
        Files.write(file.toPath(), bytes.toByteArray());
    }
}
