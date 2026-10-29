package pe.utec.minia.api.device;

import pe.utec.minia.api.error.ApiException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class BinaryFraming {

    private static final int MAX_JSON_BYTES = 64 * 1024;

    private BinaryFraming() {
    }

    public static byte[] encode(byte[] json, byte[] pcm) {
        if (json == null || json.length == 0) {
            throw ApiException.internal("JSON_VACIO", "La respuesta no tiene metadatos.");
        }
        if (json.length > MAX_JSON_BYTES) {
            throw ApiException.internal("JSON_DEMASIADO_GRANDE", "Los metadatos superan el máximo permitido.");
        }
        int pcmLength = pcm == null ? 0 : pcm.length;
        ByteBuffer buffer = ByteBuffer.allocate(4 + json.length + pcmLength).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(json.length);
        buffer.put(json);
        if (pcmLength > 0) {
            buffer.put(pcm);
        }
        return buffer.array();
    }

    public static byte[] json(byte[] frame) {
        if (frame == null || frame.length < 4) {
            throw ApiException.badRequest("FRAME_INVALIDO", "El frame binario no tiene longitud.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();
        if (length < 0 || length > MAX_JSON_BYTES || frame.length < 4 + length) {
            throw ApiException.badRequest("FRAME_INVALIDO", "La longitud del JSON del frame no es válida.");
        }
        byte[] json = new byte[length];
        buffer.get(json);
        return json;
    }

    public static byte[] pcm(byte[] frame) {
        byte[] json = json(frame);
        byte[] pcm = new byte[frame.length - 4 - json.length];
        System.arraycopy(frame, 4 + json.length, pcm, 0, pcm.length);
        return pcm;
    }
}
