"""Configuración del simulador visual."""

from __future__ import annotations

import os
import uuid
from dataclasses import dataclass

from .state import ModoEntrada


@dataclass
class Config:
    base: str = "http://localhost:8080"
    token: str = "dev-token"
    device_id: str = "sim-visual-01"
    alias: str = "Mateo"
    modo: ModoEntrada = ModoEntrada.AUTO
    forzar: str = ""
    sin_audio: bool = False
    smoke: bool = False
    mic_index: int | None = None

    @property
    def cliente_mock(self) -> bool:
        return bool(self.forzar)

    def nuevo_device(self) -> None:
        self.device_id = f"{self.device_id.split('-sim-')[0]}-sim-{uuid.uuid4().hex[:6]}"


def config_desde_args(args) -> Config:
    device = args.device or os.environ.get("MINIA_DEVICE", "sim-visual-01")
    if args.nuevo:
        device = f"{device}-{uuid.uuid4().hex[:6]}"
    modo = ModoEntrada(str(args.modo).upper())
    return Config(
        base=args.base or os.environ.get("MINIA_BASE", "http://localhost:8080"),
        token=args.token or os.environ.get("DEVICE_TOKEN", "dev-token"),
        device_id=device,
        alias=args.alias,
        modo=modo,
        forzar=(args.forzar or "").upper(),
        sin_audio=bool(args.sin_audio),
        smoke=bool(args.smoke),
        mic_index=args.mic_index,
    )
