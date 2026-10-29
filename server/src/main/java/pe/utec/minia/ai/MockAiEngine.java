package pe.utec.minia.ai;

import pe.utec.minia.domain.Resultado;

public class MockAiEngine implements AiEngine {

    @Override
    public Evaluacion evaluar(EvaluacionRequest request) {
        Resultado resultado = resultado(request);
        String pista = "Busca la respuesta en la línea " + request.lineaPista() + " del texto.";
        String transcripcion = request.mockTranscripcion() == null || request.mockTranscripcion().isBlank()
                ? "Respuesta simulada del turno " + request.secuencia()
                : request.mockTranscripcion();
        return switch (resultado) {
            case CORRECTO -> new Evaluacion(resultado, 0.95, transcripcion, pista,
                    "¡Muy bien! Esa es la respuesta correcta.");
            case INCORRECTO -> new Evaluacion(resultado, 0.20, transcripcion, pista,
                    "Casi. " + pista + " Inténtalo otra vez.");
            case SIN_CONFIANZA -> new Evaluacion(resultado, 0.10, transcripcion, pista,
                    "No te escuché bien. ¿Puedes repetir tu respuesta?");
            default -> new Evaluacion(Resultado.ERROR, 0.0, transcripcion, pista,
                    "No pude evaluar tu respuesta. Vamos a intentarlo de nuevo.");
        };
    }

    private Resultado resultado(EvaluacionRequest request) {
        if (request.mockResultado() != null && !request.mockResultado().isBlank()) {
            try {
                return Resultado.valueOf(request.mockResultado().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return Resultado.SIN_CONFIANZA;
            }
        }
        return request.secuencia() % 4 == 2 || request.secuencia() % 4 == 0
                ? Resultado.INCORRECTO
                : Resultado.CORRECTO;
    }

    @Override
    public String nombre() {
        return "mock";
    }
}
