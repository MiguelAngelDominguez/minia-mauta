package pe.utec.minia.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.utec.minia.tts.CachedTtsEngine;
import pe.utec.minia.tts.GeminiTtsEngine;
import pe.utec.minia.tts.MockTtsEngine;
import pe.utec.minia.tts.TtsEngine;

import java.net.http.HttpClient;

@Configuration
public class TtsConfig {

    @Bean
    public TtsEngine ttsEngine(MiniaProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        TtsEngine base = "gemini".equalsIgnoreCase(properties.tts().mode())
                ? new GeminiTtsEngine(properties, mapper, httpClient)
                : new MockTtsEngine(properties);
        return new CachedTtsEngine(base, properties);
    }
}
