package pe.utec.minia.service;

import org.springframework.stereotype.Service;
import pe.utec.minia.api.error.ApiException;
import pe.utec.minia.domain.Pregunta;
import pe.utec.minia.domain.Texto;
import pe.utec.minia.repository.PreguntaRepository;
import pe.utec.minia.repository.TextoRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ContentService {

    private final TextoRepository textoRepository;
    private final PreguntaRepository preguntaRepository;

    public ContentService(TextoRepository textoRepository, PreguntaRepository preguntaRepository) {
        this.textoRepository = textoRepository;
        this.preguntaRepository = preguntaRepository;
    }

    public List<Texto> textosActivos() {
        return textoRepository.findByActivoTrueOrderByCreadoEnAsc();
    }

    public Texto textoActivo() {
        return textosActivos().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("SIN_TEXTOS", "No hay textos activos."));
    }

    public Texto texto(UUID id) {
        return textoRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("TEXTO_NO_ENCONTRADO", "Texto no encontrado."));
    }

    public Pregunta pregunta(UUID id) {
        return preguntaRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("PREGUNTA_NO_ENCONTRADA", "Pregunta no encontrada."));
    }

    public List<Pregunta> preguntas(UUID textoId) {
        return preguntaRepository.findByTextoIdAndActivoTrueOrderByOrdenAsc(textoId);
    }

    public List<UUID> seleccionarCinco(UUID textoId) {
        List<Pregunta> preguntas = new ArrayList<>(preguntas(textoId));
        if (preguntas.size() < 5) {
            throw ApiException.badRequest("BANCO_INSUFICIENTE",
                    "El texto necesita al menos 5 preguntas activas.");
        }
        Collections.shuffle(preguntas);
        return preguntas.stream().limit(5).map(Pregunta::getId).toList();
    }
}
