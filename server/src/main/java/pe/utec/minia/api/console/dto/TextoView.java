package pe.utec.minia.api.console.dto;

import java.util.List;

public record TextoView(
        String id,
        String titulo,
        String grado,
        List<String> lineas,
        boolean activo,
        int totalPreguntas
) {
}
