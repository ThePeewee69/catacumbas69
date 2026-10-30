package com.catacumbas69.pulsario.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Turns message bytes into text for the UI: strict UTF-8 when the bytes are valid UTF-8, Base64 otherwise.
 */
public final class PayloadCodec {

    private PayloadCodec() {
    }

    /**
     * Decodes a payload.
     *
     * @param data message bytes, may be {@code null}
     * @return the decoded payload
     */
    public static Decoded decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new Decoded("", false);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
            return new Decoded(text, false);
        } catch (CharacterCodingException e) {
            return new Decoded(Base64.getEncoder().encodeToString(data), true);
        }
    }

    /**
     * A decoded payload.
     *
     * @param text UTF-8 text or Base64
     * @param binary {@code true} when {@code text} is Base64
     */
    public record Decoded(String text, boolean binary) {
    }
}
