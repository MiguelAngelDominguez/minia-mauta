# API del servidor MinIA Mauta

Base local: `http://localhost:8080`  
Swagger UI: `http://localhost:8080/swagger-ui.html`  
OpenAPI JSON: `http://localhost:8080/v3/api-docs`

El contrato del dispositivo tiene su propio documento detallado:
[`contrato-esp32.md`](contrato-esp32.md).

## 1. Resumen de endpoints

### Dispositivo (ESP32)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/v1/device/contrato` | Contrato vivo en JSON. |
| POST | `/api/v1/device/sesiones` | Crea o reanuda una sesión. |
| POST | `/api/v1/device/sesiones/{id}/turno` | Envía PCM16LE y recibe frame. |
| GET | `/api/v1/device/sesiones/{id}/audio-actual` | PCM16LE de la pregunta actual. |
| POST | `/api/v1/device/sesiones/{id}/cerrar` | Finaliza la sesión. |

Todas las rutas de sesión requieren `X-Device-Token` y `X-Device-Id`.

### Consola interna (React)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/v1/console/panel` | Resumen + sesiones + textos + diagnóstico. |
| GET | `/api/v1/console/sesiones` | Últimas 50 sesiones. |
| GET | `/api/v1/console/sesiones/{id}` | Detalle de una sesión. |
| GET | `/api/v1/console/textos` | Textos activos con sus líneas. |
| GET | `/api/v1/console/diagnostico` | Motores de IA/TTS y configuración de audio. |
| POST | `/api/v1/console/reset-demo` | Borra sesiones y turnos (mantiene textos). |

La consola no implementa autenticación en el MVP. Debe publicarse solo en la red
interna o detrás de un proxy con autenticación.

## 2. Consola

### 2.1 `GET /api/v1/console/panel`

```json
{
  "resumen": {
    "sesiones": 3,
    "activas": 0,
    "finalizadas": 3,
    "turnos": 14,
    "aciertos": 9,
    "errores": 4,
    "latenciaPromedioMs": 5.4
  },
  "sesiones": [
    {
      "id": "2d5b37af-7834-42df-8ad0-d8b1269b4058",
      "deviceId": "sim-esp32-01",
      "alias": "Mateo",
      "textoId": "11111111-1111-1111-1111-111111111111",
      "titulo": "El zorro y el cuervo",
      "estado": "FINALIZADA",
      "preguntaActual": 5,
      "totalPreguntas": 5,
      "aciertos": 4,
      "errores": 1,
      "turnos": 6,
      "iniciadaEn": "2026-10-23T00:04:08.255677Z",
      "finalizadaEn": "2026-10-23T00:04:08.736774Z"
    }
  ],
  "textos": [
    {
      "id": "11111111-1111-1111-1111-111111111111",
      "titulo": "El zorro y el cuervo",
      "grado": "Primaria",
      "lineas": ["Un cuervo encontró un trozo de queso y voló hasta una rama."],
      "activo": true,
      "totalPreguntas": 10
    }
  ],
  "diagnostico": {
    "motorIa": "mock",
    "modoIa": "mock",
    "motorTts": "mock-tone-cache",
    "modoTts": "mock",
    "rate": 16000,
    "canales": 1,
    "maxSegundos": 30,
    "contratoVersion": 1
  }
}
```

Notas:

- `resumen.errores` cuenta **turnos** con resultado `INCORRECTO` (incluye el
  primer intento fallido que genera pista).
- `sesiones[].errores` cuenta **preguntas** que se fallaron tras dos intentos.
- `resumen.aciertos` cuenta turnos con resultado `CORRECTO`.
- `latenciaPromedioMs` mide solo la evaluación (no incluye TTS ni red).

### 2.2 `GET /api/v1/console/sesiones/{id}`

