package com.versioncontrol.scion.diff;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Splits bytes into lines and joins them back without losing anything: split on '\n' keeping a trailing empty line
 * when the text ends with a newline. ISO-8859-1 maps every byte to one char, so any file round-trips exactly.
 */
public final class Lines {
    private Lines() {
    }

    public static List<String> split(byte[] bytes) {
        if (bytes.length == 0) {
            return List.of();
        }
        return Arrays.asList(new String(bytes, StandardCharsets.ISO_8859_1).split("\n", -1));
    }

    public static byte[] join(List<String> lines) {
        return String.join("\n", lines).getBytes(StandardCharsets.ISO_8859_1);
    }
}
