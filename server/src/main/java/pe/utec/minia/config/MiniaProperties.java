package pe.utec.minia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "minia")
public record MiniaProperties(
        Ai ai,
        Tts tts,
        Audio audio,
        Device device,
        Cors cors
) {

    public record Ai(String mode, GeminiAi gemini) {
        public record GeminiAi(String apiKey, String model, int timeoutSeconds) {
        }
    }

    public record Tts(String mode, GeminiTts gemini) {
        public record GeminiTts(String model, String voice, int timeoutSeconds) {
        }
    }

    public record Audio(int rate, int channels, int maxSeconds, int chunkMs, int silenceMs) {
    }

    public record Device(String token) {
    }

    public record Cors(String allowedOrigins) {
    }
}
