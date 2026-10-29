package pe.utec.minia.audio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class AudioUtils {

    private AudioUtils() {
    }

    public static byte[] silence(int milliseconds, int rate) {
        int samples = Math.max(0, rate * milliseconds / 1000);
        return new byte[samples * 2];
    }

    public static byte[] tone(int frequency, int milliseconds, int rate) {
        int samples = Math.max(0, rate * milliseconds / 1000);
        byte[] out = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double value = Math.sin(2 * Math.PI * frequency * i / rate) * 0.18;
            writeShortLE(out, i * 2, (short) Math.round(value * Short.MAX_VALUE));
        }
        return out;
    }

    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            if (part != null && part.length > 0) {
                out.writeBytes(part);
            }
        }
        return out.toByteArray();
    }

    public static byte[] wavFromPcm(byte[] pcm, int rate, int channels) {
        int dataLength = pcm.length;
        byte[] wav = new byte[44 + dataLength];
        putAscii(wav, 0, "RIFF");
        putIntLE(wav, 4, 36 + dataLength);
        putAscii(wav, 8, "WAVE");
        putAscii(wav, 12, "fmt ");
        putIntLE(wav, 16, 16);
        putShortLE(wav, 20, (short) 1);
        putShortLE(wav, 22, (short) channels);
        putIntLE(wav, 24, rate);
        putIntLE(wav, 28, rate * channels * 2);
        putShortLE(wav, 32, (short) (channels * 2));
        putShortLE(wav, 34, (short) 16);
        putAscii(wav, 36, "data");
        putIntLE(wav, 40, dataLength);
        System.arraycopy(pcm, 0, wav, 44, dataLength);
        return wav;
    }

    public static byte[] pcmFromWav(byte[] wav) {
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            return wav;
        }
        int offset = 12;
        while (offset + 8 <= wav.length) {
            int size = readIntLE(wav, offset + 4);
            if (wav[offset] == 'd' && wav[offset + 1] == 'a' && wav[offset + 2] == 't' && wav[offset + 3] == 'a') {
                int start = offset + 8;
                int end = Math.min(start + size, wav.length);
                byte[] pcm = new byte[end - start];
                System.arraycopy(wav, start, pcm, 0, pcm.length);
                return pcm;
            }
            offset += 8 + size + (size % 2);
        }
        return wav;
    }

    public static byte[] resamplePcm16(byte[] pcm, int fromRate, int toRate) {
        if (fromRate == toRate || pcm.length < 2) {
            return pcm;
        }
        int inSamples = pcm.length / 2;
        int outSamples = (int) Math.round(inSamples * (double) toRate / fromRate);
        byte[] out = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            double source = i * (double) fromRate / toRate;
            int base = (int) Math.floor(source);
            int next = Math.min(base + 1, inSamples - 1);
            double fraction = source - base;
            short s0 = readShortLE(pcm, base * 2);
            short s1 = readShortLE(pcm, next * 2);
            short value = (short) Math.round(s0 + (s1 - s0) * fraction);
            writeShortLE(out, i * 2, value);
        }
        return out;
    }

    public static long durationMs(byte[] pcm, int rate) {
        return (pcm.length / 2L) * 1000L / rate;
    }

    public static double rms(byte[] pcm) {
        if (pcm.length < 2) {
            return 0;
        }
        double sum = 0;
        int samples = pcm.length / 2;
        for (int i = 0; i < samples; i++) {
            double value = readShortLE(pcm, i * 2) / (double) Short.MAX_VALUE;
            sum += value * value;
        }
        return Math.sqrt(sum / samples);
    }

    public static short readShortLE(byte[] data, int offset) {
        return (short) ((data[offset] & 0xFF) | (data[offset + 1] << 8));
    }

    public static void writeShortLE(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    public static int wavSampleRate(byte[] wav) {
        if (wav.length < 28 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
            return 0;
        }
        return readIntLE(wav, 24);
    }

    public static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void putShortLE(byte[] data, int offset, short value) {
        writeShortLE(data, offset, value);
    }

    private static void putIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putAscii(byte[] data, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }
}
