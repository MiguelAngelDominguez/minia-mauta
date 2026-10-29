package pe.utec.minia.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "sesion")
@Getter
@Setter
@NoArgsConstructor
public class Sesion {

    @Id
    private UUID id;

    private String deviceId;

    private String alias;

    private UUID textoId;

    @Enumerated(EnumType.STRING)
    private EstadoSesion estado;

    private int preguntaActual;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<UUID> preguntasIds;

    private int aciertos;

    private int errores;

    private int intentosActual;

    private int turnoActual;

    private Instant iniciadaEn;

    private Instant finalizadaEn;

    @Version
    private long version;
}
