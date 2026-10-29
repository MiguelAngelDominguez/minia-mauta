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

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pregunta")
@Getter
@Setter
@NoArgsConstructor
public class Pregunta {

    @Id
    private UUID id;

    private UUID textoId;

    private int orden;

    private String enunciado;

    private String respuestaEsperada;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> palabrasClave;

    private int lineaPista;

    private String seccionPista;

    private String dificultad;

    private boolean activo;
}
