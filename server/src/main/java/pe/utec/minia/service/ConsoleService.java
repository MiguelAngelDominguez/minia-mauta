package pe.utec.minia.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utec.minia.ai.AiEngine;
import pe.utec.minia.api.console.dto.DiagnosticoView;
import pe.utec.minia.api.console.dto.PanelResponse;
import pe.utec.minia.api.console.dto.PreguntaView;
import pe.utec.minia.api.console.dto.ResumenPanel;
import pe.utec.minia.api.console.dto.SesionDetalle;
import pe.utec.minia.api.console.dto.SesionResumen;
import pe.utec.minia.api.console.dto.TextoView;
import pe.utec.minia.api.console.dto.TurnoView;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.config.MiniaProperties;
import pe.utec.minia.domain.Pregunta;
import pe.utec.minia.domain.Resultado;
import pe.utec.minia.domain.Sesion;
import pe.utec.minia.domain.Texto;
import pe.utec.minia.domain.Turno;
import pe.utec.minia.repository.SesionRepository;
import pe.utec.minia.repository.TurnoRepository;
import pe.utec.minia.tts.TtsEngine;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConsoleService {

    private final SesionRepository sesionRepository;
    private final TurnoRepository turnoRepository;
    private final ContentService contentService;
    private final AiEngine aiEngine;
    private final TtsEngine ttsEngine;
    private final MiniaProperties properties;

    public ConsoleService(
            SesionRepository sesionRepository,
            TurnoRepository turnoRepository,
            ContentService contentService,
            AiEngine aiEngine,
            TtsEngine ttsEngine,
            MiniaProperties properties
    ) {
        this.sesionRepository = sesionRepository;
        this.turnoRepository = turnoRepository;
        this.contentService = contentService;
        this.aiEngine = aiEngine;
        this.ttsEngine = ttsEngine;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PanelResponse panel() {
        return new PanelResponse(resumen(), sesiones(), textos(), diagnostico());
    }

    @Transactional(readOnly = true)
    public ResumenPanel resumen() {
        long total = sesionRepository.count();
        long activas = sesionRepository.countByFinalizadaEnIsNull();
        long turnos = turnoRepository.count();
        long aciertos = turnoRepository.countByResultado(Resultado.CORRECTO);
        long errores = turnoRepository.countByResultado(Resultado.INCORRECTO);
        Double promedio = turnoRepository.promedioLatenciaMs();
        double latencia = promedio == null ? 0 : Math.round(promedio * 10) / 10.0;
        return new ResumenPanel(total, activas, total - activas, turnos, aciertos, errores, latencia);
    }

    @Transactional(readOnly = true)
    public List<SesionResumen> sesiones() {
        return sesionRepository.findTop50ByOrderByIniciadaEnDesc().stream()
                .map(this::resumen)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TextoView> textos() {
        return contentService.textosActivos().stream()
                .map(this::textoView)
                .toList();
    }

    @Transactional(readOnly = true)
    public SesionDetalle detalle(UUID sesionId) {
        Sesion sesion = sesionRepository.findById(sesionId)
                .orElseThrow(() -> ApiException.notFound("SESION_NO_ENCONTRADA", "Sesión no encontrada."));
        Texto texto = contentService.texto(sesion.getTextoId());
        List<Pregunta> preguntas = sesion.getPreguntasIds().stream()
                .map(contentService::pregunta)
                .toList();
        List<PreguntaView> preguntaViews = preguntas.stream().map(this::preguntaView).toList();
        int index = Math.min(Math.max(sesion.getPreguntaActual(), 1), preguntaViews.size()) - 1;
        PreguntaView actual = preguntaViews.get(index);
        List<TurnoView> historial = turnoRepository.findBySesionIdOrderBySecuenciaAsc(sesionId).stream()
                .map(this::turnoView)
                .toList();
        return new SesionDetalle(
                sesion.getId().toString(),
                sesion.getDeviceId(),
                sesion.getAlias(),
                texto.getId().toString(),
                texto.getTitulo(),
                texto.getGrado(),
                texto.getLineas(),
                sesion.getEstado().name(),
                sesion.getPreguntaActual(),
                sesion.getPreguntasIds().size(),
                sesion.getAciertos(),
                sesion.getErrores(),
                sesion.getIntentosActual(),
                sesion.getTurnoActual(),
                sesion.getIniciadaEn(),
                sesion.getFinalizadaEn(),
                actual,
                preguntaViews,
                historial
        );
    }

    public DiagnosticoView diagnostico() {
        return new DiagnosticoView(
                aiEngine.nombre(),
                properties.ai().mode(),
                ttsEngine.nombre(),
                properties.tts().mode(),
                properties.audio().rate(),
                properties.audio().channels(),
                properties.audio().maxSeconds(),
                1
        );
    }

    @Transactional
    public Map<String, Object> resetDemo() {
        turnoRepository.deleteAllInBatch();
        sesionRepository.deleteAllInBatch();
        return Map.of("ok", true, "mensaje", "Se eliminaron las sesiones y turnos. Los textos y preguntas se mantienen.");
    }

    private SesionResumen resumen(Sesion sesion) {
        String titulo = contentService.texto(sesion.getTextoId()).getTitulo();
        return new SesionResumen(
                sesion.getId().toString(),
                sesion.getDeviceId(),
                sesion.getAlias(),
                sesion.getTextoId().toString(),
                titulo,
                sesion.getEstado().name(),
                sesion.getPreguntaActual(),
                sesion.getPreguntasIds().size(),
                sesion.getAciertos(),
                sesion.getErrores(),
                sesion.getTurnoActual(),
                sesion.getIniciadaEn(),
                sesion.getFinalizadaEn()
        );
    }

    private TextoView textoView(Texto texto) {
        return new TextoView(
                texto.getId().toString(),
                texto.getTitulo(),
                texto.getGrado(),
                texto.getLineas(),
                texto.isActivo(),
                contentService.preguntas(texto.getId()).size()
        );
    }

    private PreguntaView preguntaView(Pregunta pregunta) {
        return new PreguntaView(
                pregunta.getId().toString(),
                pregunta.getOrden(),
                pregunta.getEnunciado(),
                pregunta.getRespuestaEsperada(),
                pregunta.getPalabrasClave(),
                pregunta.getLineaPista(),
                pregunta.getSeccionPista(),
                pregunta.getDificultad()
        );
    }

    private TurnoView turnoView(Turno turno) {
        return new TurnoView(
                turno.getId().toString(),
                turno.getSecuencia(),
                turno.getIntento(),
                turno.getPreguntaId().toString(),
                turno.getResultado().name(),
                turno.getConfianza(),
                turno.getTranscripcion(),
                turno.getPista(),
                turno.getFeedback(),
                turno.getLatenciaMs(),
                turno.getAudioMs(),
                turno.getCreadoEn()
        );
    }
}
