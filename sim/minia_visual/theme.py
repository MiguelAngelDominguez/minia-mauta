"""Paleta, fuentes y utilidades de dibujo del simulador visual."""

from __future__ import annotations

import math
from functools import lru_cache

import pygame


BG_TOP = (13, 21, 33)
BG_BOTTOM = (25, 37, 52)
DESK = (28, 42, 55)
PANEL = (16, 25, 37)
PANEL_BORDER = (57, 78, 98)
TEXT = (232, 241, 246)
MUTED = (145, 166, 181)
DIM = (90, 108, 122)
ACCENT = (67, 224, 184)
ACCENT_2 = (80, 170, 255)
WARN = (255, 185, 74)
DANGER = (255, 92, 92)
GREEN = (78, 229, 138)
RED = (255, 88, 88)
AMBER = (255, 184, 72)
SILVER = (194, 204, 211)
GOLD = (214, 174, 76)

BOARD_TOP = (231, 225, 207)
BOARD_SIDE = (194, 184, 161)
BOARD_EDGE = (154, 144, 124)
PCB_DARK = (24, 48, 67)
PCB_BLUE = (26, 84, 123)
PCB_PURPLE = (74, 60, 120)
PCB_SILVER = (185, 195, 201)
PLASTIC_BLACK = (29, 34, 40)
PLASTIC_WHITE = (224, 224, 216)


@lru_cache(maxsize=128)
def font(size: int, bold: bool = False, mono: bool = False) -> pygame.font.Font:
    if mono:
        name = "Consolas"
    else:
        name = "Bahnschrift" if bold else "Segoe UI"
    try:
        return pygame.font.SysFont(name, size, bold=bold)
    except Exception:
        return pygame.font.Font(None, size)


def text(surface: pygame.Surface, value: str, size: int, color, pos,
         bold: bool = False, mono: bool = False, anchor: str = "topleft") -> pygame.Rect:
    image = font(size, bold=bold, mono=mono).render(str(value), True, color)
    rect = image.get_rect()
    setattr(rect, anchor, pos)
    surface.blit(image, rect)
    return rect


def wrap(value: str, size: int, max_width: int, bold: bool = False,
         mono: bool = False) -> list[str]:
    if not value:
        return []
    f = font(size, bold=bold, mono=mono)
    words = str(value).replace("\n", " \n ").split(" ")
    lines: list[str] = []
    current = ""
    for word in words:
        if word == "\n":
            lines.append(current.strip())
            current = ""
            continue
        probe = (current + " " + word).strip()
        if f.size(probe)[0] <= max_width:
            current = probe
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def wrapped(surface: pygame.Surface, value: str, size: int, color, pos,
            max_width: int, line_height: int | None = None, bold: bool = False,
            mono: bool = False, max_lines: int | None = None) -> int:
    lh = line_height or size + 5
    lines = wrap(value, size, max_width, bold=bold, mono=mono)
    if max_lines is not None:
        lines = lines[:max_lines]
    x, y = pos
    for line in lines:
        text(surface, line, size, color, (x, y), bold=bold, mono=mono)
        y += lh
    return y


def rounded(surface: pygame.Surface, rect: pygame.Rect, color, radius: int = 10,
            width: int = 0) -> None:
    pygame.draw.rect(surface, color, rect, width=width, border_radius=radius)


def panel(surface: pygame.Surface, rect: pygame.Rect, color=PANEL,
          border=PANEL_BORDER, radius: int = 14) -> None:
    shadow = pygame.Surface(rect.size, pygame.SRCALPHA)
    pygame.draw.rect(shadow, (0, 0, 0, 70), shadow.get_rect(), border_radius=radius)
    surface.blit(shadow, (rect.x + 5, rect.y + 7))
    rounded(surface, rect, color, radius)
    rounded(surface, rect, border, radius, 1)


def badge(surface: pygame.Surface, rect: pygame.Rect, label: str, color,
          active: bool = True, size: int = 12) -> None:
    fill = (*color, 46) if active else (38, 50, 62, 220)
    overlay = pygame.Surface(rect.size, pygame.SRCALPHA)
    pygame.draw.rect(overlay, fill, overlay.get_rect(), border_radius=rect.height // 2)
    surface.blit(overlay, rect.topleft)
    pygame.draw.rect(surface, color if active else PANEL_BORDER, rect,
                     width=1, border_radius=rect.height // 2)
    text(surface, label, size, color if active else MUTED,
         (rect.centerx, rect.centery), bold=True, anchor="center")


def gradient(surface: pygame.Surface, top, bottom) -> None:
    height = surface.get_height()
    width = surface.get_width()
    for y in range(height):
        f = y / max(1, height - 1)
        color = (
            int(top[0] + (bottom[0] - top[0]) * f),
            int(top[1] + (bottom[1] - top[1]) * f),
            int(top[2] + (bottom[2] - top[2]) * f),
        )
        pygame.draw.line(surface, color, (0, y), (width, y))


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def pulse(t: float, speed: float = 1.0) -> float:
    return 0.5 + 0.5 * math.sin(t * speed)


def alpha_circle(surface: pygame.Surface, center, radius: int, color,
                 alpha: int = 80) -> None:
    if radius <= 0:
        return
    size = radius * 2 + 4
    layer = pygame.Surface((size, size), pygame.SRCALPHA)
    pygame.draw.circle(layer, (*color, alpha), (size // 2, size // 2), radius)
    surface.blit(layer, (center[0] - size // 2, center[1] - size // 2))
