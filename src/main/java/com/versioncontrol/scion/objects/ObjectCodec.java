package com.versioncontrol.scion.objects;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The canonical bytes of an object: {@code "<type> <content length>\0<content>"}. The id is the SHA-256 of these
 * bytes, so they are what is stored on disk and sent over the network, and anyone can check them against the id.
 */
public final class ObjectCodec {
    private ObjectCodec() {
    }

    public static byte[] encode(ScionObject object) {
        byte[] content = object.content();
        byte[] header = (object.type().label() + " " + content.length + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = Arrays.copyOf(header, header.length + content.length);
        System.arraycopy(content, 0, encoded, header.length, content.length);
        return encoded;
    }

    public static ObjectId idOf(ScionObject object) {
        return ObjectId.hash(encode(object));
    }

    public static ScionObject decode(byte[] encoded) {
        int nul = indexOf(encoded, (byte) 0);
        if (nul < 0) {
            throw new CorruptObjectException("object has no header");
        }
        String header = new String(encoded, 0, nul, StandardCharsets.US_ASCII);
        int space = header.indexOf(' ');
        if (space < 0) {
            throw new CorruptObjectException("bad object header: " + header);
        }
        ObjectType type = ObjectType.fromLabel(header.substring(0, space));
        int length;
        try {
            length = Integer.parseInt(header.substring(space + 1));
        } catch (NumberFormatException e) {
            throw new CorruptObjectException("bad object length: " + header);
        }
        if (length != encoded.length - nul - 1) {
            throw new CorruptObjectException("object length " + length + " does not match " + (encoded.length - nul - 1));
        }
        byte[] content = Arrays.copyOfRange(encoded, nul + 1, encoded.length);
        return switch (type) {
            case BLOB -> new Blob(content);
            case TREE -> Tree.parse(content);
        };
    }

    private static int indexOf(byte[] bytes, byte value) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
