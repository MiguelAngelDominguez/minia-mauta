package pe.utec.minia.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utec.minia.domain.Sesion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SesionRepository extends JpaRepository<Sesion, UUID> {

    Optional<Sesion> findFirstByDeviceIdAndFinalizadaEnIsNull(String deviceId);

    List<Sesion> findTop50ByOrderByIniciadaEnDesc();

    List<Sesion> findByDeviceIdOrderByIniciadaEnDesc(String deviceId);

    long countByFinalizadaEnIsNull();
}
