package pe.utec.minia.ai;

import org.junit.jupiter.api.Test;
import pe.utec.minia.domain.Resultado;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockAiEngineTest {

    private final MockAiEngine engine = new MockAiEngine();

    @Test
    void deterministicSequenceAlternatesCorrectAndIncorrect() {
        Resultado[] esperados = {
                Resultado.CORRECTO, Resultado.INCORRECTO, Resultado.CORRECTO, Resultado.INCORRECTO,
                Resultado.CORRECTO, Resultado.INCORRECTO, Resultado.CORRECTO, Resultado.INCORRECTO
        };
        for (int secuencia = 1; secuencia <= esperados.length; secuencia++) {
            Evaluacion evaluacion = engine.evaluar(request(secuencia, null, null));
            assertEquals(esperados[secuencia - 1], evaluacion.resultado());
        }
    }

    @Test
    void forcedResultWinsOverSequence() {
        Evaluacion evaluacion = engine.evaluar(request(1, "SIN_CONFIANZA", null));

        assertEquals(Resultado.SIN_CONFIANZA, evaluacion.resultado());
        assertTrue(evaluacion.feedback().contains("No te escuché"));
    }

    @Test
    void usesProvidedTranscriptionAndHintLine() {
        Evaluacion evaluacion = engine.evaluar(request(1, "INCORRECTO", "estaba en la rama"));

        assertEquals("estaba en la rama", evaluacion.transcripcion());
        assertTrue(evaluacion.pista().contains("línea 4"));
    }

    @Test
    void invalidForcedResultFallsBackToSinConfianza() {
        Evaluacion evaluacion = engine.evaluar(request(1, "NO_EXISTE", null));

        assertEquals(Resultado.SIN_CONFIANZA, evaluacion.resultado());
    }

    private EvaluacionRequest request(int secuencia, String mockResultado, String mockTranscripcion) {
        return new EvaluacionRequest(
                new byte[]{0, 0},
                16000,
                "texto",
                "pregunta",
                "respuesta",
                List.of("palabra"),
                4,
                1,
                secuencia,
                mockResultado,
                mockTranscripcion
        );
    }
}
