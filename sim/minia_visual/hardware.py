"""Escena isométrica 2.5D del hardware físico de MinIA Mauta."""

from __future__ import annotations

import math
from dataclasses import dataclass

import pygame

from . import theme
from .state import Fase, HardwareState

COS30 = math.sqrt(3) / 2
SIN30 = 0.5


@dataclass
class Punto:
    x: float
    y: float


class HardwareScene:
    """Dibuja placa, módulos, cables y animaciones sobre una mesa de trabajo."""

    def __init__(self, width: int = 940, height: int = 760):
        self.width = width
        self.height = height
        self.ox = 382.0
        self.oy = 170.0
        self.t = 0.0
        self.hitboxes: dict[str, pygame.Rect] = {}
        self._fondo = self._crear_fondo()

    # ------------------------------------------------------------------
    # Proyección y primitivas isométricas
    # ------------------------------------------------------------------
    def p(self, x: float, y: float, z: float = 0.0) -> tuple[int, int]:
        return (
            int(round(self.ox + (x - y) * COS30)),
            int(round(self.oy + (x + y) * SIN30 - z)),
        )

    def polygon(self, target, puntos, color, borde=None, ancho=1):
        pts = [self.p(*punto) for punto in puntos]
        if len(pts) >= 3:
            pygame.draw.polygon(target, color, pts)
            if borde is not None:
                pygame.draw.polygon(target, borde, pts, ancho)
        return pts

    def top(self, target, x, y, w, d, z, color, borde=None):
        return self.polygon(target, [(x, y, z), (x + w, y, z), (x + w, y + d, z), (x, y + d, z)],
                            color, borde)

    def prism(self, target, x, y, w, d, z0, z1, top, left, right, borde=None):
        # Caras laterales visibles primero (frente y derecha).
        self.polygon(target, [(x, y + d, z0), (x + w, y + d, z0), (x + w, y + d, z1), (x, y + d, z1)],
                     left, borde)
        self.polygon(target, [(x + w, y, z0), (x + w, y + d, z0), (x + w, y + d, z1), (x + w, y, z1)],
                     right, borde)
        return self.top(target, x, y, w, d, z1, top, borde)

    def circle_pts(self, cx, cy, z, radio, n=30):
        return [
            (cx + math.cos(2 * math.pi * i / n) * radio,
             cy + math.sin(2 * math.pi * i / n) * radio, z)
            for i in range(n)
        ]

    def circle(self, target, cx, cy, z, radio, color, borde=None, n=30):
        return self.polygon(target, self.circle_pts(cx, cy, z, radio, n), color, borde)

    def text3(self, target, valor, x, y, z, size, color, bold=False, center=True):
        pos = self.p(x, y, z)
        return theme.text(target, valor, size, color, pos, bold=bold,
                          anchor="center" if center else "midleft")

    def bounds(self, puntos) -> pygame.Rect:
        pts = [self.p(*punto) for punto in puntos]
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        return pygame.Rect(min(xs), min(ys), max(1, max(xs) - min(xs)), max(1, max(ys) - min(ys)))

    # ------------------------------------------------------------------
    # Fondo y mesa
    # ------------------------------------------------------------------
    def _crear_fondo(self) -> pygame.Surface:
        fondo = pygame.Surface((self.width, self.height))
        theme.gradient(fondo, theme.BG_TOP, theme.BG_BOTTOM)
        # Cuadrícula tenue de mesa técnica.
        for x in range(0, self.width, 48):
            pygame.draw.line(fondo, (29, 45, 58), (x, 0), (x, self.height), 1)
        for y in range(0, self.height, 48):
            pygame.draw.line(fondo, (29, 45, 58), (0, y), (self.width, y), 1)
        # Halo suave detrás de la placa.
        for radio, alpha in ((330, 18), (280, 20), (220, 24)):
            layer = pygame.Surface((radio * 2, radio * 2), pygame.SRCALPHA)
            pygame.draw.circle(layer, (66, 218, 184, alpha), (radio, radio), radio)
            fondo.blit(layer, (self.width // 2 - radio, 350 - radio))
        return fondo

    def draw(self, surface: pygame.Surface, estado: HardwareState) -> None:
        surface.blit(self._fondo, (0, 0))
        self.hitboxes = {}
        self._sombra(surface)
        self._placa(surface)
        self._cables(surface, estado)
        self._usb(surface, estado)
        self._componentes(surface, estado)
        self._etiquetas(surface)
        self._leyenda(surface, estado)

    def _sombra(self, surface):
        capa = pygame.Surface((self.width, self.height), pygame.SRCALPHA)
        pygame.draw.ellipse(capa, (0, 0, 0, 100), pygame.Rect(72, 145, 780, 560))
        surface.blit(capa, (0, 0))

    def _placa(self, surface):
        # Protoboard 830 puntos, sin carcasa.
        self.prism(surface, 0, 0, 600, 360, 0, 18,
                   theme.BOARD_TOP, theme.BOARD_SIDE, (176, 165, 143), theme.BOARD_EDGE)
        # Rieles de alimentación.
        for y, color in ((12, (205, 82, 78)), (348, (69, 132, 200))):
            pygame.draw.line(surface, color, self.p(16, y, 18.4), self.p(584, y, 18.4), 2)
            pygame.draw.line(surface, (255, 255, 255), self.p(16, y + 1, 18.5),
                             self.p(584, y + 1, 18.5), 1)
        # Agujeros de la protoboard.
        for y in range(28, 340, 14):
            for x in range(24, 580, 14):
                if 145 < x < 395 and 28 < y < 192:
                    continue
                px, py = self.p(x, y, 18.5)
                pygame.draw.ellipse(surface, (175, 165, 145), (px - 1, py - 1, 2, 2))
        # Borde interior (solo contorno, para no tapar agujeros ni rieles).
        borde = [self.p(16, 22, 18.3), self.p(584, 22, 18.3),
                 self.p(584, 338, 18.3), self.p(16, 338, 18.3)]
        pygame.draw.polygon(surface, (195, 185, 163), borde, 1)

    # ------------------------------------------------------------------
    # Componentes
    # ------------------------------------------------------------------
    def _componentes(self, surface, estado):
        orden = [
            ("mic", 170, self._mic),
            ("esp32", 220, self._esp32),
            ("leds", 380, self._leds),
            ("amp", 530, self._amp),
            ("switch", 550, self._switch),
            ("bocina", 740, self._bocina),
        ]
        for _, _, funcion in sorted(orden, key=lambda item: item[1]):
            funcion(surface, estado)

    def _esp32(self, surface, estado):
        x, y, w, d = 155, 35, 230, 150
        self.prism(surface, x, y, w, d, 18, 27, theme.PCB_DARK, (14, 30, 43), (19, 39, 55),
                   (8, 18, 27))
        self.hitboxes["esp32"] = self.bounds([
            (x, y, 18), (x + w, y, 18), (x + w, y + d, 27), (x, y + d, 27)
        ])
        # Pines laterales dorados.
        for lado, px in (("izq", x - 10), ("der", x + w + 2)):
            for i in range(15):
                py = y + 10 + i * 9.0
                self.prism(surface, px, py, 8, 5, 18, 34, theme.GOLD,
                           (150, 118, 45), (177, 142, 53), (102, 80, 35))
        # USB micro en el borde frontal.
        self.prism(surface, 248, y + d - 2, 52, 18, 18, 35,
                   theme.SILVER, (135, 145, 151), (160, 170, 176), (90, 100, 108))
        # Módulo blindado.
        self.prism(surface, 180, 52, 164, 73, 27, 30,
                   (167, 176, 181), (111, 120, 126), (134, 144, 150), (82, 92, 99))
        self.top(surface, 185, 57, 154, 63, 30.2, (190, 199, 204), (110, 121, 128))
        # Antena serigrafiada.
        puntos = [(318, 145), (330, 145), (330, 157), (342, 157), (342, 169), (354, 169)]
        for i in range(len(puntos) - 1):
            pygame.draw.line(surface, (190, 199, 204), self.p(*puntos[i], 30.3),
                             self.p(*puntos[i + 1], 30.3), 2)
        # LED de estado de la placa.
        led_on = estado.powered
        color = (70, 220, 180) if led_on else (30, 64, 72)
        px, py = self.p(367, 155, 30.4)
        pygame.draw.circle(surface, (8, 12, 16), (px, py), 5)
        pygame.draw.circle(surface, color, (px, py), 3)
        if led_on:
            theme.alpha_circle(surface, (px, py), 12, (70, 230, 190), 44)
        # Texto del módulo.
        self.text3(surface, "ESP32", 262, 88, 30.5, 17, (236, 243, 245), bold=True)
        self.text3(surface, "NodeMCU DevKit v1", 262, 106, 30.5, 10, (73, 86, 94))
        # WiFi animado sobre la antena.
        if estado.wifi:
            self._wifi(surface, estado)
        # Etiqueta de pines.
        self.text3(surface, "30 pines", 158, 175, 28.0, 9, (175, 200, 212), center=False)

    def _mic(self, surface, estado):
        x, y, w, d = 55, 95, 82, 58
        self.prism(surface, x, y, w, d, 18, 25, theme.PCB_BLUE, (16, 55, 82), (20, 66, 98),
                   (11, 42, 64))
        self.hitboxes["mic"] = self.bounds([
            (x, y, 18), (x + w, y, 18), (x + w, y + d, 25), (x, y + d, 25)
        ])
        # Puerto del micrófono.
        cx, cy = 94, 124
        self.circle(surface, cx, cy, 25.4, 15, (45, 53, 58), (12, 17, 20), n=36)
        self.circle(surface, cx, cy, 25.6, 10, (24, 30, 34), (8, 11, 13), n=30)
        for i in range(6):
            r = 2 + i * 1.3
            pygame.draw.circle(surface, (72, 83, 89), self.p(cx, cy, 25.7), int(r), 1)
        # Pines del módulo.
        for i in range(6):
            self.prism(surface, x + w + 1, y + 8 + i * 8, 7, 4, 18, 29,
                       theme.GOLD, (150, 118, 45), (177, 142, 53))
        self.text3(surface, "INMP441", 96, 150, 25.8, 11, (223, 239, 248), bold=True)
        if estado.mic_active:
            self._mic_ondas(surface, cx, cy, estado.mic_level)

    def _amp(self, surface, estado):
        x, y, w, d = 410, 85, 110, 75
        self.prism(surface, x, y, w, d, 18, 26, theme.PCB_PURPLE, (44, 36, 77), (56, 45, 92),
                   (31, 25, 58))
        self.hitboxes["amp"] = self.bounds([
            (x, y, 18), (x + w, y, 18), (x + w, y + d, 26), (x, y + d, 26)
        ])
        # Chip amplificador.
        self.prism(surface, 438, 106, 45, 30, 26, 29, (28, 31, 35), (18, 20, 23), (22, 24, 28),
                   (10, 12, 14))
        for i in range(5):
            self.prism(surface, 441 + i * 8, 101, 4, 5, 26, 29, theme.SILVER,
                       (130, 140, 146), (155, 165, 171))
            self.prism(surface, 441 + i * 8, 136, 4, 5, 26, 29, theme.SILVER,
                       (130, 140, 146), (155, 165, 171))
        # Bornera de salida.
        self.prism(surface, 470, 145, 38, 14, 26, 34, (35, 86, 70), (17, 54, 44), (23, 68, 55),
                   (12, 39, 32))
        for i in range(3):
            self.circle(surface, 478 + i * 11, 152, 34.2, 3, (218, 178, 65), (97, 74, 34), n=12)
        self.text3(surface, "MAX98357A", 465, 180, 26.2, 11, (235, 238, 250), bold=True)
        if estado.speaker_active:
            px, py = self.p(455, 96, 29.2)
            pygame.draw.circle(surface, (255, 210, 95), (px, py), 3)

    def _leds(self, surface, estado):
        for nombre, x, color, etiqueta in (
            ("verde", 74, (74, 228, 136), "VERDE"),
            ("rojo", 128, (255, 88, 88), "ROJO"),
        ):
            encendido = estado.led == nombre or (estado.led == "amber" and nombre == "rojo")
            self._led(surface, x, 260, color, encendido, estado)
            self.text3(surface, etiqueta, x + 6, 286, 19.2, 9, (84, 79, 67), bold=True)
        # Resistencias de 220 ohmios.
        self._resistencia(surface, 58, 306, 30, (216, 194, 147))
        self._resistencia(surface, 112, 306, 30, (216, 194, 147))
        self.text3(surface, "220Ω", 100, 327, 19.2, 9, (84, 79, 67), bold=True)

    def _led(self, surface, x, y, color, encendido, estado):
        z0, z1 = 18, 25
        base = (92, 92, 82) if not encendido else color
        self.prism(surface, x, y, 16, 12, z0, z1, base, (55, 58, 54), (72, 74, 68),
                   (40, 42, 40))
        cx, cy = x + 8, y + 6
        px, py = self.p(cx, cy, z1 + 2)
        radio = 8
        if encendido:
            brillo = color
            halo = 24 + int(6 * theme.pulse(self.t, 4.0))
            theme.alpha_circle(surface, (px, py), halo, brillo, 55)
            pygame.draw.circle(surface, brillo, (px, py), radio)
            pygame.draw.circle(surface, (255, 255, 255), (px - 2, py - 3), 2)
        else:
            pygame.draw.circle(surface, (55, 61, 60), (px, py), radio)
            pygame.draw.circle(surface, (33, 39, 39), (px, py), radio, 1)
        # Patas.
        pygame.draw.line(surface, (130, 132, 118), self.p(cx - 4, cy + 6, z0),
                         self.p(cx - 4, cy + 12, z0), 2)
        pygame.draw.line(surface, (130, 132, 118), self.p(cx + 4, cy + 6, z0),
                         self.p(cx + 4, cy + 12, z0), 2)

    def _resistencia(self, surface, x, y, largo, color):
        a = self.p(x, y, 22)
        b = self.p(x + largo, y, 22)
        pygame.draw.line(surface, (75, 68, 52), (a[0] + 2, a[1] + 3), (b[0] + 2, b[1] + 3), 9)
        pygame.draw.line(surface, color, a, b, 8)
        pygame.draw.line(surface, (245, 226, 181), a, b, 3)
        # Franjas.
        for i in range(3):
            t = (i + 1) / 4
            px = int(theme.lerp(a[0], b[0], t))
            py = int(theme.lerp(a[1], b[1], t))
            color_franja = [(120, 75, 54), (195, 80, 55), (63, 86, 120)][i]
            pygame.draw.line(surface, color_franja, (px, py - 5), (px, py + 5), 2)

    def _switch(self, surface, estado):
        x, y, w, d = 228, 290, 66, 30
        self.prism(surface, x, y, w, d, 18, 25, (44, 50, 55), (27, 32, 36), (35, 40, 44),
                   (17, 21, 24))
        self.hitboxes["switch"] = self.bounds([
            (x - 5, y - 5, 18), (x + w + 5, y - 5, 18),
            (x + w + 5, y + d + 5, 25), (x - 5, y + d + 5, 25),
        ])
        sx = x + (22 if estado.powered else 4)
        self.prism(surface, sx, y + 7, 26, 16, 25, 33, (195, 202, 207),
                   (117, 124, 130), (151, 159, 165), (87, 94, 100))
        pygame.draw.line(surface, (220, 226, 230), self.p(sx + 3, y + 10, 33.2),
                         self.p(sx + 23, y + 10, 33.2), 1)
        self.text3(surface, "POWER", 261, 330, 19.2, 9, (84, 79, 67), bold=True)

    def _bocina(self, surface, estado):
        cx, cy, radio = 496, 252, 54
        z0, z1 = 18, 38
        # Cuerpo cilíndrico aproximado con caras verticales.
        puntos_abajo = self.circle_pts(cx, cy, z0, radio, 36)
        puntos_arriba = self.circle_pts(cx, cy, z1, radio, 36)
        for i in range(36):
            j = (i + 1) % 36
            pts = [puntos_abajo[i], puntos_abajo[j], puntos_arriba[j], puntos_arriba[i]]
            angulo = (2 * math.pi * i / 36)
            sombra = int(34 + 20 * (0.5 + 0.5 * math.cos(angulo)))
            color = (sombra, sombra + 3, sombra + 5)
            self.polygon(surface, pts, color, (18, 22, 25))
        # Tapa y cono.
        self.circle(surface, cx, cy, z1 + 0.1, radio, (46, 52, 57), (18, 22, 25), n=40)
        self.circle(surface, cx, cy, z1 + 0.2, radio - 7, (25, 30, 34), (60, 67, 73), n=40)
        for anillo in range(3):
            r = radio - 14 - anillo * 10
            self.circle(surface, cx, cy, z1 + 0.3, r, (19, 24, 28), (66, 75, 82), n=36)
        self.circle(surface, cx, cy, z1 + 0.5, 12, (78, 86, 93), (30, 36, 41), n=24)
        self.text3(surface, "4Ω 3W", cx, cy + 70, 19.2, 10, (84, 79, 67), bold=True)
        if estado.speaker_active:
            self._ondas_bocina(surface, cx, cy, estado.speaker_level)

    # ------------------------------------------------------------------
    # Animaciones
    # ------------------------------------------------------------------
    def _wifi(self, surface, estado):
        overlay = pygame.Surface((self.width, self.height), pygame.SRCALPHA)
        bx, by = self.p(362, 42, 34)
        for i in range(3):
            radio = 14 + i * 12
            alpha = 150 - i * 26
            rect = pygame.Rect(bx - radio, by - radio, radio * 2, radio * 2)
            pygame.draw.arc(overlay, (86, 232, 195, alpha), rect,
                            math.radians(25), math.radians(155), 2)
        # Punto de transmisión.
        pygame.draw.circle(overlay, (128, 255, 220, 210), (bx, by + 4), 3)
        surface.blit(overlay, (0, 0))

    def _mic_ondas(self, surface, cx, cy, nivel):
        overlay = pygame.Surface((self.width, self.height), pygame.SRCALPHA)
        px, py = self.p(cx, cy, 28)
        base = 18 + int(nivel * 18)
        for i in range(3):
            radio = base + i * 13
            alpha = max(20, 120 - i * 28)
            pygame.draw.circle(overlay, (90, 215, 255, alpha), (px, py), radio, 2)
        surface.blit(overlay, (0, 0))
        # Medidor de nivel junto al micrófono.
        x, y = self.p(54, 164, 22)
        pygame.draw.rect(surface, (18, 31, 42), (x - 2, y, 86, 8), border_radius=4)
        pygame.draw.rect(surface, (74, 210, 255), (x, y + 2, int(82 * max(0.02, nivel)), 4),
                         border_radius=2)

    def _ondas_bocina(self, surface, cx, cy, nivel):
        overlay = pygame.Surface((self.width, self.height), pygame.SRCALPHA)
        px, py = self.p(cx, cy, 20)
        for i in range(4):
            radio = 66 + i * 16
            alpha = max(15, 110 - i * 24)
            pygame.draw.arc(overlay, (255, 204, 104, alpha),
                            (px - radio, py - radio, radio * 2, radio * 2),
                            math.radians(210), math.radians(330), 2)
            pygame.draw.arc(overlay, (255, 204, 104, alpha // 2),
                            (px - radio, py - radio, radio * 2, radio * 2),
                            math.radians(30), math.radians(150), 2)
        surface.blit(overlay, (0, 0))

    def _usb(self, surface, estado):
        # Cable micro-USB que sale del ESP32 y cae fuera de la protoboard.
        inicio = self.p(274, 188, 26)
        control1 = self.p(250, 230, 8)
        control2 = self.p(170, 290, 4)
        fin = self.p(105, 342, 0)
        puntos = self._bezier4(inicio, control1, control2, fin, 36)
        pygame.draw.lines(surface, (10, 15, 20), False, [(x + 3, y + 4) for x, y in puntos], 10)
        pygame.draw.lines(surface, (42, 49, 56), False, puntos, 8)
        pygame.draw.lines(surface, (78, 88, 96), False, puntos, 2)
        # Conector.
        self.prism(surface, 262, 181, 31, 18, 26, 38, (210, 216, 220),
                   (134, 143, 149), (162, 171, 177), (84, 94, 102))
        if estado.link:
            for i in range(3):
                t = (estado.packet_phase + i / 3) % 1.0
                x, y = self._bezier4_punto(inicio, control1, control2, fin, t)
                theme.alpha_circle(surface, (int(x), int(y)), 10, (88, 220, 255), 80)
                pygame.draw.circle(surface, (180, 245, 255), (int(x), int(y)), 3)

    def _cables(self, surface, estado):
        rojo = (218, 73, 65)
        negro = (43, 48, 55)
        amarillo = (232, 184, 66)
        azul = (78, 145, 225)
        verde = (71, 190, 124)
        naranja = (232, 132, 54)

        def cable(a, b, color, sag=18, width=3):
            self._cable(surface, a, b, color, sag, width, estado)

        # Bus I2S compartido.
        cable((137, 108, 28), (158, 52, 28), rojo, 16, 3)       # VDD
        cable((137, 116, 28), (158, 64, 28), negro, 12, 3)      # GND
        cable((137, 124, 28), (158, 76, 28), amarillo, 15, 3)   # BCLK
        cable((137, 132, 28), (158, 88, 28), azul, 17, 3)       # LRC
        cable((137, 140, 28), (158, 100, 28), verde, 19, 3)     # DIN
        cable((410, 100, 28), (387, 112, 28), rojo, 14, 3)
        cable((410, 108, 28), (387, 124, 28), negro, 12, 3)
        cable((410, 116, 28), (387, 136, 28), amarillo, 16, 3)
        cable((410, 124, 28), (387, 148, 28), azul, 18, 3)
        cable((410, 132, 28), (387, 160, 28), verde, 20, 3)
        # LEDs y resistencias.
        cable((387, 170, 28), (142, 306, 24), naranja, 30, 3)
        cable((387, 178, 28), (196, 306, 24), naranja, 34, 3)
        cable((88, 276, 24), (88, 306, 24), (83, 190, 124), 6, 3)
        cable((142, 276, 24), (142, 306, 24), (220, 70, 70), 6, 3)
        # Alimentación del switch.
        cable((228, 305, 24), (16, 348, 19), rojo, 20, 3)
        cable((260, 320, 24), (300, 348, 19), negro, 20, 3)

        # Pulsos del bus I2S cuando hay audio.
        if estado.mic_active or estado.speaker_active:
            for i in range(3):
                t = (self.t * 0.9 + i / 3) % 1.0
                self._pulso_cable(surface, (137, 124, 28), (158, 76, 28), amarillo, t)
                self._pulso_cable(surface, (410, 116, 28), (387, 136, 28), amarillo, t)

    def _cable(self, surface, a, b, color, sag, width, estado):
        inicio = self.p(*a)
        fin = self.p(*b)
        control = ((inicio[0] + fin[0]) / 2, (inicio[1] + fin[1]) / 2 + sag)
        puntos = self._bezier(inicio, control, fin, 24)
        pygame.draw.lines(surface, (0, 0, 0), False, [(x + 2, y + 3) for x, y in puntos], width + 2)
        pygame.draw.lines(surface, color, False, puntos, width)
        pygame.draw.lines(surface, tuple(min(255, c + 45) for c in color), False, puntos,
                          max(1, width - 2))

    def _pulso_cable(self, surface, a, b, color, t):
        inicio = self.p(*a)
        fin = self.p(*b)
        control = ((inicio[0] + fin[0]) / 2, (inicio[1] + fin[1]) / 2 + 17)
        x, y = self._bezier_punto(inicio, control, fin, t)
        theme.alpha_circle(surface, (int(x), int(y)), 11, color, 95)
        pygame.draw.circle(surface, (255, 255, 255), (int(x), int(y)), 2)

    @staticmethod
    def _bezier(a, b, c, n=30):
        return [HardwareScene._bezier_punto(a, b, c, i / (n - 1)) for i in range(n)]

    @staticmethod
    def _bezier_punto(a, b, c, t):
        u = 1 - t
        x = u * u * a[0] + 2 * u * t * b[0] + t * t * c[0]
        y = u * u * a[1] + 2 * u * t * b[1] + t * t * c[1]
        return x, y

    @staticmethod
    def _bezier4(a, b, c, d, n=36):
        return [HardwareScene._bezier4_punto(a, b, c, d, i / (n - 1)) for i in range(n)]

    @staticmethod
    def _bezier4_punto(a, b, c, d, t):
        u = 1 - t
        x = u ** 3 * a[0] + 3 * u * u * t * b[0] + 3 * u * t * t * c[0] + t ** 3 * d[0]
        y = u ** 3 * a[1] + 3 * u * u * t * b[1] + 3 * u * t * t * c[1] + t ** 3 * d[1]
        return x, y

    # ------------------------------------------------------------------
    # Etiquetas y leyenda
    # ------------------------------------------------------------------
    def _etiquetas(self, surface):
        # Etiquetas con línea corta para no tapar el hardware.
        etiquetas = [
            ("Micrófono I2S", 18, 330, 74, 120),
            ("ESP32", 18, 370, 260, 100),
            ("Amplificador I2S", 700, 330, 465, 130),
            ("Bocina 40 mm", 700, 370, 500, 290),
            ("LEDs + 220Ω", 18, 430, 104, 270),
            ("Switch SPST", 700, 430, 260, 304),
        ]
        for titulo, tx, ty, ax, ay in etiquetas:
            ax, ay = self.p(ax, ay, 30)
            pygame.draw.line(surface, (85, 112, 126), (tx + 4, ty + 10), (ax, ay), 1)
            pygame.draw.circle(surface, (112, 150, 166), (ax, ay), 2)
            theme.text(surface, titulo, 12, (176, 201, 213), (tx, ty), bold=True)

    def _leyenda(self, surface, estado):
        y = 704
        theme.text(surface, "PROTOBOARD 830 PUNTOS", 14, (122, 154, 169), (28, y), bold=True)
        theme.text(surface, "Cableado físico simulado · audio PCM16 real", 12,
                   (104, 132, 146), (28, y + 22))
        # Indicador de estado en la esquina inferior derecha.
        color = theme.ACCENT if estado.powered else theme.DIM
        if estado.fase in (Fase.GRABANDO, Fase.ENVIANDO, Fase.EVALUANDO):
            color = theme.WARN
        if estado.fase == Fase.ERROR or estado.led == "rojo":
            color = theme.DANGER
        if estado.fase == Fase.FINALIZADO:
            color = theme.GREEN
        dot = (884, y + 10)
        theme.alpha_circle(surface, dot, 16, color, 55)
        pygame.draw.circle(surface, color, dot, 5)
        theme.text(surface, estado.fase.value, 12, color, (868, y + 4), bold=True, anchor="topright")
