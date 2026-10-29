package pe.utec.minia.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "texto")
@Getter
@Setter
@NoArgsConstructor
public class Texto {

    @Id
    private UUID id;

    private String titulo;

    private String grado;

    private String cuerpo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> lineas;

    private boolean activo;

    private Instant creadoEn;
}
