package pe.utec.minia.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pe.utec.minia.domain.Resultado;
import pe.utec.minia.domain.Turno;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TurnoRepository extends JpaRepository<Turno, UUID> {

    List<Turno> findBySesionIdOrderBySecuenciaAsc(UUID sesionId);

    Optional<Turno> findBySesionIdAndTurnId(UUID sesionId, UUID turnId);

    long countByResultado(Resultado resultado);

    @Query("select avg(t.latenciaMs) from Turno t where t.latenciaMs is not null")
    Double promedioLatenciaMs();
}