```json
{
  "id": "2d5b37af-7834-42df-8ad0-d8b1269b4058",
  "deviceId": "sim-esp32-01",
  "alias": "Mateo",
  "textoId": "11111111-1111-1111-1111-111111111111",
  "titulo": "El zorro y el cuervo",
  "grado": "Primaria",
  "lineas": [
    "Un cuervo encontró un trozo de queso y voló hasta una rama.",
    "Un zorro que pasaba por allí lo vio y quiso quitarle el queso."
  ],
  "estado": "FINALIZADA",
  "preguntaActual": 5,
  "totalPreguntas": 5,
  "aciertos": 4,
  "errores": 1,
  "intentosActual": 0,
  "turnos": 6,
  "iniciadaEn": "2026-10-23T00:04:08.255677Z",
  "finalizadaEn": "2026-10-23T00:04:08.736774Z",
  "preguntaActualDetalle": {
    "id": "22222222-2222-2222-2222-222222222205",
    "orden": 5,
    "enunciado": "¿Qué le dijo el zorro al cuervo?",
    "respuestaEsperada": "Que tenía plumas muy brillantes.",
    "palabrasClave": ["plumas", "brillantes"],
    "lineaPista": 4,
    "seccionPista": "Cuarta línea",
    "dificultad": "FACIL"
  },
  "preguntas": [
    {
      "id": "22222222-2222-2222-2222-222222222205",
      "orden": 5,
      "enunciado": "¿Qué le dijo el zorro al cuervo?",
      "respuestaEsperada": "Que tenía plumas muy brillantes.",
      "palabrasClave": ["plumas", "brillantes"],
      "lineaPista": 4,
      "seccionPista": "Cuarta línea",
      "dificultad": "FACIL"
    }
  ],
  "historial": [
    {
      "id": "f1e2d3c4-b5a6-4978-8a9b-0c1d2e3f4a5b",
      "secuencia": 2,
      "intento": 1,
      "preguntaId": "22222222-2222-2222-2222-222222222202",
      "resultado": "INCORRECTO",
      "confianza": 0.2,
      "transcripcion": "Transcripción simulada",
      "pista": "Busca la respuesta en la línea 2 del texto.",
      "feedback": "Casi. Busca la respuesta en la línea 2 del texto. Inténtalo otra vez.",
      "latenciaMs": 3,
      "audioMs": 1850,
      "creadoEn": "2026-10-23T00:04:08.400000Z"
    }
  ]
}
```

`preguntas` contiene solo las 5 preguntas seleccionadas para esa sesión, en el
orden en que se presentarán. `preguntaActualDetalle` es la pregunta en curso
(o la última si ya finalizó).

### 2.3 `POST /api/v1/console/reset-demo`

```json
{
  "ok": true,
  "mensaje": "Se eliminaron las sesiones y turnos. Los textos y preguntas se mantienen."
}
```

## 3. Ejemplos con curl

Windows PowerShell:

```powershell
# Contrato
curl.exe http://localhost:8080/api/v1/device/contrato

# Crear sesión
curl.exe -X POST http://localhost:8080/api/v1/device/sesiones `
  -H "Content-Type: application/json" `
  -H "X-Device-Token: dev-token" `
  -H "X-Device-Id: esp32-aula-01" `
  -d '{\"deviceId\":\"esp32-aula-01\",\"alias\":\"Mateo\"}' `
  -o frame.bin

# Panel de la consola
curl.exe http://localhost:8080/api/v1/console/panel
```

El frame binario no se puede leer cómodamente con `curl`; usar el simulador:

```powershell
python sim\esp32_sim.py --modo demo --nuevo
```

## 4. Códigos de error HTTP

| HTTP | Cuándo |
|---|---|
| 200 | Turno procesado, incluso si `X-Resultado=ERROR` (recuperable). |
| 400 | JSON inválido, audio vacío/corto/largo, sesión finalizada. |
| 401 | Token inválido o `deviceId` que no es dueño de la sesión. |
| 404 | Sesión o texto no encontrado. |
| 500 | Error interno no controlado. |
| 503 | Gemini no configurado o no disponible al crear audio fuera de turno. |

Todos los errores no binarios tienen la forma:

```json
{
  "codigo": "AUDIO_VACIO",
  "mensaje": "No se recibió audio."
}
```

## 5. Configuración relevante

| Variable | Default | Descripción |
|---|---|---|
| `MINIA_AI_MODE` | `mock` | `mock` o `gemini`. |
| `MINIA_TTS_MODE` | `mock` | `mock` o `gemini`. |
| `GEMINI_API_KEY` | vacío | Obligatoria en modo Gemini. |
| `GEMINI_MODEL` | `gemini-3.5-flash` | Modelo de evaluación. |
| `GEMINI_TTS_MODEL` | `gemini-2.5-flash-preview-tts` | Modelo de voz. |
| `GEMINI_TTS_VOICE` | `Kore` | Voz de Gemini TTS. |
| `DEVICE_TOKEN` | `dev-token` | Token compartido con el ESP32. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origen de la consola. |
| `DB_URL` | `jdbc:postgresql://localhost:5433/minia` | PostgreSQL. |

Ver `.env.example`.

> En cuentas free, `gemini-2.5-flash` ya no está disponible para evaluación
> (`404`); usar `gemini-3.5-flash`. El TTS free tiene una cuota muy baja
> (≈3 solicitudes/minuto) y puede responder `429`, por lo que se recomienda
> `MINIA_TTS_MODE=mock` para demos estables.
