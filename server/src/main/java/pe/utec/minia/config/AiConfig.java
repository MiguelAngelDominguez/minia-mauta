package pe.utec.minia.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.utec.minia.ai.AiEngine;
import pe.utec.minia.ai.GeminiAiEngine;
import pe.utec.minia.ai.MockAiEngine;

import java.net.http.HttpClient;

@Configuration
public class AiConfig {

    @Bean
    public AiEngine aiEngine(MiniaProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        if ("gemini".equalsIgnoreCase(properties.ai().mode())) {
            return new GeminiAiEngine(properties, mapper, httpClient);
        }
        return new MockAiEngine();
    }
}
