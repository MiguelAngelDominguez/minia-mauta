package pe.utec.minia.service;

import pe.utec.minia.api.dto.TurnoMeta;

public record TurnoResult(TurnoMeta meta, byte[] audio) {
}
