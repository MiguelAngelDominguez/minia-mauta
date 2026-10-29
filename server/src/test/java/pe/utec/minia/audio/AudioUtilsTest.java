package pe.utec.minia.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioUtilsTest {

    @Test
    void wavRoundtripPreservesPcmAndRate() {
        byte[] pcm = AudioUtils.tone(440, 100, 16000);
        byte[] wav = AudioUtils.wavFromPcm(pcm, 16000, 1);

        assertEquals(16000, AudioUtils.wavSampleRate(wav));
        assertArrayEquals(pcm, AudioUtils.pcmFromWav(wav));
    }

    @Test
    void resampleFrom48kTo16kReducesSamples() {
        byte[] pcm48 = AudioUtils.tone(440, 100, 48000);
        byte[] pcm16 = AudioUtils.resamplePcm16(pcm48, 48000, 16000);

        assertEquals(pcm48.length / 3, pcm16.length);
    }

    @Test
    void durationAndSilenceAreConsistent() {
        byte[] silence = AudioUtils.silence(1000, 16000);

        assertEquals(1000, AudioUtils.durationMs(silence, 16000));
        assertEquals(0.0, AudioUtils.rms(silence));
        assertTrue(AudioUtils.rms(AudioUtils.tone(440, 100, 16000)) > 0);
    }

    @Test
    void concatKeepsPartsInOrder() {
        byte[] a = {1, 2};
        byte[] b = {3, 4, 5, 6};

        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, AudioUtils.concat(a, b));
        assertArrayEquals(b, AudioUtils.concat(null, b));
    }
}
