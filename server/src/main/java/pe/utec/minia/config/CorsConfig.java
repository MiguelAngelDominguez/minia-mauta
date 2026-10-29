package pe.utec.minia.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(MiniaProperties properties) {
        String[] origins = Arrays.stream(properties.cors().allowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders(
                                "X-Contrato-Version",
                                "X-Audio-Format",
                                "X-Audio-Rate",
                                "X-Estado",
                                "X-Resultado",
                                "X-Sesion-Id",
                                "X-Turno"
                        )
                        .maxAge(3600);
            }
        };
    }
}
