package pe.utec.minia.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "turno")
@Getter
@Setter
@NoArgsConstructor
public class Turno {

    @Id
    private UUID id;

    private UUID sesionId;

    private UUID turnId;

    private int secuencia;

    private UUID preguntaId;

    private int intento;

    private String transcripcion;

    @Enumerated(EnumType.STRING)
    private Resultado resultado;

    private Double confianza;

    private String pista;

    private String feedback;

    private Long latenciaMs;

    private Long audioMs;

    private Instant creadoEn;
}
