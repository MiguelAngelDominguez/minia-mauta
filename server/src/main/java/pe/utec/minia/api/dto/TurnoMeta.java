package pe.utec.minia.api.dto;

public record TurnoMeta(
        String sesionId,
        String estado,
        String resultado,
        int preguntaN,
        int totalPreguntas,
        int intento,
        String pista,
        String transcripcion,
        String feedback,
        boolean fin,
        String textoId,
        String titulo,
        String pregunta,
        int lineaPista,
        String respuestaCorrecta,
        String codigoError
) {
}
