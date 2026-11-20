package pe.utec.minia.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utec.minia.ai.AiEngine;
import pe.utec.minia.ai.Evaluacion;
import pe.utec.minia.ai.EvaluacionRequest;
import pe.utec.minia.api.dto.TurnoMeta;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.config.MiniaProperties;
import pe.utec.minia.domain.EstadoSesion;
import pe.utec.minia.domain.Pregunta;
import pe.utec.minia.domain.Resultado;
import pe.utec.minia.domain.Sesion;
import pe.utec.minia.domain.Texto;
import pe.utec.minia.domain.Turno;
import pe.utec.minia.repository.SesionRepository;
import pe.utec.minia.repository.TurnoRepository;
import pe.utec.minia.tts.TtsEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SesionRepository sesionRepository;
    private final TurnoRepository turnoRepository;
    private final ContentService contentService;
    private final AiEngine aiEngine;
    private final TtsEngine ttsEngine;
    private final AudioService audioService;
    private final MiniaProperties properties;
    private final Cache<String, TurnoResult> responseCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public SessionService(
            SesionRepository sesionRepository,
            TurnoRepository turnoRepository,
            ContentService contentService,
            AiEngine aiEngine,
            TtsEngine ttsEngine,
            AudioService audioService,
            MiniaProperties properties
    ) {
        this.sesionRepository = sesionRepository;
        this.turnoRepository = turnoRepository;
        this.contentService = contentService;
        this.aiEngine = aiEngine;
        this.ttsEngine = ttsEngine;
        this.audioService = audioService;
        this.properties = properties;
    }

    @Transactional
    public TurnoResult crear(String deviceId, String alias, UUID textoId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw ApiException.badRequest("DEVICE_ID_REQUERIDO", "Falta deviceId.");
        }

        return sesionRepository.findFirstByDeviceIdAndFinalizadaEnIsNull(deviceId)
                .map(this::reanudar)
                .orElseGet(() -> nuevaSesion(deviceId, alias, textoId));
    }

    private TurnoResult nuevaSesion(String deviceId, String alias, UUID textoId) {
        Texto texto = textoId == null ? contentService.textoActivo() : contentService.texto(textoId);
        List<UUID> preguntas = contentService.seleccionarCinco(texto.getId());

        Sesion sesion = new Sesion();
        sesion.setId(UUID.randomUUID());
        sesion.setDeviceId(deviceId);
        sesion.setAlias(alias);
        sesion.setTextoId(texto.getId());
        sesion.setEstado(EstadoSesion.PREGUNTANDO);
        sesion.setPreguntaActual(1);
        sesion.setPreguntasIds(preguntas);
        sesion.setIniciadaEn(Instant.now());
        sesionRepository.save(sesion);

        // Generar y guardar el audio del texto de lectura para reproducción en la consola.
        try {
            byte[] audioTexto = ttsEngine.sintetizar(texto.getCuerpo());
            Path dir = Path.of(System.getProperty("minia.cache.dir", "data/audio-cache"));
            Files.createDirectories(dir);
            Path file = dir.resolve("sesion-" + sesion.getId() + "-texto.pcm");
            Files.write(file, audioTexto);
            sesion.setAudioTextoRuta(file.toString());
            sesionRepository.save(sesion);
        } catch (Exception exception) {
            log.warn("No se pudo generar el audio del texto para la sesion {}: {}", sesion.getId(), exception.getMessage());
        }

        Pregunta pregunta = contentService.pregunta(preguntas.getFirst());
        String saludo = "Hola" + (alias == null || alias.isBlank() ? "" : ", " + alias)
                + ". Vamos a leer y responder preguntas sobre " + texto.getTitulo() + ".";
        byte[] audio = sintetizarParalelo(saludo, pregunta.getEnunciado(), properties.audio().silenceMs());
        TurnoMeta meta = meta(sesion, pregunta, Resultado.NINGUNO, null, null, saludo, false, null, null);
        return new TurnoResult(meta, audio);
    }

    private TurnoResult reanudar(Sesion sesion) {
        Pregunta pregunta = preguntaActual(sesion);
        String mensaje = "Continuamos con la actividad.";
        byte[] audio = sintetizarParalelo(mensaje, pregunta.getEnunciado(), properties.audio().silenceMs());
        TurnoMeta meta = meta(sesion, pregunta, Resultado.NINGUNO, null, null, mensaje, false, null, null);
        return new TurnoResult(meta, audio);
    }

    @Transactional
    public TurnoResult responder(
            UUID sesionId,
            String deviceId,
            UUID turnId,
            byte[] audioPcm
    ) {
        if (turnId == null) {
            turnId = UUID.randomUUID();
        }
        String cacheKey = sesionId + ":" + turnId;
        TurnoResult cached = responseCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        audioService.validatePcm16(audioPcm);
        Sesion sesion = sesionRepository.findById(sesionId)
                .orElseThrow(() -> ApiException.notFound("SESION_NO_ENCONTRADA", "Sesión no encontrada."));
        if (!sesion.getDeviceId().equals(deviceId)) {
            throw ApiException.unauthorized("DEVICE_NO_COINCIDE", "La sesión pertenece a otro dispositivo.");
        }
        if (sesion.getFinalizadaEn() != null) {
            throw ApiException.badRequest("SESION_FINALIZADA", "La sesión ya terminó.");
        }

        Texto texto = contentService.texto(sesion.getTextoId());
        Pregunta pregunta = preguntaActual(sesion);
        int intento = sesion.getIntentosActual() + 1;
        int secuencia = sesion.getTurnoActual() + 1;

        long started = System.nanoTime();
        Evaluacion evaluacion = evaluar(audioPcm, texto, pregunta, intento, secuencia);
        if (evaluacion.resultado() == null) {
            evaluacion = new Evaluacion(Resultado.ERROR, evaluacion.confianza(), evaluacion.transcripcion(),
                    evaluacion.pista(), evaluacion.feedback());
        }
        RespuestaTurno respuesta = aplicar(sesion, texto, pregunta, evaluacion, intento);
        long latency = (System.nanoTime() - started) / 1_000_000;

        sesion.setTurnoActual(secuencia);
        sesionRepository.save(sesion);

        Turno turno = new Turno();
        turno.setId(UUID.randomUUID());
        turno.setSesionId(sesion.getId());
        turno.setTurnId(turnId);
        turno.setSecuencia(secuencia);
        turno.setPreguntaId(pregunta.getId());
        turno.setIntento(intento);
        turno.setTranscripcion(evaluacion.transcripcion());
        turno.setResultado(evaluacion.resultado());
        turno.setConfianza(evaluacion.confianza());
        turno.setPista(respuesta.pista());
        turno.setFeedback(respuesta.feedback());
        turno.setLatenciaMs(latency);
        turno.setAudioMs(audioService.durationMs(respuesta.audio()));
        turno.setAudioRuta(guardarAudio(turno.getId(), respuesta.audio()));
        turno.setCreadoEn(Instant.now());
        turnoRepository.save(turno);

        Pregunta preguntaMeta = respuesta.fin() ? pregunta : preguntaActual(sesion);
        TurnoMeta meta = meta(
                sesion,
                preguntaMeta,
                evaluacion.resultado(),
                respuesta.pista(),
                evaluacion.transcripcion(),
                respuesta.feedback(),
                respuesta.fin(),
                respuesta.codigoError(),
                respuesta.respuestaCorrecta()
        );
        TurnoResult result = new TurnoResult(meta, respuesta.audio());
        responseCache.put(cacheKey, result);
        return result;
    }

    private Evaluacion evaluar(
            byte[] audioPcm,
            Texto texto,
            Pregunta pregunta,
            int intento,
            int secuencia
    ) {
        try {
            return aiEngine.evaluar(new EvaluacionRequest(
                    audioPcm,
                    properties.audio().rate(),
                    texto.getCuerpo(),
                    pregunta.getEnunciado(),
                    pregunta.getRespuestaEsperada(),
                    pregunta.getPalabrasClave(),
                    pregunta.getLineaPista(),
                    intento,
                    secuencia
            ));
        } catch (RuntimeException exception) {
            log.warn("Fallo al evaluar con {}: {}", aiEngine.nombre(), exception.getMessage());
            return new Evaluacion(Resultado.ERROR, 0, "", "",
                    "No pude evaluar tu respuesta. Vamos a intentarlo de nuevo.");
        }
    }

    private RespuestaTurno aplicar(
            Sesion sesion,
            Texto texto,
            Pregunta pregunta,
            Evaluacion evaluacion,
            int intento
    ) {
        Resultado resultado = evaluacion.resultado() == null ? Resultado.ERROR : evaluacion.resultado();
        String feedback = evaluacion.feedback();
        if (feedback == null || feedback.isBlank()) {
            feedback = resultado == Resultado.CORRECTO
                    ? "¡Muy bien! Esa es la respuesta correcta."
                    : "Vamos a revisar el texto y volver a intentarlo.";
        }
        String pista = evaluacion.pista();
        String respuestaCorrecta = null;
        String codigoError = null;
        byte[] audio;

        switch (resultado) {
            case CORRECTO -> {
                sesion.setAciertos(sesion.getAciertos() + 1);
                sesion.setIntentosActual(0);
                return avanzar(sesion, texto, feedback, null);
            }
            case INCORRECTO -> {
                if (intento == 1) {
                    sesion.setIntentosActual(1);
                    sesion.setEstado(EstadoSesion.PISTA);
                    if (pista == null || pista.isBlank()) {
                        pista = "Busca la respuesta en la línea " + pregunta.getLineaPista() + " del texto.";
                    }
                    audio = sintetizarParalelo(feedback, pregunta.getEnunciado(), 250);
                    return new RespuestaTurno(audio, feedback, pista, false, null, null);
                }
                sesion.setErrores(sesion.getErrores() + 1);
                sesion.setIntentosActual(0);
                respuestaCorrecta = pregunta.getRespuestaEsperada();
                feedback = feedback + " La respuesta correcta es: " + respuestaCorrecta + ".";
                return avanzar(sesion, texto, feedback, respuestaCorrecta);
            }
            case SIN_CONFIANZA -> {
                sesion.setEstado(EstadoSesion.SIN_CONFIANZA);
                audio = sintetizarParalelo(feedback, pregunta.getEnunciado(), 250);
                return new RespuestaTurno(audio, feedback, null, false, null, null);
            }
            case ERROR -> {
                sesion.setEstado(EstadoSesion.ERROR);
                codigoError = "IA_NO_DISPONIBLE";
                audio = sintetizarParalelo(feedback, pregunta.getEnunciado(), 250);
                return new RespuestaTurno(audio, feedback, null, false, codigoError, null);
            }
            default -> {
                sesion.setEstado(EstadoSesion.ERROR);
                codigoError = "RESULTADO_DESCONOCIDO";
                audio = sintetizarSeguro("No pude entender el resultado. Vamos a intentarlo de nuevo.");
                return new RespuestaTurno(audio, feedback, null, false, codigoError, null);
            }
        }
    }

    private RespuestaTurno avanzar(Sesion sesion, Texto texto, String feedback, String respuestaCorrecta) {
        int siguiente = sesion.getPreguntaActual() + 1;
        if (siguiente > sesion.getPreguntasIds().size()) {
            sesion.setEstado(EstadoSesion.FINALIZADA);
            sesion.setFinalizadaEn(Instant.now());
            byte[] audio = sintetizarParalelo(feedback, "La actividad terminó. ¡Buen trabajo!", 250);
            return new RespuestaTurno(audio, feedback, null, true, null, respuestaCorrecta);
        }

        sesion.setPreguntaActual(siguiente);
        sesion.setEstado(EstadoSesion.PREGUNTANDO);
        Pregunta siguientePregunta = contentService.pregunta(sesion.getPreguntasIds().get(siguiente - 1));
        byte[] audio = sintetizarParalelo(feedback, siguientePregunta.getEnunciado(), properties.audio().silenceMs());
        return new RespuestaTurno(audio, feedback, null, false, null, respuestaCorrecta);
    }

    @Transactional
    public TurnoResult cerrar(UUID sesionId, String deviceId) {
        Sesion sesion = sesionRepository.findById(sesionId)
                .orElseThrow(() -> ApiException.notFound("SESION_NO_ENCONTRADA", "Sesión no encontrada."));
        if (!sesion.getDeviceId().equals(deviceId)) {
            throw ApiException.unauthorized("DEVICE_NO_COINCIDE", "La sesión pertenece a otro dispositivo.");
        }
        if (sesion.getFinalizadaEn() == null) {
            sesion.setEstado(EstadoSesion.FINALIZADA);
            sesion.setFinalizadaEn(Instant.now());
            sesionRepository.save(sesion);
        }
        String mensaje = "La actividad terminó. ¡Buen trabajo!";
        byte[] audio = sintetizarSeguro(mensaje);
        Pregunta pregunta = preguntaActual(sesion);
        TurnoMeta meta = meta(sesion, pregunta, Resultado.NINGUNO, null, null, mensaje, true, null, null);
        return new TurnoResult(meta, audio);
    }

    @Transactional(readOnly = true)
    public TurnoResult audioActual(UUID sesionId, String deviceId) {
        Sesion sesion = sesionRepository.findById(sesionId)
                .orElseThrow(() -> ApiException.notFound("SESION_NO_ENCONTRADA", "Sesión no encontrada."));
        if (!sesion.getDeviceId().equals(deviceId)) {
            throw ApiException.unauthorized("DEVICE_NO_COINCIDE", "La sesión pertenece a otro dispositivo.");
        }
        if (sesion.getFinalizadaEn() != null) {
            String mensaje = "La actividad terminó. ¡Buen trabajo!";
            Pregunta pregunta = preguntaActual(sesion);
            byte[] audio = sintetizarSeguro(mensaje);
            TurnoMeta meta = meta(sesion, pregunta, Resultado.NINGUNO, null, null, mensaje, true, null, null);
            return new TurnoResult(meta, audio);
        }
        Pregunta pregunta = preguntaActual(sesion);
        String mensaje = "Pregunta " + sesion.getPreguntaActual() + ". " + pregunta.getEnunciado();
        byte[] audio = sintetizarSeguro(mensaje);
        TurnoMeta meta = meta(sesion, pregunta, Resultado.NINGUNO, null, null, mensaje, false, null, null);
        return new TurnoResult(meta, audio);
    }

    private byte[] sintetizarSeguro(String texto) {
        try {
            return ttsEngine.sintetizar(texto);
        } catch (Exception exception) {
            log.warn("Fallo al sintetizar audio: {}", exception.getMessage());
            return audioService.silenceMs(100);
        }
    }

    private byte[] sintetizarParalelo(String textoA, String textoB, int silenceMs) {
        try {
            CompletableFuture<byte[]> futureA = CompletableFuture.supplyAsync(() -> ttsEngine.sintetizar(textoA));
            CompletableFuture<byte[]> futureB = CompletableFuture.supplyAsync(() -> ttsEngine.sintetizar(textoB));
            byte[] audioA = futureA.join();
            byte[] audioB = futureB.join();
            return audioService.concat(audioA, audioService.silenceMs(silenceMs), audioB);
        } catch (Exception exception) {
            log.warn("Fallo al sintetizar audio paralelo: {}", exception.getMessage());
            return audioService.silenceMs(100);
        }
    }

    private String guardarAudio(UUID turnoId, byte[] audio) {
        if (audio == null || audio.length == 0) {
            return null;
        }
        String cacheDir = System.getProperty("minia.cache.dir", "data/audio-cache");
        Path dir = Path.of(cacheDir);
        Path file = dir.resolve("turno-" + turnoId + ".pcm");
        try {
            Files.createDirectories(dir);
            Files.write(file, audio);
            return file.toString();
        } catch (IOException exception) {
            log.warn("No se pudo guardar el audio del turno {}: {}", turnoId, exception.getMessage());
            return null;
        }
    }

    private Pregunta preguntaActual(Sesion sesion) {
        int index = Math.min(Math.max(sesion.getPreguntaActual(), 1), sesion.getPreguntasIds().size()) - 1;
        return contentService.pregunta(sesion.getPreguntasIds().get(index));
    }

    private TurnoMeta meta(
            Sesion sesion,
            Pregunta pregunta,
            Resultado resultado,
            String pista,
            String transcripcion,
            String feedback,
            boolean fin,
            String codigoError,
            String respuestaCorrecta
    ) {
        Texto texto = contentService.texto(sesion.getTextoId());
        return new TurnoMeta(
                sesion.getId().toString(),
                sesion.getEstado().name(),
                resultado.name(),
                sesion.getPreguntaActual(),
                sesion.getPreguntasIds().size(),
                sesion.getIntentosActual() + 1,
                pista,
                transcripcion,
                feedback,
                fin,
                texto.getId().toString(),
                texto.getTitulo(),
                pregunta.getEnunciado(),
                pregunta.getLineaPista(),
                respuestaCorrecta,
                codigoError
        );
    }

    private record RespuestaTurno(
            byte[] audio,
            String feedback,
            String pista,
            boolean fin,
            String codigoError,
            String respuestaCorrecta
    ) {
    }
}
