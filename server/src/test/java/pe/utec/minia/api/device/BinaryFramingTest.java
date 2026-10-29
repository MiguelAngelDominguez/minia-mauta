package pe.utec.minia.api.device;

import org.junit.jupiter.api.Test;
import pe.utec.minia.api.error.ApiException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryFramingTest {

    @Test
    void encodeAndDecodeRoundtrip() {
        byte[] json = "{\"estado\":\"PREGUNTANDO\"}".getBytes(StandardCharsets.UTF_8);
        byte[] pcm = {0, 1, 2, 3, 4, 5};

        byte[] frame = BinaryFraming.encode(json, pcm);

        assertArrayEquals(json, BinaryFraming.json(frame));
        assertArrayEquals(pcm, BinaryFraming.pcm(frame));
        assertEquals(json.length, ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF));
    }

    @Test
    void encodeAllowsEmptyPcm() {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = BinaryFraming.encode(json, new byte[0]);

        assertEquals(0, BinaryFraming.pcm(frame).length);
    }

    @Test
    void rejectsShortFrame() {
        assertThrows(ApiException.class, () -> BinaryFraming.json(new byte[]{0, 1}));
    }

    @Test
    void rejectsLengthLargerThanFrame() {
        byte[] frame = {0, 0, 0, 10, 1, 2, 3};
        assertThrows(ApiException.class, () -> BinaryFraming.json(frame));
    }

    @Test
    void rejectsMissingJson() {
        assertThrows(ApiException.class, () -> BinaryFraming.encode(new byte[0], new byte[]{1}));
    }
}
