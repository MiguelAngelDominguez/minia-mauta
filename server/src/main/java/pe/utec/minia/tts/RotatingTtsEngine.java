package pe.utec.minia.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.audio.AudioUtils;
import pe.utec.minia.config.GeminiKeyPool;
import pe.utec.minia.config.MiniaProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public class RotatingTtsEngine implements TtsEngine {

    private static final Logger log = LoggerFactory.getLogger(RotatingTtsEngine.class);

    private final MiniaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final GeminiKeyPool keyPool;

    public RotatingTtsEngine(MiniaProperties properties, ObjectMapper mapper, HttpClient httpClient, GeminiKeyPool keyPool) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
        this.keyPool = keyPool;
    }

    @Override
    public byte[] sintetizar(String texto) {
        int intentos = keyPool.totalCombinaciones();
        if (intentos == 0) {
            throw ApiException.serviceUnavailable("TTS_SIN_KEYS", "No hay API keys configuradas para TTS.");
        }

        for (int i = 0; i < intentos; i++) {
            GeminiKeyPool.Combinacion combo = keyPool.siguienteDisponible();
            if (combo == null) {
                break;
            }
            try {
                return llamarGeminiTts(combo, texto);
            } catch (ApiException exception) {
                if (exception.getMessage() != null && exception.getMessage().contains("429")) {
                    keyPool.marcarAgotada(combo.keyIndex(), combo.modelIndex());
                    continue;
                }
                throw exception;
            } catch (Exception exception) {
                keyPool.marcarAgotada(combo.keyIndex(), combo.modelIndex());
            }
        }
        throw ApiException.serviceUnavailable("TTS_CUOTA_AGOTADA",
                "Todas las combinaciones de API key + modelo TTS están agotadas. Intenta más tarde.");
    }

    private byte[] llamarGeminiTts(GeminiKeyPool.Combinacion combo, String texto) {
        try {
            ObjectNode body = mapper.createObjectNode();

            // Los modelos TTS de Gemini no aceptan systemInstruction: la instrucción va en el prompt
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            content.put("role", "user");
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text",
                    "Lee en español con una voz amable, clara y pausada, sin añadir comentarios ni palabras extra:\n" + texto);

            ObjectNode generationConfig = body.putObject("generationConfig");
            generationConfig.putArray("responseModalities").add("AUDIO");
            ObjectNode speechConfig = generationConfig.putObject("speechConfig");
            ObjectNode voiceConfig = speechConfig.putObject("voiceConfig");
            voiceConfig.putObject("prebuiltVoiceConfig")
                    .put("voiceName", properties.tts().gemini().voice());

            String payload = mapper.writeValueAsString(body);
            String model = combo.model().contains("3.8") ? "gemini-2.5-flash-preview-tts" : combo.model();

            List<String> urls = List.of(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
                    "https://aiplatform.googleapis.com/v1/publishers/google/models/" + model + ":generateContent?key="
                            + URLEncoder.encode(combo.apiKey(), StandardCharsets.UTF_8)
            );

            HttpResponse<String> response = null;
            for (String url : urls) {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(properties.tts().gemini().timeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                if (!url.contains("?key=")) {
                    reqBuilder.header("x-goog-api-key", combo.apiKey());
                }
                response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    break;
                }
                log.warn("TTS fallo en {} -> HTTP {}: {}", url.split("\\?")[0], response.statusCode(), response.body());
            }

            if (response == null || response.statusCode() != 200) {
                int code = response == null ? 500 : response.statusCode();
                if (code == 429) {
                    throw ApiException.serviceUnavailable("GEMINI_TTS_429", "429");
                }
                throw ApiException.serviceUnavailable("GEMINI_TTS_ERROR",
                        "Gemini TTS respondió con estado " + code + ".");
            }

            byte[] audio = extractAudio(mapper.readTree(response.body()));
            int sourceRate = AudioUtils.wavSampleRate(audio);
            byte[] pcm = AudioUtils.pcmFromWav(audio);
            if (sourceRate == 0) {
                sourceRate = 24000;
            }
            return AudioUtils.resamplePcm16(pcm, sourceRate, properties.audio().rate());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("GEMINI_TTS_INTERRUMPIDO", "La síntesis fue interrumpida.");
        } catch (IOException exception) {
            throw ApiException.serviceUnavailable("GEMINI_TTS_ERROR", "No se pudo conectar con Gemini TTS.");
        } catch (IllegalArgumentException exception) {
            throw ApiException.serviceUnavailable("GEMINI_TTS_AUDIO_INVALIDO",
                    "Gemini TTS devolvió un audio con formato inválido.");
        }
    }

    private byte[] extractAudio(JsonNode root) {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            JsonNode inline = part.path("inlineData");
            if (inline.hasNonNull("data")) {
                return Base64.getDecoder().decode(inline.path("data").asText());
            }
        }
        throw ApiException.serviceUnavailable("GEMINI_TTS_SIN_AUDIO", "Gemini TTS no devolvió audio.");
    }

    @Override
    public String nombre() {
        return "gemini-tts-rotating:" + keyPool.totalCombinaciones() + " combos";
    }
}
