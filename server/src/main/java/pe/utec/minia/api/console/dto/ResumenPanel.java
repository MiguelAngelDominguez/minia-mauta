package pe.utec.minia.api.console.dto;

public record ResumenPanel(
        long sesiones,
        long activas,
        long finalizadas,
        long turnos,
        long aciertos,
        long errores,
        double latenciaPromedioMs
) {
}
