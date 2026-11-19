package pe.utec.minia.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.utec.minia.tts.CachedTtsEngine;
import pe.utec.minia.tts.RotatingTtsEngine;
import pe.utec.minia.tts.TtsEngine;

import java.net.http.HttpClient;
import java.util.List;

@Configuration
public class TtsConfig {

    @Bean
    public GeminiKeyPool geminiKeyPool(MiniaProperties properties) {
        List<String> keys = properties.ai().gemini().apiKeys();
        List<String> models = properties.tts().gemini().models();
        String llmModel = properties.ai().gemini().model();
        return new GeminiKeyPool(keys, models, llmModel);
    }

    @Bean
    public TtsEngine ttsEngine(MiniaProperties properties, ObjectMapper mapper, HttpClient httpClient, GeminiKeyPool keyPool) {
        TtsEngine base = new RotatingTtsEngine(properties, mapper, httpClient, keyPool);
        return new CachedTtsEngine(base, properties);
    }
}