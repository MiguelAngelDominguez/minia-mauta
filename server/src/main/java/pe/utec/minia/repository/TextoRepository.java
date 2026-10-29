package pe.utec.minia.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utec.minia.domain.Texto;

import java.util.List;
import java.util.UUID;

public interface TextoRepository extends JpaRepository<Texto, UUID> {

    List<Texto> findByActivoTrueOrderByCreadoEnAsc();
}
