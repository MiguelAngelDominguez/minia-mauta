package pe.utec.minia.api.console.dto;

import java.util.List;

public record PreguntaView(
        String id,
        int orden,
        String enunciado,
        String respuestaEsperada,
        List<String> palabrasClave,
        int lineaPista,
        String seccionPista,
        String dificultad
) {
}
