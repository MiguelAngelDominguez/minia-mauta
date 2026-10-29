"""Simulador del ESP32 para el contrato binario de MinIA Mauta.

No requiere dependencias externas. Genera tonos PCM16 como audio de prueba,
parsea el framing [u32 BE len(JSON)][JSON UTF-8][PCM16LE] y guarda las
respuestas del servidor como WAV para poder escucharlas.

Uso:
    python esp32_sim.py --modo todo
    python esp32_sim.py --modo demo --nuevo
    python esp32_sim.py --modo contrato --base http://192.168.1.50:8080
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys
import urllib.error
import urllib.request
import uuid
import wave
from dataclasses import dataclass
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

CONTRATO_VERSION = 1
AUDIO_RATE = 16000
AUDIO_CHANNELS = 1
MAX_SECONDS = 30


@dataclass
class Frame:
    meta: dict
    pcm: bytes


@dataclass
class Respuesta:
    status: int
    headers: dict[str, str]
    body: bytes

    @property
    def frame(self) -> Frame:
        return decodificar(self.body)


def decodificar(body: bytes) -> Frame:
    if len(body) < 4:
        raise ValueError("El cuerpo no contiene un frame válido.")
    largo = int.from_bytes(body[:4], "big")
    if largo < 0 or len(body) < 4 + largo:
        raise ValueError("La longitud del JSON del frame no es válida.")
    meta = json.loads(body[4:4 + largo].decode("utf-8"))
    return Frame(meta=meta, pcm=body[4 + largo:])


def tono_pcm(segundos: float = 1.2, frecuencia: int = 440, rate: int = AUDIO_RATE) -> bytes:
    muestras = int(segundos * rate)
    return b"".join(
        struct.pack("<h", int(0.15 * 32767 * math.sin(2 * math.pi * frecuencia * i / rate)))
        for i in range(muestras)
    )


def guardar_wav(ruta: Path, pcm: bytes, rate: int = AUDIO_RATE) -> None:
    ruta.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(ruta), "wb") as archivo:
        archivo.setnchannels(AUDIO_CHANNELS)
        archivo.setsampwidth(2)
        archivo.setframerate(rate)
        archivo.writeframes(pcm)


class ClienteDispositivo:
    def __init__(self, base: str, token: str, device_id: str, alias: str, usar_mock: bool):
        self.base = base.rstrip("/")
        self.token = token
        self.device_id = device_id
        self.alias = alias
        self.usar_mock = usar_mock

    def _request(self, metodo: str, ruta: str, body: bytes | None = None,
                 headers: dict[str, str] | None = None) -> Respuesta:
        cabeceras = {
            "X-Device-Token": self.token,
            "X-Device-Id": self.device_id,
        }
        if headers:
            cabeceras.update(headers)
        req = urllib.request.Request(self.base + ruta, data=body, method=metodo, headers=cabeceras)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return Respuesta(response.status, dict(response.headers), response.read())
        except urllib.error.HTTPError as error:
            return Respuesta(error.code, dict(error.headers), error.read())

    def contrato(self) -> dict:
        respuesta = self._request("GET", "/api/v1/device/contrato")
        if respuesta.status != 200:
            raise RuntimeError(f"No se pudo leer el contrato: HTTP {respuesta.status}")
        return json.loads(respuesta.body)

    def crear(self) -> Frame:
        body = json.dumps({"deviceId": self.device_id, "alias": self.alias}).encode("utf-8")
        respuesta = self._request(
            "POST", "/api/v1/device/sesiones", body,
            {"Content-Type": "application/json"},
        )
        if respuesta.status != 200:
            raise RuntimeError(f"Error al crear la sesión: HTTP {respuesta.status} {respuesta.body!r}")
        validar_cabeceras(respuesta)
        return respuesta.frame

    def turno(self, sesion_id: str, pcm: bytes, turn_id: str | None = None,
              mock_resultado: str | None = None) -> tuple[str, Respuesta]:
        turn_id = turn_id or str(uuid.uuid4())
        headers = {
            "Content-Type": f"audio/L16;rate={AUDIO_RATE};channels={AUDIO_CHANNELS}",
            "X-Turno": turn_id,
        }
        if self.usar_mock and mock_resultado:
            headers["X-Mock-Resultado"] = mock_resultado
            headers["X-Mock-Transcripcion"] = f"Transcripción simulada {turn_id[:8]}"
        respuesta = self._request(
            "POST", f"/api/v1/device/sesiones/{sesion_id}/turno", pcm, headers)
        if respuesta.status == 200:
            validar_cabeceras(respuesta)
        return turn_id, respuesta

    def audio_actual(self, sesion_id: str) -> Respuesta:
        return self._request("GET", f"/api/v1/device/sesiones/{sesion_id}/audio-actual")

    def cerrar(self, sesion_id: str) -> Respuesta:
        return self._request("POST", f"/api/v1/device/sesiones/{sesion_id}/cerrar")


def validar_cabeceras(respuesta: Respuesta) -> None:
    requeridas = [
        "X-Contrato-Version", "X-Audio-Format", "X-Audio-Rate",
        "X-Estado", "X-Resultado", "X-Sesion-Id",
    ]
    faltantes = [nombre for nombre in requeridas if nombre not in respuesta.headers]
    if faltantes:
        raise AssertionError(f"Faltan cabeceras de respuesta: {faltantes}")
    if respuesta.headers["X-Contrato-Version"] != str(CONTRATO_VERSION):
        raise AssertionError("La versión de contrato no coincide.")
    if respuesta.headers["X-Audio-Format"] != "PCM16LE":
        raise AssertionError("El formato de audio no es PCM16LE.")
    if int(respuesta.headers["X-Audio-Rate"]) != AUDIO_RATE:
        raise AssertionError("La tasa de audio no es 16000.")


def modo_contrato(cliente: ClienteDispositivo) -> None:
    contrato = cliente.contrato()
    assert contrato["contratoVersion"] == CONTRATO_VERSION
    assert contrato["framing"] == "[uint32 BE len(JSON)][JSON UTF-8][PCM16LE]"
    assert contrato["audio"]["rate"] == AUDIO_RATE
    assert contrato["audio"]["maxSegundos"] == MAX_SECONDS
    rutas = {endpoint["ruta"] for endpoint in contrato["endpoints"]}
    assert "/api/v1/device/sesiones/{id}/turno" in rutas
    print("CONTRATO OK")
    print(json.dumps(contrato, ensure_ascii=False, indent=2))


def modo_demo(cliente: ClienteDispositivo, salida: Path) -> str:
    frame = cliente.crear()
    sesion_id = frame.meta["sesionId"]
    print(f"Sesión {sesion_id} | estado={frame.meta['estado']} | "
          f"pregunta {frame.meta['preguntaN']}/{frame.meta['totalPreguntas']}")
    print(f"  {frame.meta['pregunta']}")
    guardar_wav(salida / "turno-00-bienvenida.wav", frame.pcm)

    actual = cliente.audio_actual(sesion_id)
    assert actual.status == 200 and len(actual.body) > 0, "audio-actual no devolvió audio"
    guardar_wav(salida / "turno-00-audio-actual.wav", actual.body)

    plan = ["CORRECTO", "INCORRECTO", "INCORRECTO", "CORRECTO", "CORRECTO", "CORRECTO"]
    meta = frame.meta
    for indice in range(1, 13):
        if meta.get("fin"):
            break
        forzado = plan[(indice - 1) % len(plan)]
        turn_id, respuesta = cliente.turno(sesion_id, tono_pcm(), mock_resultado=forzado)
        if respuesta.status != 200:
            raise RuntimeError(f"Turno {indice} falló: HTTP {respuesta.status} {respuesta.body!r}")
        meta = respuesta.frame.meta
        guardar_wav(salida / f"turno-{indice:02d}.wav", respuesta.frame.pcm)
        print(f"  turno {indice:02d}: forzado={forzado:12s} estado={meta['estado']:12s} "
              f"resultado={meta['resultado']:12s} pregunta={meta['preguntaN']} "
              f"intento={meta['intento']} fin={meta['fin']} "
              f"audio={len(respuesta.frame.pcm)}B turno={turn_id[:8]}")
        if meta.get("pista"):
            print(f"    pista: {meta['pista']}")
        if meta.get("respuestaCorrecta"):
            print(f"    respuesta correcta: {meta['respuestaCorrecta']}")

    cierre = cliente.cerrar(sesion_id)
    assert cierre.status == 200, f"No se pudo cerrar: HTTP {cierre.status}"
    guardar_wav(salida / "turno-99-cierre.wav", cierre.frame.pcm)
    print(f"DEMO OK | estado final={meta['estado']} | WAV en {salida}")
    return sesion_id


def modo_reintentos(cliente: ClienteDispositivo, salida: Path) -> None:
    frame = cliente.crear()
    sesion_id = frame.meta["sesionId"]
    turn_id = str(uuid.uuid4())
    _, primera = cliente.turno(sesion_id, tono_pcm(), turn_id, mock_resultado="CORRECTO")
    _, segunda = cliente.turno(sesion_id, tono_pcm(0.8, 500), turn_id, mock_resultado="INCORRECTO")
    assert primera.status == 200 and segunda.status == 200
    assert primera.frame.meta == segunda.frame.meta, "La idempotencia devolvió metadatos distintos"
    assert primera.frame.pcm == segunda.frame.pcm, "La idempotencia devolvió audio distinto"
    print("IDEMPOTENCIA OK: el mismo X-Turno devuelve la misma respuesta")

    _, vacio = cliente.turno(sesion_id, b"", mock_resultado="CORRECTO")
    assert vacio.status == 400, f"Audio vacío debía fallar con 400, fue {vacio.status}"
    assert json.loads(vacio.body)["codigo"] == "AUDIO_VACIO"
    print("AUDIO VACÍO OK: HTTP 400 AUDIO_VACIO")

    largo = tono_pcm(MAX_SECONDS + 1)
    _, demasiado = cliente.turno(sesion_id, largo, mock_resultado="CORRECTO")
    assert demasiado.status == 400, f"Audio largo debía fallar con 400, fue {demasiado.status}"
    assert json.loads(demasiado.body)["codigo"] == "AUDIO_DEMASIADO_LARGO"
    print("AUDIO LARGO OK: HTTP 400 AUDIO_DEMASIADO_LARGO")

    _, error_ia = cliente.turno(sesion_id, tono_pcm(), mock_resultado="ERROR")
    assert error_ia.status == 200
    assert error_ia.frame.meta["estado"] == "ERROR"
    assert error_ia.headers["X-Resultado"] == "ERROR"
    assert len(error_ia.frame.pcm) > 0
    guardar_wav(salida / "turno-error-ia.wav", error_ia.frame.pcm)
    print("ERROR RECUPERABLE OK: HTTP 200 + estado ERROR + audio para reintentar")

    assert cliente.cerrar(sesion_id).status == 200
    print("REINTENTOS OK")


def main() -> int:
    parser = argparse.ArgumentParser(description="Simulador ESP32 para MinIA Mauta")
    parser.add_argument("--base", default=os.environ.get("MINIA_BASE", "http://localhost:8080"))
    parser.add_argument("--token", default=os.environ.get("DEVICE_TOKEN", "dev-token"))
    parser.add_argument("--device", default=os.environ.get("MINIA_DEVICE", "sim-esp32-01"))
    parser.add_argument("--alias", default="Mateo")
    parser.add_argument("--modo", choices=["contrato", "demo", "reintentos", "todo"], default="demo")
    parser.add_argument("--real", action="store_true",
                        help="No enviar cabeceras X-Mock-* (requiere servidor con Gemini)")
    parser.add_argument("--nuevo", action="store_true",
                        help="Usar un deviceId único para empezar una sesión nueva")
    parser.add_argument("--salida", default="sim/out")
    args = parser.parse_args()

    device_id = args.device
    if args.nuevo:
        device_id = f"{device_id}-{uuid.uuid4().hex[:6]}"
    salida = Path(args.salida)

    cliente = ClienteDispositivo(args.base, args.token, device_id, args.alias, not args.real)
    print(f"Servidor: {args.base} | deviceId: {device_id} | mock={not args.real}")
    try:
        if args.modo in ("contrato", "todo"):
            modo_contrato(cliente)
        if args.modo in ("demo", "todo"):
            modo_demo(cliente, salida)
        if args.modo in ("reintentos", "todo"):
            modo_reintentos(cliente, salida)
    except urllib.error.URLError as error:
        print(f"No se pudo conectar con {args.base}: {error}", file=sys.stderr)
        return 2
    except AssertionError as error:
        print(f"FALLO DE CONTRATO: {error}", file=sys.stderr)
        return 1
    except RuntimeError as error:
        print(f"FALLO: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
