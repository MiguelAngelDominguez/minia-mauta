package pe.utec.minia.api.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.utec.minia.api.dto.CrearSesionRequest;
import pe.utec.minia.api.dto.TurnoMeta;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.config.MiniaProperties;
import pe.utec.minia.domain.EstadoSesion;
import pe.utec.minia.domain.Resultado;
import pe.utec.minia.service.SessionService;
import pe.utec.minia.service.TurnoResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/device")
public class DeviceController {

    private static final String CONTRATO_VERSION = "1";
    private static final String AUDIO_FORMAT = "PCM16LE";

    private final SessionService sessionService;
    private final MiniaProperties properties;
    private final ObjectMapper mapper;

    public DeviceController(SessionService sessionService, MiniaProperties properties, ObjectMapper mapper) {
        this.sessionService = sessionService;
        this.properties = properties;
        this.mapper = mapper;
    }

    @GetMapping("/contrato")
    public Map<String, Object> contrato() {
        Map<String, Object> contrato = new LinkedHashMap<>();
        contrato.put("contratoVersion", Integer.parseInt(CONTRATO_VERSION));
        contrato.put("transporte", "HTTP/1.1");
        contrato.put("framing", "[uint32 BE len(JSON)][JSON UTF-8][PCM16LE]");
        contrato.put("audio", Map.of(
                "formato", AUDIO_FORMAT,
                "rate", properties.audio().rate(),
                "canales", properties.audio().channels(),
                "maxSegundos", properties.audio().maxSeconds(),
                "maxBytes", properties.audio().rate() * properties.audio().channels() * 2 * properties.audio().maxSeconds()
        ));
        contrato.put("headersRespuesta", List.of(
                "X-Contrato-Version", "X-Audio-Format", "X-Audio-Rate",
                "X-Estado", "X-Resultado", "X-Sesion-Id", "X-Turno"
        ));
        contrato.put("headersSolicitud", List.of(
                "X-Device-Token", "X-Device-Id", "X-Turno",
                "X-Mock-Resultado (solo mock)", "X-Mock-Transcripcion (solo mock)"
        ));
        contrato.put("endpoints", List.of(
                Map.of("metodo", "GET", "ruta", "/api/v1/device/contrato", "descripcion", "Este documento"),
                Map.of("metodo", "POST", "ruta", "/api/v1/device/sesiones", "contentType", "application/json",
                        "descripcion", "Crea o reanuda una sesión. Devuelve saludo + primera pregunta."),
                Map.of("metodo", "POST", "ruta", "/api/v1/device/sesiones/{id}/turno", "contentType",
                        "audio/L16;rate=16000;channels=1", "descripcion", "Envía PCM16LE y recibe la evaluación."),
                Map.of("metodo", "GET", "ruta", "/api/v1/device/sesiones/{id}/audio-actual",
                        "descripcion", "Reintenta la reproducción de la pregunta actual."),
                Map.of("metodo", "POST", "ruta", "/api/v1/device/sesiones/{id}/cerrar",
                        "descripcion", "Finaliza la sesión y devuelve el audio de cierre.")
        ));
        contrato.put("estados", List.of(EstadoSesion.values()));
        contrato.put("resultados", List.of(Resultado.values()));
        contrato.put("erroresRecuperables", List.of(
                "AUDIO_VACIO", "AUDIO_INVALIDO", "AUDIO_MUY_CORTO", "AUDIO_DEMASIADO_LARGO", "IA_NO_DISPONIBLE"
        ));
        contrato.put("notas", List.of(
                "Los errores recuperables durante un turno devuelven HTTP 200, estado ERROR y audio para reintentar.",
                "Los errores de autenticación o de sesión devuelven un JSON con codigo y mensaje.",
                "Usa X-Turno como UUID idempotente; el servidor recuerda la respuesta por 10 minutos.",
                "En modo mock, X-Mock-Resultado permite forzar CORRECTO, INCORRECTO, SIN_CONFIANZA o ERROR."
        ));
        return contrato;
    }

