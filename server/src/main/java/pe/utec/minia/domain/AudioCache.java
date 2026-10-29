package pe.utec.minia.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audio_cache")
@Getter
@Setter
@NoArgsConstructor
public class AudioCache {

    @Id
    private String hash;

    private String texto;

    private String voz;

    private int rate;

    private String ruta;

    private long bytes;

    private Instant creadoEn;
}
