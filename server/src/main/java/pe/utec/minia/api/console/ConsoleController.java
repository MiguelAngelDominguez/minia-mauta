package pe.utec.minia.api.console;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.utec.minia.api.console.dto.DiagnosticoView;
import pe.utec.minia.api.console.dto.PanelResponse;
import pe.utec.minia.api.console.dto.SesionDetalle;
import pe.utec.minia.api.console.dto.SesionResumen;
import pe.utec.minia.api.console.dto.TextoView;
import pe.utec.minia.service.ConsoleService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/console")
public class ConsoleController {

    private final ConsoleService consoleService;

    public ConsoleController(ConsoleService consoleService) {
        this.consoleService = consoleService;
    }

    @GetMapping("/panel")
    public PanelResponse panel() {
        return consoleService.panel();
    }

    @GetMapping("/sesiones")
    public List<SesionResumen> sesiones() {
        return consoleService.sesiones();
    }

    @GetMapping("/sesiones/{id}")
    public SesionDetalle detalle(@PathVariable UUID id) {
        return consoleService.detalle(id);
    }

    @GetMapping("/textos")
    public List<TextoView> textos() {
        return consoleService.textos();
    }

    @GetMapping("/diagnostico")
    public DiagnosticoView diagnostico() {
        return consoleService.diagnostico();
    }

    @PostMapping("/reset-demo")
    public Map<String, Object> resetDemo() {
        return consoleService.resetDemo();
    }
}
