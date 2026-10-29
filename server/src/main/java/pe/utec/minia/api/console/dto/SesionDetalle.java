package pe.utec.minia.api.console.dto;

import java.time.Instant;
import java.util.List;

public record SesionDetalle(
        String id,
        String deviceId,
        String alias,
        String textoId,
        String titulo,
        String grado,
        List<String> lineas,
        String estado,
        int preguntaActual,
        int totalPreguntas,
        int aciertos,
        int errores,
        int intentosActual,
        int turnos,
        Instant iniciadaEn,
        Instant finalizadaEn,
        PreguntaView preguntaActualDetalle,
        List<PreguntaView> preguntas,
        List<TurnoView> historial
) {
}
