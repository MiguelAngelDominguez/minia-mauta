package pe.utec.minia.ai;

import pe.utec.minia.domain.Resultado;

public record Evaluacion(
        Resultado resultado,
        double confianza,
        String transcripcion,
        String pista,
        String feedback
) {
}
