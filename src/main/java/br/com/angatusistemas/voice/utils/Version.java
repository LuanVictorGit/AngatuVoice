package br.com.angatusistemas.voice.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version MAJOR.MINOR.PATCH with an optional pre-release suffix; a pre-release sorts below its release.
 *
 * @author Angatu Sistemas
 */
public record Version(int major, int minor, int patch, String pre) implements Comparable<Version> {

    private static final Pattern FORMAT = Pattern.compile("v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:-([0-9A-Za-z.]{1,32}))?");
    private static final Version CURRENT = loadCurrent();

    public static Version current() {
        return CURRENT;
    }

    public static Version parse(String text) {
        Matcher m = FORMAT.matcher(text == null ? "" : text.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a semantic version: " + text);
        }
        return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                m.group(4) == null ? "" : m.group(4));
    }

    public boolean isPreRelease() {
        return !pre.isEmpty();
    }

    @Override
    public int compareTo(Version other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, other.patch);
        }
        if (c != 0 || pre.equals(other.pre)) {
            return c;
        }
        if (pre.isEmpty() || other.pre.isEmpty()) {
            return pre.isEmpty() ? 1 : -1;
        }
        return pre.compareTo(other.pre);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + pre);
    }

    private static Version loadCurrent() {
        Properties props = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read version.properties", e);
        }
        String value = props.getProperty("version", "0.0.0");
        return parse(value.startsWith("$") ? "0.0.0" : value);
    }
}
