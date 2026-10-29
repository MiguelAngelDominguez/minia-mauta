package pe.utec.minia.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utec.minia.domain.Pregunta;

import java.util.List;
import java.util.UUID;

public interface PreguntaRepository extends JpaRepository<Pregunta, UUID> {

    List<Pregunta> findByTextoIdAndActivoTrueOrderByOrdenAsc(UUID textoId);
}
