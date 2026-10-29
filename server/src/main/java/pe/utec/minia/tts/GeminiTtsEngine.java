package pe.utec.minia.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.audio.AudioUtils;
import pe.utec.minia.config.MiniaProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

public class GeminiTtsEngine implements TtsEngine {

    private final MiniaProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public GeminiTtsEngine(MiniaProperties properties, ObjectMapper mapper, HttpClient httpClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = httpClient;
    }

    @Override
    public byte[] sintetizar(String texto) {
        String apiKey = properties.ai().gemini().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.serviceUnavailable("GEMINI_SIN_API_KEY",
                    "Falta configurar GEMINI_API_KEY en el servidor.");
        }

        try {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", "Di con voz amable, clara y pausada, en español: " + texto);

            ObjectNode generationConfig = body.putObject("generationConfig");
            generationConfig.putArray("responseModalities").add("AUDIO");
            ObjectNode speechConfig = generationConfig.putObject("speechConfig");
            ObjectNode voiceConfig = speechConfig.putObject("voiceConfig");
            voiceConfig.putObject("prebuiltVoiceConfig")
                    .put("voiceName", properties.tts().gemini().voice());

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + properties.tts().gemini().model() + ":generateContent";

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(properties.tts().gemini().timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ApiException.serviceUnavailable("GEMINI_TTS_ERROR",
                        "Gemini TTS respondió con estado " + response.statusCode() + ".");
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
        return "gemini-tts:" + properties.tts().gemini().model() + ":" + properties.tts().gemini().voice();
    }
}
