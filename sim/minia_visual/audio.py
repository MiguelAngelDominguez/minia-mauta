"""Micrófono, parlante y síntesis de voz local (SAPI) del simulador."""

from __future__ import annotations

import math
import tempfile
import threading
import time
import wave
from pathlib import Path

import numpy as np

RATE = 16000
MAX_SECONDS = 30
MIN_BYTES = int(RATE * 2 * 0.30)


def _resample_pcm16(pcm: bytes, origen: int, destino: int = RATE) -> bytes:
    if origen == destino or not pcm:
        return pcm
    datos = np.frombuffer(pcm, dtype="<i2").astype(np.float32)
    if datos.size == 0:
        return b""
    cantidad = max(1, int(round(datos.size * destino / origen)))
    x_viejo = np.linspace(0.0, 1.0, datos.size, endpoint=False)
    x_nuevo = np.linspace(0.0, 1.0, cantidad, endpoint=False)
    salida = np.interp(x_nuevo, x_viejo, datos)
    return np.clip(salida, -32768, 32767).astype("<i2").tobytes()


def _a_mono16(raw: bytes, canales: int, ancho: int) -> bytes:
    if ancho == 1:
        datos = (np.frombuffer(raw, dtype=np.uint8).astype(np.int16) - 128) * 256
    elif ancho == 2:
        datos = np.frombuffer(raw, dtype="<i2").astype(np.int16)
    elif ancho == 4:
        datos = (np.frombuffer(raw, dtype="<i4").astype(np.float32) / 65536).astype(np.int16)
    else:
        return b""
    if canales > 1:
        if datos.size % canales != 0:
            datos = datos[: datos.size - (datos.size % canales)]
        datos = datos.reshape(-1, canales).mean(axis=1).astype(np.int16)
    return datos.astype("<i2").tobytes()


