package pe.utec.minia.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.utec.minia.ai.AiEngine;
import pe.utec.minia.ai.RotatingAiEngine;

import java.net.http.HttpClient;

@Configuration
public class AiConfig {

    @Bean
    public AiEngine aiEngine(MiniaProperties properties, ObjectMapper mapper, HttpClient httpClient, GeminiKeyPool keyPool) {
        return new RotatingAiEngine(properties, mapper, httpClient, keyPool);
    }
}