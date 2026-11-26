package pe.utec.minia.service;

import org.springframework.stereotype.Service;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.audio.AudioUtils;
import pe.utec.minia.config.MiniaProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Service
public class AudioService {

    private final MiniaProperties properties;

    public AudioService(MiniaProperties properties) {
        this.properties = properties;
    }

    public void validatePcm16(byte[] audio) {
        if (audio == null || audio.length == 0) {
            throw ApiException.badRequest("AUDIO_VACIO", "No se recibió audio.");
        }
        if (audio.length % 2 != 0) {
            throw ApiException.badRequest("AUDIO_INVALIDO", "El audio PCM16 debe tener un número par de bytes.");
        }
        long maxBytes = maxPcmBytes();
        if (audio.length > maxBytes) {
            throw ApiException.badRequest("AUDIO_DEMASIADO_LARGO",
                    "El audio supera el máximo de " + properties.audio().maxSeconds() + " segundos.");
        }
        long durationMs = AudioUtils.durationMs(audio, properties.audio().rate());
        if (durationMs < 300) {
            throw ApiException.badRequest("AUDIO_MUY_CORTO", "El audio es demasiado corto.");
        }
    }

    /**
     * Lee PCM16 desde el cuerpo HTTP en bloques, sin depender de Content-Length.
     * El contenedor HTTP ya elimina el framing de Transfer-Encoding: chunked.
     */
    public byte[] readPcm16(InputStream input) throws IOException {
        if (input == null) {
            throw ApiException.badRequest("AUDIO_VACIO", "No se recibió audio.");
        }

        long maxBytes = maxPcmBytes();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long totalBytes = 0;
        int read;

        while ((read = input.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            totalBytes += read;
            if (totalBytes > maxBytes) {
                throw ApiException.badRequest("AUDIO_DEMASIADO_LARGO",
                        "El audio supera el máximo de " + properties.audio().maxSeconds() + " segundos.");
            }
            output.write(buffer, 0, read);
        }

        byte[] audio = output.toByteArray();
        validatePcm16(audio);
        return audio;
    }

    public long maxPcmBytes() {
        return (long) properties.audio().rate() * 2 * properties.audio().maxSeconds();
    }

    public byte[] silenceMs(int milliseconds) {
        return AudioUtils.silence(milliseconds, properties.audio().rate());
    }

    public byte[] concat(byte[]... parts) {
        return AudioUtils.concat(parts);
    }

    public byte[] wav(byte[] pcm) {
        return AudioUtils.wavFromPcm(pcm, properties.audio().rate(), properties.audio().channels());
    }

    public byte[] resampleToDeviceRate(byte[] pcm, int sourceRate) {
        return AudioUtils.resamplePcm16(pcm, sourceRate, properties.audio().rate());
    }

    public long durationMs(byte[] pcm) {
        return AudioUtils.durationMs(pcm, properties.audio().rate());
    }
}
