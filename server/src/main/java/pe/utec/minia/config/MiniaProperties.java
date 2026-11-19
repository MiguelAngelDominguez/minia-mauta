package pe.utec.minia.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "minia")
public record MiniaProperties(
        Ai ai,
        Tts tts,
        Audio audio,
        Device device,
        Cors cors
) {
    public record Ai(GeminiAi gemini) {
        public record GeminiAi(List<String> apiKeys, String model, int timeoutSeconds) {
        }
    }

    public record Tts(GeminiTts gemini) {
        public record GeminiTts(List<String> models, String voice, int timeoutSeconds) {
        }
    }

    public record Audio(int rate, int channels, int maxSeconds, int chunkMs, int silenceMs) {
    }

    public record Device(String token) {
    }

    public record Cors(String allowedOrigins) {
    }
}