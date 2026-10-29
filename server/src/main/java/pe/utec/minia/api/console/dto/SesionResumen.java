package pe.utec.minia.api.console.dto;

import java.time.Instant;

public record SesionResumen(
        String id,
        String deviceId,
        String alias,
        String textoId,
        String titulo,
        String estado,
        int preguntaActual,
        int totalPreguntas,
        int aciertos,
        int errores,
        int turnos,
        Instant iniciadaEn,
        Instant finalizadaEn
) {
}
