# Contrato ESP32 ↔ Servidor MinIA Mauta

Versión: **1**  
Transporte: **HTTP/1.1**  
Formato de audio: **PCM16LE, 16 kHz, mono**  
Fecha: octubre 2026

Este documento es la referencia para el firmware del ESP32 y para el simulador
`sim/esp32_sim.py`. El servidor expone el mismo contrato en vivo en
`GET /api/v1/device/contrato`.

## 1. Principios

- El ESP32 captura voz, la envía al servidor y reproduce el audio que recibe.
- El servidor orquesta la sesión, evalúa con Gemini y devuelve audio TTS.
- El audio viaja como **PCM16LE crudo**; el WAV es solo un contenedor de
  depuración para navegador, simulador y Gemini.
- La conversación es **half-duplex**: el niño habla, el servidor responde, el
  niño vuelve a hablar. El firmware no debe grabar mientras reproduce.
- Los errores recuperables de un turno devuelven **HTTP 200** con
  `X-Estado: ERROR` y audio para reintentar. Así el dispositivo no pierde la
  sesión por un fallo temporal de IA o de red.

## 2. Audio

| Propiedad | Valor |
|---|---|
| Formato | PCM16 little-endian con signo |
| Frecuencia | 16 000 Hz |
| Canales | 1 (mono) |
| Bytes por muestra | 2 |
| Bytes por segundo | 32 000 |
| Duración máxima por turno | 30 s |
| Tamaño máximo por turno | 960 000 bytes |
| Duración mínima aceptada | 300 ms |

El cuerpo del `POST /turno` es PCM puro. No enviar cabecera WAV ni metadatos.

```
Content-Type: audio/L16;rate=16000;channels=1
```

El servidor responde siempre con PCM16LE del mismo formato. Para escucharlo en
una PC, envolverlo en WAV (el simulador lo hace automáticamente).

## 3. Framing de respuesta

Toda respuesta de audio usa este frame binario:

```
+----------------+---------------------+------------------+
| u32 BE         | JSON UTF-8          | PCM16LE          |
| len(JSON)      | len(JSON) bytes     | resto del cuerpo |
+----------------+---------------------+------------------+
```

- `len(JSON)` es un entero de 32 bits **big-endian**.
- El JSON está codificado en UTF-8 y mide como máximo 64 KiB.
- El PCM ocupa exactamente `Content-Length - 4 - len(JSON)` bytes.
- El PCM puede ser vacío solo en casos excepcionales; el firmware debe tolerar
  `len(PCM) == 0` sin bloquearse.

Ejemplo de parseo en el ESP32:

```cpp
uint32_t jsonLen = ((uint32_t)buf[0] << 24) | ((uint32_t)buf[1] << 16)
                 | ((uint32_t)buf[2] << 8)  |  (uint32_t)buf[3];
const uint8_t* json = buf + 4;
const uint8_t* pcm  = buf + 4 + jsonLen;
size_t pcmLen       = contentLength - 4 - jsonLen;
```

## 4. Autenticación e identificación

| Cabecera | Obligatoria | Descripción |
|---|---|---|
| `X-Device-Token` | Sí | Token compartido configurado en `DEVICE_TOKEN`. |
| `X-Device-Id` | Sí | Identificador estable del dispositivo (MAC, serie o UUID). |
| `X-Turno` | Recomendada | UUID del turno para idempotencia. |

El token no se envía por la URL. En producción usar HTTPS y guardar el token en
NVS/almacenamiento seguro. `X-Device-Id` permite reanudar una sesión activa si
el dispositivo se reinicia.

## 5. Endpoints

| Método | Ruta | Cuerpo | Respuesta |
|---|---|---|---|
| GET | `/api/v1/device/contrato` | — | JSON con este contrato resumido. |
| POST | `/api/v1/device/sesiones` | JSON `CrearSesionRequest` | Frame con saludo + primera pregunta. |
| POST | `/api/v1/device/sesiones/{id}/turno` | PCM16LE | Frame con evaluación + siguiente audio. |
| GET | `/api/v1/device/sesiones/{id}/audio-actual` | — | PCM16LE de la pregunta actual. |
| POST | `/api/v1/device/sesiones/{id}/cerrar` | — | Frame con audio de cierre. |

### 5.1 Crear o reanudar sesión

```http
POST /api/v1/device/sesiones
Content-Type: application/json
X-Device-Token: dev-token
X-Device-Id: esp32-aula-01
```

```json
{
  "deviceId": "esp32-aula-01",
  "alias": "Mateo",
  "textoId": null
}
```

- Si el dispositivo ya tiene una sesión activa (`finalizada_en IS NULL`), el
  servidor la reanuda y devuelve la pregunta actual.
