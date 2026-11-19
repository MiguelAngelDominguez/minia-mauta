package pe.utec.minia.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor de rotación de API keys y modelos TTS de Gemini.
 *
 * Cada combinación (apiKey, modelo) tiene su propia cuota independiente.
 * El pool lleva contadores por minuto y por día; cuando una combinación
 * responde 429, se marca como agotada y se pasa a la siguiente.
 */
public class GeminiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(GeminiKeyPool.class);

    private final List<String> apiKeys;
    private final List<String> ttsModels;
    private final String llmModel;

    // Estado por combinación (keyIndex, modelIndex)
    private final List<Slot> slots = new ArrayList<>();
    private final AtomicInteger cursor = new AtomicInteger(0);

    // Contadores globales por minuto y por día
    private volatile Instant minutoActual = Instant.now();
    private volatile Instant diaActual = Instant.now();
    private final ConcurrentHashMap<String, Integer> usoPorMinuto = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> usoPorDia = new ConcurrentHashMap<>();

    // Límites aproximados del free tier (ajustables)
    private static final int LIMITE_POR_MINUTO = 3;
    private static final int LIMITE_POR_DIA = 10;

    public GeminiKeyPool(List<String> apiKeys, List<String> ttsModels, String llmModel) {
        this.apiKeys = apiKeys == null ? List.of() : List.copyOf(apiKeys);
        this.ttsModels = ttsModels == null ? List.of() : List.copyOf(ttsModels);
        this.llmModel = llmModel;
        for (int ki = 0; ki < this.apiKeys.size(); ki++) {
            for (int mi = 0; mi < this.ttsModels.size(); mi++) {
                slots.add(new Slot(ki, mi));
            }
        }
        if (slots.isEmpty()) {
            log.warn("GeminiKeyPool creado sin combinaciones (keys o modelos vacíos).");
        } else {
            log.info("GeminiKeyPool inicializado con {} combinaciones ({} keys x {} modelos).",
                    slots.size(), apiKeys.size(), ttsModels.size());
        }
    }

    public record Slot(int keyIndex, int modelIndex) {
        public String key(List<String> keys) { return keys.get(keyIndex); }
        public String model(List<String> models) { return models.get(modelIndex); }
    }

    public record Combinacion(String apiKey, String model, int keyIndex, int modelIndex) {
    }

    /**
     * Devuelve la próxima combinación disponible (round-robin).
     * Si todas están agotadas por minuto, devuelve la que tenga menos uso.
     */
    public Combinacion siguienteDisponible() {
        resetearContadoresSiCorresponde();
        if (slots.isEmpty()) {
            return null;
        }
        int start = cursor.getAndIncrement();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get((start + i) % slots.size());
            String clave = slot.keyIndex + ":" + slot.modelIndex;
            int usoMin = usoPorMinuto.getOrDefault(clave, 0);
            int usoDia = usoPorDia.getOrDefault(clave, 0);
            if (usoMin < LIMITE_POR_MINUTO && usoDia < LIMITE_POR_DIA) {
                usoPorMinuto.merge(clave, 1, Integer::sum);
                usoPorDia.merge(clave, 1, Integer::sum);
                return new Combinacion(
                        apiKeys.get(slot.keyIndex),
                        ttsModels.get(slot.modelIndex),
                        slot.keyIndex,
                        slot.modelIndex
                );
            }
        }
        // Todas agotadas por minuto: devolver la de menor uso (mejor esfuerzo)
        Slot slot = slots.get(start % slots.size());
        String clave = slot.keyIndex + ":" + slot.modelIndex;
        usoPorMinuto.merge(clave, 1, Integer::sum);
        usoPorDia.merge(clave, 1, Integer::sum);
        log.warn("Todas las combinaciones agotadas por minuto; usando {} (key {}, model {})",
                clave, slot.keyIndex, slot.modelIndex);
        return new Combinacion(
                apiKeys.get(slot.keyIndex),
                ttsModels.get(slot.modelIndex),
                slot.keyIndex,
                slot.modelIndex
        );
    }

    /**
     * Marca una combinación como agotada (respondió 429).
     * Incrementa su contador al límite para que no se vuelva a usar este minuto.
     */
    public void marcarAgotada(int keyIndex, int modelIndex) {
        String clave = keyIndex + ":" + modelIndex;
        usoPorMinuto.put(clave, LIMITE_POR_MINUTO);
        log.info("Combinación agotada: key={}, model={}", keyIndex, modelIndex);
    }

    public void resetearMinuto() {
        usoPorMinuto.clear();
        minutoActual = Instant.now();
        log.debug("Contadores por minuto reseteados.");
    }

    public void resetearDia() {
        usoPorDia.clear();
        diaActual = Instant.now();
        log.debug("Contadores por día reseteados.");
    }

    private void resetearContadoresSiCorresponde() {
        Instant ahora = Instant.now();
        if (ahora.isAfter(minutoActual.plusSeconds(60))) {
            resetearMinuto();
        }
        if (ahora.isAfter(diaActual.plusSeconds(86400))) {
            resetearDia();
        }
    }

    public int totalCombinaciones() {
        return slots.size();
    }

    public int combinacionesDisponibles() {
        resetearContadoresSiCorresponde();
        int disponibles = 0;
        for (Slot slot : slots) {
            String clave = slot.keyIndex + ":" + slot.modelIndex;
            int usoMin = usoPorMinuto.getOrDefault(clave, 0);
            int usoDia = usoPorDia.getOrDefault(clave, 0);
            if (usoMin < LIMITE_POR_MINUTO && usoDia < LIMITE_POR_DIA) {
                disponibles++;
            }
        }
        return disponibles;
    }

    public String llmModel() {
        return llmModel;
    }

    public List<String> apiKeys() {
        return Collections.unmodifiableList(apiKeys);
    }

    public List<String> ttsModels() {
        return Collections.unmodifiableList(ttsModels);
    }
}