package pe.utec.minia.ai;

public interface AiEngine {

    Evaluacion evaluar(EvaluacionRequest request);

    String nombre();
}
