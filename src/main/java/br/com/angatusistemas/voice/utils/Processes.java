package br.com.angatusistemas.voice.utils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short system command with a deadline and returns what it printed, without ever going through a shell.
 *
 * @author Angatu Sistemas
 */
public final class Processes {

    private static final Charset OEM = Charset.forName(System.getProperty("native.encoding", "UTF-8"));

    private Processes() {
    }

    public static String run(int timeoutSeconds, String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), OEM);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return output;
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