- Si no, crea una sesión nueva, selecciona 5 preguntas al azar del texto activo
  y devuelve saludo + primera pregunta.
- `alias` y `textoId` son opcionales. `textoId` solo se usa al crear.

### 5.2 Enviar un turno

```http
POST /api/v1/device/sesiones/{sesionId}/turno
Content-Type: audio/L16;rate=16000;channels=1
X-Device-Token: dev-token
X-Device-Id: esp32-aula-01
X-Turno: 5f0f2b1e-6a0e-4e2b-9f0e-3c1d2a3b4c5d

<PCM16LE bytes>
```

La respuesta es un frame binario. Cabeceras:

| Cabecera | Ejemplo | Descripción |
|---|---|---|
| `X-Contrato-Version` | `1` | Versión del contrato. |
| `X-Audio-Format` | `PCM16LE` | Formato del audio de respuesta. |
| `X-Audio-Rate` | `16000` | Frecuencia del audio. |
| `X-Estado` | `PREGUNTANDO` | Estado de la sesión después del turno. |
| `X-Resultado` | `CORRECTO` | Resultado del turno. |
| `X-Sesion-Id` | UUID | Sesión a la que pertenece. |
| `X-Turno` | UUID | Turno procesado. |

### 5.3 Reintentar audio

```http
GET /api/v1/device/sesiones/{sesionId}/audio-actual
X-Device-Token: dev-token
X-Device-Id: esp32-aula-01
```

Devuelve PCM16LE crudo con `Content-Type: audio/L16;rate=16000;channels=1`.
Útil si el parlante se cortó o el dispositivo se reinició.

### 5.4 Cerrar sesión

```http
POST /api/v1/device/sesiones/{sesionId}/cerrar
X-Device-Token: dev-token
X-Device-Id: esp32-aula-01
```

Finaliza la sesión y devuelve un frame con el audio de cierre. Es idempotente:
si ya estaba finalizada, responde igual.

## 6. Metadatos del frame

Ejemplo de JSON dentro del frame:

```json
{
  "sesionId": "0e2f5a6b-7c8d-4e9f-a0b1-c2d3e4f5a6b7",
  "estado": "PISTA",
  "resultado": "INCORRECTO",
  "preguntaN": 2,
  "totalPreguntas": 5,
  "intento": 2,
  "pista": "Busca la respuesta en la línea 2 del texto.",
  "transcripcion": "estaba en el árbol",
  "feedback": "Casi. Busca la respuesta en la línea 2 del texto. Inténtalo otra vez.",
  "fin": false,
  "textoId": "11111111-1111-1111-1111-111111111111",
  "titulo": "El zorro y el cuervo",
  "pregunta": "¿Dónde estaba el cuervo cuando lo vio el zorro?",
  "lineaPista": 2,
  "respuestaCorrecta": null,
  "codigoError": null
}
```

| Campo | Tipo | Descripción |
|---|---|---|
| `sesionId` | string UUID | Identificador de la sesión. |
| `estado` | string | Estado después del turno. |
| `resultado` | string | Resultado del turno. |
| `preguntaN` | int | Pregunta actual (1..5) o la última al finalizar. |
| `totalPreguntas` | int | Siempre 5 en el MVP. |
| `intento` | int | Próximo intento de la pregunta actual (1 o 2). |
| `pista` | string? | Pista cuando el estado es `PISTA`. |
| `transcripcion` | string? | Transcripción de la respuesta del niño. |
| `feedback` | string? | Texto de retroalimentación ya sintetizado en el audio. |
| `fin` | boolean | `true` cuando la sesión terminó. |
| `textoId` | string UUID | Texto de la sesión. |
| `titulo` | string | Título del texto. |
| `pregunta` | string | Enunciado de la pregunta actual (o siguiente). |
| `lineaPista` | int | Línea del texto que contiene la respuesta. |
| `respuestaCorrecta` | string? | Se completa cuando se revela la respuesta. |
| `codigoError` | string? | Código cuando el turno no se pudo evaluar. |

## 7. Estados y resultados

Estados de sesión:

```
CREADA → PREGUNTANDO → (PISTA | SIN_CONFIANZA | ERROR) → PREGUNTANDO
                     └→ FINALIZADA
```

| Estado | Significado |
|---|---|
| `CREADA` | Sesión recién creada (transitorio). |
| `PREGUNTANDO` | El niño debe responder la pregunta actual. |
| `EVALUANDO` | Reservado para el flujo interno. |
| `PISTA` | Primer error: se dio una pista y se repitió la pregunta. |
| `RESPUESTA` | Reservado para el flujo interno. |
| `SIN_CONFIANZA` | No se entendió la respuesta; se repite la pregunta. |
| `FINALIZADA` | Terminaron las 5 preguntas o se cerró la sesión. |
| `ERROR` | Fallo recuperable de IA/TTS; se puede reintentar. |

