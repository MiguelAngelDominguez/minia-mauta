package pe.utec.minia.api.console.dto;

public record DiagnosticoView(
        String motorIa,
        String modoIa,
        String motorTts,
        String modoTts,
        int rate,
        int canales,
        int maxSegundos,
        int contratoVersion
) {
}
