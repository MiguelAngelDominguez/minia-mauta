"""Máquina de estados y estado visual compartido."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class Fase(str, Enum):
    APAGADO = "APAGADO"
    ARRANCANDO = "ARRANCANDO"
    CONECTANDO = "CONECTANDO"
    ESCUCHANDO = "ESCUCHANDO"
    ESPERANDO = "ESPERANDO"
    GRABANDO = "GRABANDO"
    ENVIANDO = "ENVIANDO"
    EVALUANDO = "EVALUANDO"
    HABLANDO = "HABLANDO"
    FINALIZADO = "FINALIZADO"
    ERROR = "ERROR"


class ModoEntrada(str, Enum):
    MIC = "MIC"
    TEXTO = "TEXTO"
    AUTO = "AUTO"

    def siguiente(self) -> "ModoEntrada":
        orden = [ModoEntrada.MIC, ModoEntrada.AUTO, ModoEntrada.TEXTO]
        return orden[(orden.index(self) + 1) % len(orden)]


@dataclass
class HardwareState:
    powered: bool = False
    fase: Fase = Fase.APAGADO
    boot_progress: float = 0.0
    wifi: bool = False
    link: bool = False
    mic_active: bool = False
    mic_level: float = 0.0
    speaker_active: bool = False
    speaker_level: float = 0.0
    led: str = "off"
    led_until: float = 0.0
    session_active: bool = False
    packet_phase: float = 0.0
    result: str = "NINGUNO"
    last_error: str = ""


@dataclass
class TurnoVista:
    sesion_id: str = ""
    pregunta_n: int = 0
    total_preguntas: int = 0
    intento: int = 0
    estado: str = ""
    resultado: str = "NINGUNO"
    titulo: str = ""
    pregunta: str = ""
    transcripcion: str = ""
    feedback: str = ""
    pista: str = ""
    respuesta_correcta: str = ""
    codigo_error: str = ""
    fin: bool = False
    confianza: float = 0.0

    @classmethod
    def desde_meta(cls, meta: dict | None) -> "TurnoVista":
        if not meta:
            return cls()
        return cls(
            sesion_id=str(meta.get("sesionId") or ""),
            pregunta_n=int(meta.get("preguntaN") or 0),
            total_preguntas=int(meta.get("totalPreguntas") or 0),
            intento=int(meta.get("intento") or 0),
            estado=str(meta.get("estado") or ""),
            resultado=str(meta.get("resultado") or "NINGUNO"),
            titulo=str(meta.get("titulo") or ""),
            pregunta=str(meta.get("pregunta") or ""),
            transcripcion=str(meta.get("transcripcion") or ""),
            feedback=str(meta.get("feedback") or ""),
            pista=str(meta.get("pista") or ""),
            respuesta_correcta=str(meta.get("respuestaCorrecta") or ""),
            codigo_error=str(meta.get("codigoError") or ""),
            fin=bool(meta.get("fin")),
        )


@dataclass
class Evento:
    tipo: str
    dato: object = None
    mensaje: str = ""


@dataclass
class LogLinea:
    texto: str
    color: tuple[int, int, int] = (180, 200, 214)
    hora: str = ""


@dataclass
class AppState:
    powered: bool = False
    fase: Fase = Fase.APAGADO
    modo: ModoEntrada = ModoEntrada.AUTO
    turno: TurnoVista = field(default_factory=TurnoVista)
    historial: list[LogLinea] = field(default_factory=list)
    texto: str = ""
    texto_activo: bool = False
    mensaje: str = ""
    error: str = ""
    sapi_disponible: bool = True
    mic_disponible: bool = True
    audio_disponible: bool = True
    backend: str = ""
    device_id: str = ""
    alias: str = ""
    demo_forzado: str = ""