class AudioEngine:
    """Captura y reproduce PCM16 mono a 16 kHz usando PyAudio."""

    def __init__(self, enabled: bool = True, mic_index: int | None = None):
        self.enabled = enabled
        self.mic_index = mic_index
        self.pa = None
        self.mic_available = False
        self.output_available = False
        self.mic_nombre = "sin micrófono"
        self.error = ""
        self.ultimo_error = ""
        self.auto_detuvo = False

        self._recording = threading.Event()
        self._lock = threading.Lock()
        self._frames: list[bytes] = []
        self._capture_thread: threading.Thread | None = None
        self._capture_stream = None
        self._capture_rate = RATE
        self._capture_channels = 1
        self._level = 0.0

        self._playing = False
        self._play_thread: threading.Thread | None = None
        self._play_started = 0.0
        self._play_duration = 0.0
        self._stop_play = threading.Event()
        self._play_level = 0.0

        self._iniciar_pyaudio()

    def _iniciar_pyaudio(self) -> None:
        if not self.enabled:
            self.error = "audio desactivado por --sin-audio"
            return
        try:
            import pyaudio
            self.pyaudio = pyaudio
            self.pa = pyaudio.PyAudio()
            self.output_available = self.pa.get_device_count() > 0
        except Exception as exc:
            self.error = f"PyAudio no disponible: {exc}"
            self.ultimo_error = self.error
            return

        try:
            info = self.pa.get_default_input_device_info()
            self.mic_nombre = str(info.get("name", "micrófono"))
            self.mic_available = int(info.get("maxInputChannels", 0)) > 0
        except Exception:
            self.mic_available = False
        if not self.mic_available:
            for indice in range(self.pa.get_device_count()):
                try:
                    info = self.pa.get_device_info_by_index(indice)
                    if int(info.get("maxInputChannels", 0)) > 0:
                        self.mic_available = True
                        self.mic_nombre = str(info.get("name", "micrófono"))
                        self.mic_index = indice
                        break
                except Exception:
                    continue

    @property
    def recording(self) -> bool:
        return self._recording.is_set()

    @property
    def playing(self) -> bool:
        return self._playing

    @property
    def level(self) -> float:
        with self._lock:
            return self._level

    @property
    def play_level(self) -> float:
        return self._play_level

    @property
    def play_duration(self) -> float:
        return self._play_duration

    @property
    def play_elapsed(self) -> float:
        if not self._playing:
            return 0.0
        return max(0.0, time.monotonic() - self._play_started)

    def _candidatos_entrada(self) -> list[int]:
        if not self.pa:
            return []
        candidatos: list[int] = []
        if self.mic_index is not None:
            candidatos.append(int(self.mic_index))
        try:
            candidatos.append(int(self.pa.get_default_input_device_info()["index"]))
        except Exception:
            pass
        for indice in range(self.pa.get_device_count()):
            try:
                if int(self.pa.get_device_info_by_index(indice).get("maxInputChannels", 0)) > 0:
                    candidatos.append(indice)
            except Exception:
                continue
        vistos: list[int] = []
        for indice in candidatos:
            if indice not in vistos:
                vistos.append(indice)
        return vistos

    def _abrir_entrada(self):
        if not self.pa:
            return None, RATE, 1
        for indice in self._candidatos_entrada():
            for canales in (1, 2):
                for rate in (RATE, 44100, 48000):
                    try:
                        stream = self.pa.open(
                            format=self.pyaudio.paInt16,
                            channels=canales,
                            rate=rate,
                            input=True,
                            input_device_index=indice,
                            frames_per_buffer=max(160, rate // 50),
                        )
                        self.mic_index = indice
                        self.mic_available = True
                        return stream, rate, canales
                    except Exception:
                        continue
        return None, RATE, 1

    def start_recording(self) -> bool:
        if not self.enabled:
            self.ultimo_error = "audio desactivado"
            return False
        if self._recording.is_set():
            return True
        stream, rate, canales = self._abrir_entrada()
        if stream is None:
            self.ultimo_error = "no se pudo abrir el micrófono"
            self.mic_available = False
            return False
        self._capture_stream = stream
        self._capture_rate = rate
        self._capture_channels = canales
        with self._lock:
            self._frames = []
            self._level = 0.0
        self.auto_detuvo = False
        self._recording.set()
        self._capture_thread = threading.Thread(
            target=self._capturar, args=(stream, rate, canales), daemon=True
        )
        self._capture_thread.start()
        return True

    def _capturar(self, stream, rate: int, canales: int) -> None:
        max_muestras = rate * MAX_SECONDS
        total = 0
        try:
            while self._recording.is_set():
                try:
                    data = stream.read(max(160, rate // 50), exception_on_overflow=False)
                except Exception as exc:
                    self.ultimo_error = f"lectura de micrófono: {exc}"
                    break
                if not data:
                    continue
                with self._lock:
                    self._frames.append(data)
                total += len(data) // (2 * canales)
                try:
                    muestras = np.frombuffer(data, dtype="<i2").astype(np.float32)
                    if muestras.size:
                        rms = float(np.sqrt(np.mean(muestras * muestras))) / 32768.0
                        with self._lock:
                            self._level = max(0.0, min(1.0, rms * 4.5))
                except Exception:
                    pass
                if total >= max_muestras:
                    self.auto_detuvo = True
                    self._recording.clear()
                    break
        finally:
            try:
                stream.stop_stream()
            except Exception:
                pass
            try:
                stream.close()
            except Exception:
                pass
            self._capture_stream = None
            with self._lock:
                self._level = 0.0

    def stop_recording(self) -> bytes:
        self._recording.clear()
        if self._capture_thread and self._capture_thread.is_alive():
            self._capture_thread.join(timeout=2.0)
        with self._lock:
            raw = b"".join(self._frames)
            self._frames = []
            self._level = 0.0
        if not raw:
            return b""
        pcm = _a_mono16(raw, self._capture_channels, 2)
        return _resample_pcm16(pcm, self._capture_rate, RATE)

    def cancel_recording(self) -> None:
        self._recording.clear()
        if self._capture_thread and self._capture_thread.is_alive():
            self._capture_thread.join(timeout=1.0)
        with self._lock:
            self._frames = []
            self._level = 0.0

    def _abrir_salida(self):
        if not self.pa:
            return None, RATE
        for rate in (RATE, 44100, 48000):
            try:
                stream = self.pa.open(
                    format=self.pyaudio.paInt16,
                    channels=1,
                    rate=rate,
                    output=True,
                    frames_per_buffer=max(160, rate // 50),
                )
                return stream, rate
            except Exception:
                continue
        return None, RATE

    def play(self, pcm: bytes, rate: int = RATE) -> bool:
        if not self.enabled or not pcm:
            self._playing = False
            return False
        self.stop_playback()
        self._stop_play.clear()
        self._play_started = time.monotonic()
        self._play_duration = len(pcm) / 2.0 / rate
        self._playing = True
        self._play_level = 0.0
        self._play_thread = threading.Thread(
            target=self._reproducir, args=(pcm, rate), daemon=True
        )
        self._play_thread.start()
        return True

    def _reproducir(self, pcm: bytes, rate: int) -> None:
        stream, salida_rate = self._abrir_salida()
        try:
            if stream is None:
                self.ultimo_error = "no se pudo abrir el altavoz"
                return
            datos = _resample_pcm16(pcm, rate, salida_rate)
            chunk = max(320, salida_rate // 50) * 2
            for inicio in range(0, len(datos), chunk):
                if self._stop_play.is_set():
                    break
                bloque = datos[inicio:inicio + chunk]
                stream.write(bloque, exception_on_underflow=False)
                try:
                    muestras = np.frombuffer(bloque, dtype="<i2").astype(np.float32)
                    if muestras.size:
                        rms = float(np.sqrt(np.mean(muestras * muestras))) / 32768.0
                        self._play_level = max(0.0, min(1.0, rms * 3.0))
                except Exception:
                    pass
        except Exception as exc:
            self.ultimo_error = f"reproducción: {exc}"
        finally:
            try:
                if stream is not None:
                    stream.stop_stream()
                    stream.close()
            except Exception:
                pass
            self._playing = False
            self._play_level = 0.0

    def stop_playback(self) -> None:
        self._stop_play.set()
        if self._play_thread and self._play_thread.is_alive():
            self._play_thread.join(timeout=1.5)
        self._playing = False
        self._play_level = 0.0

    def close(self) -> None:
        self.cancel_recording()
        self.stop_playback()
        if self.pa is not None:
            try:
                self.pa.terminate()
            except Exception:
                pass
            self.pa = None


class SapiSynth:
    """Convierte texto a PCM16 mono 16 kHz usando la voz de Windows."""

    def __init__(self):
        self.disponible = False
        self.error = ""
        self.voz = "voz predeterminada"
        try:
            import win32com.client  # noqa: F401
            self.disponible = True
        except Exception as exc:
            self.error = f"SAPI no disponible: {exc}"

    def sintetizar(self, texto: str) -> bytes:
        if not self.disponible:
            raise RuntimeError(self.error or "SAPI no disponible")
        import pythoncom
        import win32com.client

        pythoncom.CoInitialize()
        ruta = Path(tempfile.gettempdir()) / f"minia_visual_{time.time_ns()}.wav"
        try:
            stream = win32com.client.Dispatch("SAPI.SpFileStream")
            voz = win32com.client.Dispatch("SAPI.SpVoice")
            try:
                voces = voz.GetVoices()
                for indice in range(voces.Count):
                    descripcion = str(voces.Item(indice).GetDescription())
                    if "Sabina" in descripcion or "Spanish" in descripcion or "es-" in descripcion:
                        voz.Voice = voces.Item(indice)
                        self.voz = descripcion
                        break
            except Exception:
                pass
            stream.Open(str(ruta), 3, False)  # SSFMCreateForWrite
            try:
                stream.Format.Type = 14  # 16 kHz, 16 bits, mono
            except Exception:
                pass
            voz.AudioOutputStream = stream
            voz.Speak(texto, 0)
            try:
                voz.WaitUntilDone(20000)
            except Exception:
                pass
            stream.Close()
            with wave.open(str(ruta), "rb") as wav:
                canales = wav.getnchannels()
                ancho = wav.getsampwidth()
                rate = wav.getframerate()
                raw = wav.readframes(wav.getnframes())
            pcm = _a_mono16(raw, canales, ancho)
            return _resample_pcm16(pcm, rate, RATE)
        finally:
            try:
                ruta.unlink(missing_ok=True)
            except Exception:
                pass
            try:
                pythoncom.CoUninitialize()
            except Exception:
                pass
