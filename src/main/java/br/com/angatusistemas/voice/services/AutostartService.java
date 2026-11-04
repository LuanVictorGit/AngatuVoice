package br.com.angatusistemas.voice.services;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.voice.utils.AppDirs;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinReg;
import java.nio.file.Path;

/**
 * Registers or removes the per-user Windows startup entry; nothing hidden and no administrator rights involved.
 *
 * @author Angatu Sistemas
 */
public final class AutostartService {

    private static final String KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String NAME = "AngatuVoice";

    private AutostartService() {
    }

    public static boolean available() {
        return AppDirs.install().isPresent() && executable().toFile().exists();
    }

    public static boolean enabled() {
        return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, KEY, NAME);
    }

    public static void apply(boolean enable) {
        if (!available()) {
            return;
        }
        try {
            if (enable) {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, KEY, NAME, "\"" + executable() + "\" --autostart");
            } else if (enabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, KEY, NAME);
            }
        } catch (Win32Exception e) {
            // A locked-down registry must not stop the app from opening; the setting simply has no effect.
            Console.warn("Não foi possível alterar o início com o Windows: %s", e.getMessage());
        }
    }

    private static Path executable() {
        return Path.of(System.getProperty("jpackage.app-path",
                AppDirs.install().map(p -> p.resolve("AngatuVoice.exe").toString()).orElse("")));
    }
}
