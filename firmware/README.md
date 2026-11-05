# Firmware ESP32 (MinIA Mauta)

Firmware para NodeMCU DevKit (ESP32) con micrófono INMP441 y amplificador
MAX98357A. Implementa el contrato v1 ([`docs/contrato-esp32.md`](../docs/contrato-esp32.md)):
captura voz, la envía por HTTP al servidor y reproduce el audio PCM16LE que recibe.

## Requisitos

- Arduino IDE con el core **Arduino-ESP32 2.x o superior** (usa `esp_idf_version.h`
  y la API I2S legacy `driver/i2s.h`; en core 3.x compila con una advertencia de
  deprecación). Placa: *ESP32 Dev Module*.
- Sin librerías externas.

## Configuración

1. Copiar `minia_mauta/secrets.example.h` como `minia_mauta/secrets.h` y completar
   Wi-Fi (2.4 GHz), IP de la PC con el servidor y `DEVICE_TOKEN` (igual al del servidor).
2. La PC debe permitir conexiones entrantes al puerto 8080 (firewall de Windows) y
   estar en la misma red que el ESP32.
3. Abrir `minia_mauta/minia_mauta.ino`, compilar y cargar. Monitor serie a 115200.

## Pines (verificar contra el cableado real)

| Señal | GPIO |
|---|---|
| I2S BCLK (INMP441 SCK + MAX98357A BCLK) | 14 |
| I2S WS/LRC (compartido) | 15 (pin de arranque; si falla el boot, probar otro) |
| INMP441 SD (entrada) | 32 |
| MAX98357A DIN (salida) | 22 |
| LED verde / rojo | 18 / 19 |

El INMP441 debe tener `L/R` a GND (canal izquierdo). El firmware usa el canal
izquierdo también para la salida.

## Comportamiento

- Al arrancar: Wi-Fi (rojo parpadeando), crear/reanudar sesión y reproducir saludo + pregunta.
- Cada turno: graba, corta tras 1,2 s de silencio (mín. 400 ms, máx. 29 s, o 8 s
  sin voz), envía en chunked y reproduce la respuesta. LED verde = correcto,
  rojo = incorrecto/pista/error.
- Errores: `AUDIO_*` repite la grabación; 404/`SESION_FINALIZADA` crea sesión nueva;
  fallos de red hacen backoff y resincronizan con `audio-actual`;
  `TOKEN_INVALIDO` o contrato incompatible detienen el firmware con el LED rojo fijo.
- Al finalizar la sesión queda detenido; reiniciar la placa para otra sesión.

## Calibración de la VAD

El umbral `UMBRAL_RMS_VOZ` (450) es un valor inicial. Compilar con
`#define DEBUG_VAD 1` (antes de los `#include`, o editando el valor por defecto),
observar el RMS en silencio y hablando, y fijar el umbral entre ambos. Los
parámetros `TIEMPO_SILENCIO`, `DURACION_*` y `TIMEOUT_RESPUESTA_MS` también se
pueden ajustar.

## Pruebas

No hay pruebas automáticas del firmware: requiere la placa. Para probar sin Gemini,
usar el servidor en modo mock (`MINIA_AI_MODE=mock`, `MINIA_TTS_MODE=mock`): la
voz del robot serán tonos y los resultados alternan CORRECTO/INCORRECTO. Para
evaluar voz real usar `MINIA_AI_MODE=gemini` con `MINIA_TTS_MODE=mock` y voces de
adultos (ver privacidad en el README principal).
