package com.versioncontrol.scion.changeset;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The post's changesets point to "the compressed file contents in blob storage". The compression scheme is not
 * given; Scion Lite uses Deflate <b>[Lite assumption]</b>.
 */
public final class Compression {
    private Compression() {
    }

    public static byte[] compress(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    public static byte[] decompress(byte[] bytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("truncated compressed data");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("corrupt compressed data", e);
        } finally {
            inflater.end();
        }
    }
}
