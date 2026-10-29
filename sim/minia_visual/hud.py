"""Panel lateral: estado, pregunta, feedback, monitor serial y controles."""

from __future__ import annotations

import pygame

from . import theme
from .state import Fase, ModoEntrada


class Hud:
    def __init__(self, width: int = 1280, height: int = 800):
        self.width = width
        self.height = height
        self.rect = pygame.Rect(956, 22, 302, 756)
        self.inner = self.rect.inflate(-28, -28)
        self.buttons: dict[str, pygame.Rect] = {}
        self.text_rect = pygame.Rect(self.inner.x, 632, self.inner.width, 38)
        self.mode_rects: dict[ModoEntrada, pygame.Rect] = {}

    def draw(self, surface: pygame.Surface, app) -> None:
        theme.panel(surface, self.rect)
        self.buttons = {}
        x = self.inner.x
        y = self.inner.y
        w = self.inner.width

        # Cabecera.
        theme.text(surface, "MINIA MAUTA", 20, theme.TEXT, (x, y - 2), bold=True)
        theme.text(surface, "HARDWARE · SIMULACIÓN VISUAL", 10, theme.ACCENT,
                   (x, y + 24), bold=True)
        power = pygame.Rect(x + w - 78, y - 4, 78, 26)
        self.buttons["power"] = power
        theme.badge(surface, power, "ON" if app.powered else "OFF",
                    theme.GREEN if app.powered else theme.DIM, active=app.powered, size=11)
        y += 48

        # Chips de estado.
        chips = [
            ("WIFI", "OK" if app.backend_ok else "—", theme.ACCENT_2, app.backend_ok),
            ("SESIÓN", app.sesion_corta or "—", theme.ACCENT, bool(app.sesion_corta)),
            ("VOZ", app.modo.value, theme.WARN, True),
        ]
        chip_w = (w - 12) // 3
        for i, (label, value, color, active) in enumerate(chips):
            r = pygame.Rect(x + i * (chip_w + 6), y, chip_w, 38)
            pygame.draw.rect(surface, (22, 34, 47), r, border_radius=9)
            pygame.draw.rect(surface, theme.PANEL_BORDER, r, 1, border_radius=9)
            theme.text(surface, label, 9, theme.DIM, (r.x + 7, r.y + 5), bold=True)
            theme.text(surface, value, 12, color if active else theme.MUTED,
                       (r.x + 7, r.y + 18), bold=True)
        y += 54

        # Pregunta.
        turno = app.turno
        card = pygame.Rect(x, y, w, 128)
        pygame.draw.rect(surface, (20, 31, 43), card, border_radius=12)
        pygame.draw.rect(surface, (45, 65, 82), card, 1, border_radius=12)
        tema = turno.titulo or "Esperando conexión con el servidor"
        theme.text(surface, f"PREGUNTA {turno.pregunta_n or '—'}/{turno.total_preguntas or '—'}",
                   10, theme.ACCENT, (x + 12, y + 10), bold=True)
        if turno.estado:
            color = self._color_resultado(turno.resultado)
            theme.text(surface, turno.estado, 9, color, (x + w - 12, y + 10),
                       bold=True, anchor="topright")
        theme.text(surface, tema, 10, theme.MUTED, (x + 12, y + 28))
        tema_y = theme.wrapped(surface, turno.pregunta or "La sesión se iniciará al encender el switch.",
                               13, theme.TEXT, (x + 12, y + 48), w - 24, 18,
                               bold=False, max_lines=4)
        if turno.pista:
            theme.wrapped(surface, "Pista: " + turno.pista, 10, theme.WARN,
                          (x + 12, min(y + 108, tema_y + 2)), w - 24, 14, max_lines=2)
        y += 142

        # Feedback.
        card = pygame.Rect(x, y, w, 130)
        pygame.draw.rect(surface, (20, 31, 43), card, border_radius=12)
        pygame.draw.rect(surface, (45, 65, 82), card, 1, border_radius=12)
        theme.text(surface, "TÚ", 9, theme.MUTED, (x + 12, y + 10), bold=True)
        texto = turno.transcripcion or "—"
        fin = theme.wrapped(surface, texto, 11, (191, 208, 219),
                            (x + 12, y + 25), w - 24, 15, max_lines=2)
        theme.text(surface, "MINIA", 9, theme.ACCENT, (x + 12, fin + 6), bold=True)
        mensaje = turno.feedback or app.mensaje or "Enciende el switch y habla cuando el micrófono se ilumine."
        fin2 = theme.wrapped(surface, mensaje, 11, theme.TEXT,
                             (x + 12, fin + 21), w - 24, 15, max_lines=3)
        if turno.respuesta_correcta:
            theme.wrapped(surface, "Correcta: " + turno.respuesta_correcta, 10, theme.ACCENT,
                          (x + 12, fin2 + 2), w - 24, 14, max_lines=1)
        y += 144

        # Monitor serial.
        serial = pygame.Rect(x, y, w, 196)
        pygame.draw.rect(surface, (9, 16, 24), serial, border_radius=10)
        pygame.draw.rect(surface, (31, 49, 65), serial, 1, border_radius=10)
        theme.text(surface, "MONITOR SERIAL", 9, theme.DIM, (x + 10, y + 8), bold=True)
        theme.text(surface, "ESP32 · 115200", 8, (58, 82, 98), (x + w - 10, y + 8),
                   mono=True, anchor="topright")
        lineas = app.historial[-11:]
        ly = y + 26
        for linea in lineas:
            hora = theme.font(8, mono=True).render(linea.hora, True, (58, 82, 98))
            surface.blit(hora, (x + 10, ly))
            theme.text(surface, linea.texto, 9, linea.color, (x + 48, ly), mono=True)
            ly += 14
        y += 210

        # Entrada de texto.
        theme.text(surface, "RESPUESTA POR TEXTO", 9, theme.DIM, (x, y), bold=True)
        self.text_rect = pygame.Rect(x, y + 16, w, 38)
        borde = theme.ACCENT if app.texto_activo else (52, 74, 92)
        pygame.draw.rect(surface, (18, 29, 41), self.text_rect, border_radius=9)
        pygame.draw.rect(surface, borde, self.text_rect, 1, border_radius=9)
        valor = app.texto or "Escribe y presiona Enter…"
        color = theme.TEXT if app.texto else (88, 108, 122)
        theme.text(surface, valor, 12, color, (self.text_rect.x + 10, self.text_rect.y + 11))
        # Cursor.
        if app.texto_activo and int(pygame.time.get_ticks() / 500) % 2 == 0:
            cursor_x = self.text_rect.x + 10 + theme.font(12).size(app.texto)[0] + 1
            pygame.draw.line(surface, theme.ACCENT, (cursor_x, self.text_rect.y + 9),
                             (cursor_x, self.text_rect.y + 28), 1)
        y = self.text_rect.bottom + 10

        # Modos.
        etiquetas = [(ModoEntrada.MIC, "MIC"), (ModoEntrada.AUTO, "AUTO"),
                     (ModoEntrada.TEXTO, "TEXTO")]
        bw = (w - 12) // 3
        for i, (modo, etiqueta) in enumerate(etiquetas):
            r = pygame.Rect(x + i * (bw + 6), y, bw, 30)
            self.mode_rects[modo] = r
            self.buttons[f"modo:{modo.value}"] = r
            activo = app.modo == modo
            fill = (26, 72, 67) if activo else (22, 34, 47)
            pygame.draw.rect(surface, fill, r, border_radius=8)
            pygame.draw.rect(surface, theme.ACCENT if activo else theme.PANEL_BORDER,
                             r, 1, border_radius=8)
            theme.text(surface, etiqueta, 10, theme.ACCENT if activo else theme.MUTED,
                       (r.centerx, r.centery), bold=True, anchor="center")
        y += 40

        # Estado de dispositivos y atajos.
        mic = "MIC OK" if app.audio.mic_available else "SIN MIC"
        sapi = "SAPI OK" if app.sapi.disponible else "SAPI NO"
        theme.text(surface, f"{mic} · {sapi}", 9,
                   theme.ACCENT if app.audio.mic_available else theme.WARN, (x, y))
        if app.demo_forzado:
            theme.text(surface, f"DEMO: {app.demo_forzado}", 9, theme.WARN,
                       (x + w, y), mono=True, anchor="topright")
        y += 17
        theme.text(surface, "ESPACIO hablar · ENTER enviar · M modo", 9, theme.DIM,
                   (x, y))

    def hit(self, pos) -> str | None:
        for accion, rect in self.buttons.items():
            if rect.collidepoint(pos):
                return accion
        if self.text_rect.collidepoint(pos):
            return "texto"
        return None

    @staticmethod
    def _color_resultado(resultado: str):
        return {
            "CORRECTO": theme.GREEN,
            "INCORRECTO": theme.DANGER,
            "SIN_CONFIANZA": theme.WARN,
            "ERROR": theme.DANGER,
            "NINGUNO": theme.MUTED,
        }.get(resultado, theme.MUTED)
