package pe.utec.minia.tts;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.utec.minia.config.MiniaProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public class CachedTtsEngine implements TtsEngine {

    private static final Logger log = LoggerFactory.getLogger(CachedTtsEngine.class);

    private final TtsEngine delegate;
    private final MiniaProperties properties;
    private final Cache<String, byte[]> memory = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(Duration.ofHours(12))
            .build();
    private final Path cacheDir;

    public CachedTtsEngine(TtsEngine delegate, MiniaProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
        this.cacheDir = Path.of(System.getProperty("minia.cache.dir", "data/audio-cache"));
    }

    @Override
    public byte[] sintetizar(String texto) {
        String key = hash(texto);
        byte[] cached = memory.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Path file = cacheDir.resolve(key + ".pcm");
        try {
            if (Files.exists(file)) {
                byte[] bytes = Files.readAllBytes(file);
                memory.put(key, bytes);
                return bytes;
            }
            byte[] generated = delegate.sintetizar(texto);
            Files.createDirectories(cacheDir);
            Files.write(file, generated);
            memory.put(key, generated);
            return generated;
        } catch (IOException exception) {
            log.warn("No se pudo usar el caché TTS en disco: {}", exception.getMessage());
            byte[] generated = delegate.sintetizar(texto);
            memory.put(key, generated);
            return generated;
        }
    }

    private String hash(String texto) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((texto + "|" + delegate.nombre() + "|" + properties.audio().rate())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    @Override
    public String nombre() {
        return delegate.nombre() + "-cache";
    }
}
