package pe.utec.minia.service;

import org.junit.jupiter.api.Test;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.config.MiniaProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioServiceTest {

    @Test
    void readsPcmAcrossArbitraryInputFragmentBoundaries() throws IOException {
        byte[] pcm = new byte[16_000 * 2 * 300 / 1_000];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 31);
        }

        byte[] received = audioService(30).readPcm16(fragmented(pcm, 7));

        assertArrayEquals(pcm, received);
    }

    @Test
    void rejectsEmptyAudio() {
        ApiException exception = assertThrows(ApiException.class,
                () -> audioService(30).readPcm16(new ByteArrayInputStream(new byte[0])));

        assertEquals("AUDIO_VACIO", exception.getCodigo());
    }

    @Test
    void rejectsPcmWithAnOddNumberOfBytes() {
        ApiException exception = assertThrows(ApiException.class,
                () -> audioService(30).readPcm16(new ByteArrayInputStream(new byte[9_601])));

        assertEquals("AUDIO_INVALIDO", exception.getCodigo());
    }

    @Test
    void rejectsAudioShorterThanMinimumDuration() {
        ApiException exception = assertThrows(ApiException.class,
                () -> audioService(30).readPcm16(new ByteArrayInputStream(new byte[9_598])));

        assertEquals("AUDIO_MUY_CORTO", exception.getCodigo());
    }

    @Test
    void rejectsAudioAsSoonAsItExceedsConfiguredMaximum() {
        byte[] tooLong = new byte[32_002];

        ApiException exception = assertThrows(ApiException.class,
                () -> audioService(1).readPcm16(fragmented(tooLong, 4_096)));

        assertEquals("AUDIO_DEMASIADO_LARGO", exception.getCodigo());
    }

    private AudioService audioService(int maxSeconds) {
        MiniaProperties properties = new MiniaProperties(
                null,
                null,
                new MiniaProperties.Audio(16_000, 1, maxSeconds, 100, 300),
                null,
                null
        );
        return new AudioService(properties);
    }

    private InputStream fragmented(byte[] bytes, int maxReadSize) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, maxReadSize));
            }
        };
    }
}
