package pe.utec.minia.ai;

import java.util.List;

public record EvaluacionRequest(
        byte[] audioPcm,
        int rate,
        String texto,
        String pregunta,
        String respuestaEsperada,
        List<String> palabrasClave,
        int lineaPista,
        int intento,
        int secuencia,
        String mockResultado,
        String mockTranscripcion
) {
}