Resultados de turno: `CORRECTO`, `INCORRECTO`, `SIN_CONFIANZA`, `NINGUNO`,
`ERROR`.

Regla pedagógica:

1. Primer `INCORRECTO` → estado `PISTA`, audio con feedback + pista + pregunta.
2. Segundo `INCORRECTO` → se revela la respuesta correcta y se avanza.
3. `CORRECTO` → se avanza a la siguiente pregunta.
4. `SIN_CONFIANZA` → se repite la pregunta sin penalizar.
5. `ERROR` → HTTP 200, `estado=ERROR`, audio para reintentar.

## 8. Errores

### 8.1 Recuperables durante un turno (HTTP 200)

| `codigoError` | Causa | Acción del firmware |
|---|---|---|
| `IA_NO_DISPONIBLE` | Gemini no respondió o devolvió error. | Reproducir audio y reintentar el turno. |
| `RESULTADO_DESCONOCIDO` | Respuesta inesperada del motor. | Reintentar. |

También son recuperables, pero devuelven HTTP 400 con JSON porque el audio no
llegó a evaluarse:

| Código | HTTP | Causa |
|---|---|---|
| `AUDIO_VACIO` | 400 | Cuerpo vacío. |
| `AUDIO_INVALIDO` | 400 | Número impar de bytes. |
| `AUDIO_MUY_CORTO` | 400 | Menos de 300 ms. |
| `AUDIO_DEMASIADO_LARGO` | 400 | Más de 30 s / 960 KB. |

El firmware debe tratar estos 400 como “repetir la grabación”, no como cierre
de sesión.

### 8.2 No recuperables

| Código | HTTP | Acción |
|---|---|---|
| `TOKEN_INVALIDO` | 401 | Revisar `DEVICE_TOKEN` en NVS. |
| `DEVICE_ID_REQUERIDO` | 400 | Enviar `X-Device-Id`. |
| `DEVICE_NO_COINCIDE` | 401 | El `deviceId` no es dueño de la sesión. |
| `SESION_NO_ENCONTRADA` | 404 | Crear una sesión nueva. |
| `SESION_FINALIZADA` | 400 | Crear una sesión nueva. |
| `BANCO_INSUFICIENTE` | 400 | El texto no tiene 5 preguntas activas. |

Formato JSON de error:

```json
{
  "codigo": "TOKEN_INVALIDO",
  "mensaje": "El token del dispositivo no es válido."
}
```

## 9. Idempotencia y reintentos

- Enviar siempre un `X-Turno` UUID por grabación.
- El servidor guarda la respuesta por 10 minutos y 500 turnos.
- Si se repite el mismo `X-Turno`, devuelve exactamente el mismo frame sin
  volver a llamar a Gemini.
- Si no se envía `X-Turno`, el servidor genera uno nuevo y no puede deduplicar.
- Ante timeout de red, reintentar con el mismo `X-Turno` y el mismo audio.
- Ante HTTP 200 con `estado=ERROR`, reintentar con un `X-Turno` nuevo.

## 10. Notas para el firmware

- Reservar al menos 2 KiB para el JSON y 960 000 bytes para el PCM de salida, o
  transmitir el PCM por streaming a I2S.
- Leer `Content-Length` antes de reservar memoria.
- Validar `X-Contrato-Version == "1"` al iniciar; si no coincide, mostrar error
  y no continuar.
- Hacer half-duplex real: detener el micrófono (o descartar muestras) mientras
  se reproduce el audio.
- Usar un buffer circular para el micrófono y enviar en bloques de 20–100 ms.
- Configurar timeout HTTP de al menos 20 s: Gemini puede tardar varios segundos.
- Watchdog: si el servidor no responde, reintentar con backoff y luego cerrar.
- No guardar audio del niño en la flash. Solo se envía al servidor.
- Para GARAGE, el ESP32 solo necesita la URL base y el token; no accede a la
  base de datos ni a Gemini directamente.

## 11. Privacidad

- El servidor guarda **transcripciones**, no el audio original.
- No enviar voces reales de niños a Gemini en el plan gratuito durante pruebas.
  Usar tonos sintéticos o voces de adultos con consentimiento.
- El token y la URL no deben aparecer en logs públicos.
- Aplicar la Ley 29733: informar a padres/docentes, minimizar datos y permitir
  eliminar sesiones desde el panel interno.
