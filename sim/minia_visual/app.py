"""Aplicación Pygame del simulador visual de MinIA Mauta."""

from __future__ import annotations

import queue
import threading
import time

import pygame

from . import theme
from .audio import AudioEngine, SapiSynth, MIN_BYTES
from .client import BackendWorker
from .config import Config
from .hardware import HardwareScene
from .hud import Hud
from .state import Evento, Fase, HardwareState, LogLinea, ModoEntrada, TurnoVista

WIDTH = 1280
HEIGHT = 800


class MiniaVisualApp:
    def __init__(self, cfg: Config):
        pygame.init()
        pygame.display.set_caption("MinIA Mauta · Simulador visual de hardware")
        self.screen = pygame.display.set_mode((WIDTH, HEIGHT))
        self.clock = pygame.time.Clock()
        self.cfg = cfg

        self.audio = AudioEngine(enabled=not cfg.sin_audio, mic_index=cfg.mic_index)
        self.sapi = SapiSynth()
        self.eventos: queue.Queue[Evento] = queue.Queue()
        self.worker = BackendWorker(cfg, self.eventos)
        self.worker.start()

        self.hardware = HardwareScene(width=940, height=760)
        self.hw = HardwareState()
        self.hud = Hud(WIDTH, HEIGHT)

        self.powered = False
        self.fase = Fase.APAGADO
        self.turno = TurnoVista()
        self.sesion_id = ""
        self.backend_ok = False
        self.modo = cfg.modo
        self.texto = ""
        self.texto_activo = cfg.modo == ModoEntrada.TEXTO
        self.mensaje = ""
        self.error = ""
        self.historial: list[LogLinea] = []
        self.led = "off"
        self.demo_forzado = cfg.forzar
        self.space_down = False
        self.sintetizando = False
        self.running = True
        self.inicio = time.monotonic()
        self.boot_t0 = 0.0
        self.connect_t0 = 0.0
        self.speak_t0 = 0.0
        self.error_t0 = 0.0
        self.turno_t0 = 0.0
        self._ultimo_error_audio = ""

        pygame.key.start_text_input()
        self.log("Simulador visual iniciado", theme.ACCENT)
        self.log(f"Backend: {cfg.base}", theme.MUTED)
        self.log("Enciende el switch para arrancar el ESP32", theme.MUTED)

    # ------------------------------------------------------------------
    # Propiedades para el HUD
    # ------------------------------------------------------------------
    @property
    def sesion_corta(self) -> str:
        return self.sesion_id[:8] if self.sesion_id else ""

    def log(self, texto: str, color=None):
        if len(texto) > 58:
            texto = texto[:55] + "..."
        self.historial.append(LogLinea(
            texto=texto,
            color=color or (180, 200, 214),
            hora=time.strftime("%H:%M:%S"),
        ))
        self.historial = self.historial[-60:]
        self.mensaje = texto

    # ------------------------------------------------------------------
    # Ciclo principal
    # ------------------------------------------------------------------
    def run(self) -> None:
        while self.running:
            dt = self.clock.tick(60) / 1000.0
            self._eventos()
            self._procesar_backend()
            self._actualizar(dt)
            self._dibujar()
            pygame.display.flip()
            if self.cfg.smoke and time.monotonic() - self.inicio > 2.2:
                self.running = False
        self._cerrar()

    def _eventos(self) -> None:
        for evento in pygame.event.get():
            if evento.type == pygame.QUIT:
                self.running = False
            elif evento.type == pygame.KEYDOWN:
                self._tecla(evento)
            elif evento.type == pygame.KEYUP:
                if evento.key == pygame.K_SPACE:
                    if self.space_down and self.audio.recording:
                        self._terminar_grabacion()
                    self.space_down = False
            elif evento.type == pygame.TEXTINPUT:
                if self.texto_activo:
                    if evento.text.isprintable() and len(self.texto) < 280:
                        self.texto += evento.text
            elif evento.type == pygame.MOUSEBUTTONDOWN and evento.button == 1:
                self._clic(evento.pos)

    def _tecla(self, evento) -> None:
        if evento.key == pygame.K_ESCAPE:
            if self.texto_activo:
                self.texto_activo = False
            else:
                self.running = False
            return

        if self.texto_activo:
            if evento.key == pygame.K_BACKSPACE:
                self.texto = self.texto[:-1]
                return
            if evento.key in (pygame.K_RETURN, pygame.K_KP_ENTER):
                if self.texto.strip():
                    self._enviar_texto()
                return
            if evento.key == pygame.K_TAB:
                self.texto_activo = False
                return
            if evento.key == pygame.K_SPACE:
                return
            return
        else:
            if evento.key == pygame.K_SPACE:
                if self.space_down:
                    return
                if self.modo != ModoEntrada.TEXTO and self.powered:
                    if self.fase in (Fase.ESCUCHANDO, Fase.ESPERANDO, Fase.ERROR):
                        self.space_down = True
                        self._empezar_grabacion()
                return
            if evento.key in (pygame.K_RETURN, pygame.K_KP_ENTER):
                if self.modo == ModoEntrada.TEXTO or self.texto:
                    self.texto_activo = True
                    pygame.key.start_text_input()
                return

        if evento.key == pygame.K_m:
            self.modo = self.modo.siguiente()
            self.texto_activo = self.modo == ModoEntrada.TEXTO
            self.log(f"Modo de entrada: {self.modo.value}", theme.WARN)
        elif evento.key == pygame.K_p:
            self._alternar_energia()
        elif evento.key == pygame.K_TAB:
            self.texto_activo = True
            if self.modo == ModoEntrada.MIC:
                self.modo = ModoEntrada.TEXTO
            pygame.key.start_text_input()
        elif evento.key == pygame.K_BACKSPACE and self.texto:
            self.texto = self.texto[:-1]

    def _clic(self, pos) -> None:
        accion = self.hud.hit(pos)
        if accion:
            if accion == "power":
                self._alternar_energia()
            elif accion.startswith("modo:"):
                self.modo = ModoEntrada(accion.split(":", 1)[1])
                self.texto_activo = self.modo == ModoEntrada.TEXTO
                self.log(f"Modo de entrada: {self.modo.value}", theme.WARN)
            elif accion == "texto":
                self.modo = ModoEntrada.TEXTO
                self.texto_activo = True
                pygame.key.start_text_input()
            return

        for nombre, rect in self.hardware.hitboxes.items():
            if rect.collidepoint(pos):
                if nombre == "switch":
                    self._alternar_energia()
                elif nombre == "mic":
                    if self.audio.recording:
                        self._terminar_grabacion()
                    else:
                        self._empezar_grabacion()
                return
        self.texto_activo = False

    # ------------------------------------------------------------------
    # Energía y sesión
    # ------------------------------------------------------------------
    def _alternar_energia(self) -> None:
        if not self.powered:
            self.powered = True
            self.fase = Fase.ARRANCANDO
            self.boot_t0 = time.monotonic()
            self.turno = TurnoVista()
            self.sesion_id = ""
            self.backend_ok = False
            self.error = ""
            self.led = "off"
            self.texto = ""
            self.texto_activo = False
            self.audio.stop_playback()
            self.log("SWITCH ON · arrancando ESP32", theme.ACCENT)
        else:
            self.powered = False
            self.fase = Fase.APAGADO
            self.sesion_id = ""
            self.backend_ok = False
            self.turno = TurnoVista()
            self.error = ""
            self.led = "off"
            self.audio.stop_playback()
            self.audio.cancel_recording()
            self.texto_activo = False
            self.log("SWITCH OFF · alimentación cortada", theme.DIM)

    def _procesar_backend(self) -> None:
        while not self.eventos.empty():
            evento = self.eventos.get()
            if evento.tipo in ("session", "turno") and not self.powered:
                continue
            if evento.tipo == "session":
                frame = evento.dato["frame"]
                self.turno = TurnoVista.desde_meta(frame.meta)
                self.sesion_id = self.turno.sesion_id
                self.backend_ok = True
                self.fase = Fase.HABLANDO
                self.speak_t0 = time.monotonic()
                self.led = "green"
                self.audio.play(frame.pcm)
                self.log(f"SESIÓN {self.sesion_corta} · {self.turno.titulo}", theme.ACCENT)
                self.log(f"Pregunta {self.turno.pregunta_n}/{self.turno.total_preguntas}", theme.MUTED)
            elif evento.tipo == "turno":
                frame = evento.dato["frame"]
                self.turno = TurnoVista.desde_meta(frame.meta)
                self.backend_ok = True
                self.fase = Fase.HABLANDO
                self.speak_t0 = time.monotonic()
                self.led = self._led_de_resultado(self.turno.resultado)
                self.audio.play(frame.pcm)
                self.log(f"RX {self.turno.resultado} · intento {self.turno.intento}",
                         self._color_de_resultado(self.turno.resultado))
                if self.turno.transcripcion:
                    self.log(f'Tú: "{self.turno.transcripcion}"', theme.MUTED)
                if self.turno.codigo_error:
                    self.log(f"ERROR {self.turno.codigo_error}", theme.DANGER)
            elif evento.tipo == "cerrar":
                self.fase = Fase.FINALIZADO
                self.led = "green"
                self.log("Sesión cerrada", theme.ACCENT)
            elif evento.tipo == "pcm_texto":
                self.sintetizando = False
                if self.powered:
                    self._enviar_pcm(evento.dato)
            elif evento.tipo == "error":
                self.error = evento.mensaje
                self.backend_ok = False if not self.sesion_id else self.backend_ok
                self.log(evento.mensaje, theme.DANGER)
                if self.powered:
                    self.fase = Fase.ERROR
                    self.error_t0 = time.monotonic()
                    self.led = "rojo"

    def _actualizar(self, dt: float) -> None:
        ahora = time.monotonic()
        self.hardware.t = ahora
        if self.powered:
            if self.fase == Fase.ARRANCANDO:
                if ahora - self.boot_t0 >= 1.15:
                    self.fase = Fase.CONECTANDO
                    self.connect_t0 = ahora
                    self.log("WiFi conectado · creando sesión", theme.ACCENT_2)
                    self.worker.submit("crear")
            elif self.fase == Fase.CONECTANDO:
                if ahora - self.connect_t0 > 18:
                    self.error = "El backend no respondió. Revisa que Spring Boot esté en 8080."
                    self.fase = Fase.ERROR
                    self.error_t0 = ahora
                    self.led = "rojo"
                    self.log(self.error, theme.DANGER)
            elif self.fase == Fase.ENVIANDO:
                if ahora - self.turno_t0 > 0.45:
                    self.fase = Fase.EVALUANDO
            elif self.fase == Fase.EVALUANDO:
                if ahora - self.turno_t0 > 45:
                    self.error = "Tiempo de espera agotado."
                    self.fase = Fase.ERROR
                    self.error_t0 = ahora
                    self.led = "rojo"
                    self.log(self.error, theme.DANGER)
            elif self.fase == Fase.GRABANDO:
                if not self.audio.recording:
                    if self.audio.auto_detuvo:
                        self._terminar_grabacion()
                    elif self.audio.ultimo_error:
                        self.error = self.audio.ultimo_error
                        self.fase = Fase.ERROR
                        self.error_t0 = ahora
                        self.led = "rojo"
                        self.log(self.error, theme.DANGER)
            elif self.fase == Fase.HABLANDO:
                if not self.audio.playing and ahora - self.speak_t0 > 0.35:
                    if self.turno.fin:
                        self.fase = Fase.FINALIZADO
                        self.led = "green"
                        self.log("ACTIVIDAD FINALIZADA · ¡buen trabajo!", theme.GREEN)
                    else:
                        self.fase = Fase.ESCUCHANDO
                        if self.audio.ultimo_error:
                            self.log(f"Audio: {self.audio.ultimo_error}", theme.WARN)
                        else:
                            self.log("Listo para tu respuesta", theme.MUTED)
            elif self.fase == Fase.ERROR:
                if ahora - self.error_t0 > 4.5:
                    self.fase = Fase.ESCUCHANDO
                    if self.led == "rojo":
                        self.led = "off"
        else:
            self.fase = Fase.APAGADO

        self.hw.powered = self.powered
        self.hw.fase = self.fase
        self.hw.wifi = self.powered and self.fase not in (Fase.APAGADO, Fase.ARRANCANDO)
        self.hw.link = self.fase in (Fase.ENVIANDO, Fase.EVALUANDO, Fase.HABLANDO)
        self.hw.mic_active = self.audio.recording
        self.hw.mic_level = self.audio.level
        self.hw.speaker_active = self.audio.playing
        self.hw.speaker_level = self.audio.play_level
        self.hw.led = self.led
        self.hw.session_active = bool(self.sesion_id)
        self.hw.packet_phase = (ahora * 0.8) % 1.0
        self.hw.result = self.turno.resultado
        self.hw.last_error = self.error
        self.hw.boot_progress = (
            theme.clamp((ahora - self.boot_t0) / 1.15) if self.fase == Fase.ARRANCANDO else 0.0
        )

    # ------------------------------------------------------------------
    # Audio y turnos
    # ------------------------------------------------------------------
    def _empezar_grabacion(self) -> None:
        if not self.powered:
            return
        if self.modo == ModoEntrada.TEXTO:
            self.texto_activo = True
            self.log("Modo texto: escribe tu respuesta", theme.WARN)
            return
        if self.fase not in (Fase.ESCUCHANDO, Fase.ESPERANDO, Fase.ERROR):
            return
        if not self.audio.mic_available:
            self.log("Micrófono no disponible; usando texto", theme.WARN)
            if self.modo == ModoEntrada.AUTO:
                self.modo = ModoEntrada.TEXTO
            self.texto_activo = True
            return
        self.audio.stop_playback()
        if not self.audio.start_recording():
            self.log(self.audio.ultimo_error or "No se pudo abrir el micrófono", theme.DANGER)
            if self.modo == ModoEntrada.AUTO:
                self.modo = ModoEntrada.TEXTO
                self.texto_activo = True
            return
        self.fase = Fase.GRABANDO
        self.led = "off"
        self.error = ""
        self.log("ESCUCHANDO · habla ahora", theme.WARN)

    def _terminar_grabacion(self) -> None:
        if not self.audio.recording:
            return
        pcm = self.audio.stop_recording()
        if self.fase != Fase.GRABANDO:
            return
        if len(pcm) < MIN_BYTES:
            self.log("Audio muy corto; mantén oprimido y habla", theme.WARN)
            self.fase = Fase.ESCUCHANDO
            return
        self._enviar_pcm(pcm)

    def _enviar_pcm(self, pcm: bytes) -> None:
        if not self.powered or not self.sesion_id:
            self.log("Sin sesión activa; enciende el switch", theme.DANGER)
            self.fase = Fase.ESCUCHANDO if self.powered else Fase.APAGADO
            return
        self.fase = Fase.ENVIANDO
        self.turno_t0 = time.monotonic()
        self.led = "amber"
        self.error = ""
        self.log(f"TX {len(pcm)} B PCM16 · subiendo turno", theme.ACCENT_2)
        self.worker.submit("turno", pcm=pcm)

    def _enviar_texto(self) -> None:
        texto = self.texto.strip()
        if not texto:
            return
        if not self.powered:
            self.log("Enciende el switch primero", theme.WARN)
            return
        if not self.sesion_id:
            self.log("Espera a que se cree la sesión", theme.WARN)
            return
        if self.fase not in (Fase.ESCUCHANDO, Fase.ESPERANDO, Fase.ERROR):
            self.log("Espera a que termine el audio actual", theme.WARN)
            return
        if not self.sapi.disponible:
            self.log(self.sapi.error or "SAPI no disponible", theme.DANGER)
            return
        if self.sintetizando:
            return
        self.texto = ""
        self.texto_activo = False
        self.sintetizando = True
        self.fase = Fase.ENVIANDO
        self.turno_t0 = time.monotonic()
        self.led = "amber"
        self.log(f'Texto: "{texto[:44]}"', theme.MUTED)
        threading.Thread(target=self._sintetizar, args=(texto,), daemon=True).start()

    def _sintetizar(self, texto: str) -> None:
        try:
            pcm = self.sapi.sintetizar(texto)
            if not pcm:
                raise RuntimeError("SAPI no generó audio")
            self.eventos.put(Evento("pcm_texto", pcm))
        except Exception as exc:
            self.eventos.put(Evento("error", mensaje=f"Texto a voz: {exc}"))

    # ------------------------------------------------------------------
    # Dibujo
    # ------------------------------------------------------------------
    def _dibujar(self) -> None:
        self.hardware.draw(self.screen, self.hw)
        # Cabecera del área de hardware.
        theme.text(self.screen, "MinIA Mauta", 18, theme.TEXT, (24, 20), bold=True)
        theme.text(self.screen, "Simulación física · ESP32 + I2S + PCM16",
                   10, theme.MUTED, (24, 43))
        self.hud.draw(self.screen, self)
        if not self.powered:
            self._aviso_encendido()
        elif self.fase == Fase.ARRANCANDO:
            self._progreso_arranque()

    def _aviso_encendido(self) -> None:
        capa = pygame.Surface((WIDTH, HEIGHT), pygame.SRCALPHA)
        pygame.draw.rect(capa, (6, 12, 19, 60), pygame.Rect(0, 0, 940, 760))
        self.screen.blit(capa, (0, 0))
        r = pygame.Rect(228, 330, 484, 92)
        theme.panel(self.screen, r, (15, 26, 38), theme.PANEL_BORDER, 16)
        theme.text(self.screen, "SWITCH APAGADO", 12, theme.WARN,
                   (r.centerx, r.y + 18), bold=True, anchor="midtop")
        theme.text(self.screen, "Haz clic en el switch o presiona P para encender",
                   15, theme.TEXT, (r.centerx, r.y + 42), anchor="midtop")
        theme.text(self.screen, "El hardware se energiza y arranca la sesión",
                   11, theme.MUTED, (r.centerx, r.y + 64), anchor="midtop")

    def _progreso_arranque(self) -> None:
        r = pygame.Rect(228, 330, 484, 72)
        theme.panel(self.screen, r, (15, 26, 38), theme.PANEL_BORDER, 16)
        theme.text(self.screen, "ARRANCANDO ESP32", 12, theme.ACCENT,
                   (r.centerx, r.y + 16), bold=True, anchor="midtop")
        barra = pygame.Rect(r.x + 28, r.y + 42, r.width - 56, 8)
        pygame.draw.rect(self.screen, (31, 48, 63), barra, border_radius=4)
        ancho = int(barra.width * self.hw.boot_progress)
        pygame.draw.rect(self.screen, theme.ACCENT, (barra.x, barra.y, ancho, barra.height),
                         border_radius=4)

    # ------------------------------------------------------------------
    # Utilidades
    # ------------------------------------------------------------------
    @staticmethod
    def _led_de_resultado(resultado: str) -> str:
        return {
            "CORRECTO": "verde",
            "INCORRECTO": "rojo",
            "SIN_CONFIANZA": "amber",
            "ERROR": "rojo",
        }.get(resultado, "off")

    @staticmethod
    def _color_de_resultado(resultado: str):
        return {
            "CORRECTO": theme.GREEN,
            "INCORRECTO": theme.DANGER,
            "SIN_CONFIANZA": theme.WARN,
            "ERROR": theme.DANGER,
        }.get(resultado, theme.MUTED)

    def _cerrar(self) -> None:
        try:
            self.worker.stop()
            self.worker.join(timeout=1.0)
        except Exception:
            pass
        try:
            self.audio.close()
        except Exception:
            pass
        pygame.quit()
