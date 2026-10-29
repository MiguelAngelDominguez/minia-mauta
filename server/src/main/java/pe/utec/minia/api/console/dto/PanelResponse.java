package pe.utec.minia.api.console.dto;

import java.util.List;

public record PanelResponse(
        ResumenPanel resumen,
        List<SesionResumen> sesiones,
        List<TextoView> textos,
        DiagnosticoView diagnostico
) {
}
