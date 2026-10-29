package pe.utec.minia.api.console.dto;

import java.time.Instant;

public record TurnoView(
        String id,
        int secuencia,
        int intento,
        String preguntaId,
        String resultado,
        Double confianza,
        String transcripcion,
        String pista,
        String feedback,
        Long latenciaMs,
        Long audioMs,
        Instant creadoEn
) {
}
