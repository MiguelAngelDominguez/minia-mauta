"""Entrada del simulador visual 2.5D de MinIA Mauta.

Uso:
    python sim/run_visual.py
    python sim/run_visual.py --modo texto
    python sim/run_visual.py --base http://192.168.1.50:8080 --nuevo
    python sim/run_visual.py --sin-audio --smoke
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

SIM_DIR = Path(__file__).resolve().parent
if str(SIM_DIR) not in sys.path:
    sys.path.insert(0, str(SIM_DIR))

from minia_visual.app import MiniaVisualApp  # noqa: E402
from minia_visual.config import config_desde_args  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Simulador visual del hardware MinIA Mauta")
    parser.add_argument("--base", default=os.environ.get("MINIA_BASE", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("DEVICE_TOKEN", "dev-token"))
    parser.add_argument("--device", default=os.environ.get("MINIA_DEVICE", "sim-visual-01"))
    parser.add_argument("--alias", default="Mateo")
    parser.add_argument("--modo", choices=["mic", "auto", "texto"], default="auto",
                        help="Modo de entrada inicial")
    parser.add_argument("--forzar", choices=["CORRECTO", "INCORRECTO", "SIN_CONFIANZA", "ERROR"],
                        default="", help="Solo para demos con backend mock")
    parser.add_argument("--nuevo", action="store_true",
                        help="Genera un deviceId nuevo para empezar una sesión limpia")
    parser.add_argument("--mic-index", type=int, default=None,
                        help="Índice de dispositivo de entrada de PyAudio")
    parser.add_argument("--sin-audio", action="store_true",
                        help="Desactiva micrófono y parlante")
    parser.add_argument("--smoke", action="store_true",
                        help="Abre la ventana y sale a los 2 segundos (prueba de humo)")
    args = parser.parse_args()

    cfg = config_desde_args(args)
    print("MinIA Mauta · simulador visual")
    print(f"  Backend : {cfg.base}")
    print(f"  Device  : {cfg.device_id}")
    print(f"  Modo    : {cfg.modo.value}")
    print("  Controles: clic switch/P encender · ESPACIO hablar · ENTER texto · M modo")
    if cfg.smoke:
        print("  Smoke test: la ventana se cerrará automáticamente.")
    try:
        MiniaVisualApp(cfg).run()
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
