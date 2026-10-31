package com.catacumbas69.catapulsar.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PayloadCodecTest {

    @Test
    void decodesValidUtf8AsText() {
        PayloadCodec.Decoded decoded = PayloadCodec.decode("{\"olá\":\"mundo ✓\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(decoded.binary()).isFalse();
        assertThat(decoded.text()).isEqualTo("{\"olá\":\"mundo ✓\"}");
    }

    @Test
    void encodesInvalidUtf8AsBase64() {
        byte[] data = {(byte) 0xC3, (byte) 0x28, 0x00, (byte) 0xFF};

        PayloadCodec.Decoded decoded = PayloadCodec.decode(data);

        assertThat(decoded.binary()).isTrue();
        assertThat(Base64.getDecoder().decode(decoded.text())).isEqualTo(data);
    }

    @Test
    void treatsEmptyAndNullAsEmptyText() {
        assertThat(PayloadCodec.decode(null)).isEqualTo(new PayloadCodec.Decoded("", false));
        assertThat(PayloadCodec.decode(new byte[0])).isEqualTo(new PayloadCodec.Decoded("", false));
    }
}
