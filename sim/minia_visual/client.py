"""Cliente HTTP del dispositivo, ejecutado fuera del hilo de Pygame."""

from __future__ import annotations

import queue
import sys
import threading
from pathlib import Path

SIM_DIR = Path(__file__).resolve().parents[1]
if str(SIM_DIR) not in sys.path:
    sys.path.insert(0, str(SIM_DIR))

from esp32_sim import ClienteDispositivo, Respuesta, Frame  # noqa: E402

from .config import Config  # noqa: E402
from .state import Evento  # noqa: E402


class BackendWorker(threading.Thread):
    def __init__(self, cfg: Config, eventos: queue.Queue[Evento]):
        super().__init__(name="minia-backend", daemon=True)
        self.cfg = cfg
        self.eventos = eventos
        self.cola: queue.Queue[tuple[str, dict]] = queue.Queue()
        self.cliente = ClienteDispositivo(
            cfg.base,
            cfg.token,
            cfg.device_id,
            cfg.alias,
            usar_mock=cfg.cliente_mock,
        )
        self.sesion_id = ""

    def submit(self, tipo: str, **datos) -> None:
        self.cola.put((tipo, datos))

    def stop(self) -> None:
        self.cola.put(("__stop__", {}))

    def run(self) -> None:
        while True:
            tipo, datos = self.cola.get()
            if tipo == "__stop__":
                return
            try:
                if tipo == "crear":
                    frame = self.cliente.crear()
                    self.sesion_id = str(frame.meta.get("sesionId") or "")
                    self.eventos.put(Evento("session", {"frame": frame}))
                elif tipo == "turno":
                    pcm = datos.get("pcm", b"")
                    forzar = self.cfg.forzar or None
                    turn_id, respuesta = self.cliente.turno(
                        self.sesion_id,
                        pcm,
                        mock_resultado=forzar,
                    )
                    if respuesta.status != 200:
                        self.eventos.put(Evento(
                            "error",
                            mensaje=f"Turno HTTP {respuesta.status}: {respuesta.body[:180]!r}",
                        ))
                        continue
                    frame = respuesta.frame
                    self.eventos.put(Evento(
                        "turno",
                        {
                            "frame": frame,
                            "headers": dict(respuesta.headers),
                            "turn_id": turn_id,
                        },
                    ))
                elif tipo == "cerrar":
                    respuesta = self.cliente.cerrar(self.sesion_id)
                    if respuesta.status == 200:
                        self.eventos.put(Evento("cerrar", {"frame": respuesta.frame}))
                    else:
                        self.eventos.put(Evento(
                            "error",
                            mensaje=f"Cerrar HTTP {respuesta.status}: {respuesta.body[:180]!r}",
                        ))
            except Exception as exc:  # pragma: no cover - se muestra en la UI
                self.eventos.put(Evento("error", mensaje=f"{type(exc).__name__}: {exc}"))

    def guardar_audio(self, frame: Frame, nombre: str) -> None:
        from esp32_sim import guardar_wav
        salida = Path("sim/out/visual")
        try:
            guardar_wav(salida / nombre, frame.pcm)
        except Exception:
            pass
