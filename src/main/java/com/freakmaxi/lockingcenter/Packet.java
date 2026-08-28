package com.freakmaxi.lockingcenter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Encodes a request of the locking-center wire protocol.
 *
 * <pre>
 * Lock           [1][keySize][key][sourceSize][source]
 * Unlock         [2][keySize][key]
 * ResetByKey     [3][keySize][key]
 * ResetBySource  [4][sourceSize][source]
 * TryLock        [5][keySize][key][sourceSize][source]
 * </pre>
 *
 * Strings are length prefixed with a single byte that holds the number of
 * UTF-8 <em>bytes</em> that follow, so it is taken from the encoded bytes and
 * not from {@link String#length()} which counts UTF-16 chars. For a non ASCII
 * key the two differ and the server would read a truncated key and desync on
 * the rest of the request.
 */
final class Packet {
    /**
     * The server reads a size as a signed byte, so 127 is the largest key or
     * source address a request can carry. Anything above it can never be sent
     * and is a caller mistake, not a transient failure.
     */
    static final int MAX_VALUE_SIZE = 127;

    private Packet() {
    }

    static byte[] prepare(MutexAction action, String key, String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(action.code());

            if (action.hasKey()) {
                writeString(out, key == null ? "" : key);
            }
            if (action.hasSource()) {
                writeString(out, source == null ? "" : source);
            }

            out.flush();
        } catch (IOException e) {
            // a ByteArrayOutputStream never throws
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_VALUE_SIZE) {
            throw new IllegalArgumentException("value can not be longer than " + MAX_VALUE_SIZE + " bytes");
        }
        out.writeByte(encoded.length);
        out.write(encoded);
    }
}
