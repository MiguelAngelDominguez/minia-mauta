package pe.utec.minia.api.dto;

import java.util.UUID;

public record CrearSesionRequest(String deviceId, String alias, UUID textoId) {
}