    @PostMapping(value = "/sesiones", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> crear(
            @RequestHeader(value = "X-Device-Token", required = false) String token,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
            @RequestParam(value = "deviceId", required = false) String deviceParam,
            @RequestBody(required = false) CrearSesionRequest body
    ) {
        checkToken(token);
        String deviceId = firstNonBlank(deviceHeader, body == null ? null : body.deviceId(), deviceParam);
        if (deviceId == null) {
            throw ApiException.badRequest("DEVICE_ID_REQUERIDO", "Falta X-Device-Id o deviceId.");
        }
        String alias = body == null ? null : body.alias();
        UUID textoId = body == null ? null : body.textoId();
        TurnoResult result = sessionService.crear(deviceId, alias, textoId);
        return frame(result, null);
    }

    @PostMapping(value = "/sesiones/{id}/turno", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> turno(
            @RequestHeader(value = "X-Device-Token", required = false) String token,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
            @RequestHeader(value = "X-Turno", required = false) String turnHeader,
            @RequestHeader(value = "X-Mock-Resultado", required = false) String mockResultado,
            @RequestHeader(value = "X-Mock-Transcripcion", required = false) String mockTranscripcion,
            @PathVariable UUID id,
            @RequestBody(required = false) byte[] audio
    ) {
        checkToken(token);
        String deviceId = requireDevice(deviceHeader);
        UUID turnId = parseUuid(turnHeader);
        if (turnId == null) {
            turnId = UUID.randomUUID();
        }
        TurnoResult result = sessionService.responder(
                id, deviceId, turnId, audio, mockResultado, mockTranscripcion);
        return frame(result, turnId);
    }

    @GetMapping(value = "/sesiones/{id}/audio-actual", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> audioActual(
            @RequestHeader(value = "X-Device-Token", required = false) String token,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
            @PathVariable UUID id
    ) {
        checkToken(token);
        String deviceId = requireDevice(deviceHeader);
        TurnoResult result = sessionService.audioActual(id, deviceId);
        HttpHeaders headers = baseHeaders(result.meta(), null);
        headers.setContentType(MediaType.parseMediaType(
                "audio/L16;rate=" + properties.audio().rate() + ";channels=" + properties.audio().channels()));
        return new ResponseEntity<>(result.audio(), headers, HttpStatus.OK);
    }

    @PostMapping(value = "/sesiones/{id}/cerrar", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> cerrar(
            @RequestHeader(value = "X-Device-Token", required = false) String token,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceHeader,
            @PathVariable UUID id
    ) {
        checkToken(token);
        String deviceId = requireDevice(deviceHeader);
        TurnoResult result = sessionService.cerrar(id, deviceId);
        return frame(result, null);
    }

    private ResponseEntity<byte[]> frame(TurnoResult result, UUID turnId) {
        try {
            byte[] json = mapper.writeValueAsBytes(result.meta());
            byte[] body = BinaryFraming.encode(json, result.audio());
            return new ResponseEntity<>(body, baseHeaders(result.meta(), turnId), HttpStatus.OK);
        } catch (IOException exception) {
            throw ApiException.internal("JSON_ERROR", "No se pudieron serializar los metadatos.");
        }
    }

    private HttpHeaders baseHeaders(TurnoMeta meta, UUID turnId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("X-Contrato-Version", CONTRATO_VERSION);
        headers.set("X-Audio-Format", AUDIO_FORMAT);
        headers.set("X-Audio-Rate", String.valueOf(properties.audio().rate()));
        headers.set("X-Estado", meta.estado());
        headers.set("X-Resultado", meta.resultado());
        headers.set("X-Sesion-Id", meta.sesionId());
        if (turnId != null) {
            headers.set("X-Turno", turnId.toString());
        }
        return headers;
    }

    private void checkToken(String token) {
        String expected = properties.device().token();
        if (expected != null && !expected.isBlank() && !expected.equals(token)) {
            throw ApiException.unauthorized("TOKEN_INVALIDO", "El token del dispositivo no es válido.");
        }
    }

    private String requireDevice(String deviceHeader) {
        if (deviceHeader == null || deviceHeader.isBlank()) {
            throw ApiException.badRequest("DEVICE_ID_REQUERIDO", "Falta X-Device-Id.");
        }
        return deviceHeader;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
