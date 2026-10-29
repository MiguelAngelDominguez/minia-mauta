package pe.utec.minia.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utec.minia.domain.AudioCache;

public interface AudioCacheRepository extends JpaRepository<AudioCache, String> {
}
