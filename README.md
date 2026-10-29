# MinIA Mauta

Robot de comprensión lectora por voz para niños de primaria. El niño lee un
texto, el robot le hace 5 preguntas, evalúa sus respuestas y le da una pista si
se equivoca. Al segundo error le muestra la respuesta correcta y continúa.

El proyecto incluye:

- **Servidor Spring Boot** que orquesta la sesión, evalúa con Gemini, sintetiza
  voz y guarda el historial en PostgreSQL.
- **Contrato binario HTTP** para el ESP32: audio PCM16LE de 16 kHz mono.
- **Simulador de ESP32** en Python para probar el contrato sin hardware.
- **Consola web React** para que el equipo docente vea sesiones, métricas y
  detalles.
- **Documentación** del contrato, la API y el guion de demo.

## Arquitectura

```
ESP32 (micrófono + parlante)
   │  HTTP/1.1 POST binario
   │  [u32 BE len(JSON)][JSON UTF-8][PCM16LE]
   ▼
Spring Boot 3.5 / Java 21
   ├──► Gemini 3.5 Flash (evaluación de la respuesta)
   ├──► Gemini 2.5 Flash TTS (voz del robot, con caché)
   └──► PostgreSQL 16 (textos, preguntas, sesiones, turnos)
             ▲
             │ JSON /api/v1/console/*
        React + Vite (consola interna)
```

En la versión final, el servidor corre en una Raspberry Pi 5. El ESP32 nunca
accede directamente a Gemini ni a la base de datos: solo conoce la URL base y el
token del dispositivo.

## Requisitos

- JDK 21
- Maven 3.9+ (o el Maven incluido en IntelliJ IDEA)
- Docker Desktop
- Node.js 20+ y npm
- Python 3.10+ (solo para el simulador, sin dependencias externas)

## Inicio rápido

### 1. Base de datos

```powershell
docker compose up -d
docker ps --filter name=minia-db
```

PostgreSQL queda en el puerto **5433** del host (el 5432 suele estar ocupado).
Las migraciones Flyway crean el esquema y cargan el texto “El zorro y el cuervo”
con 10 preguntas.

### 2. Servidor en modo mock

El modo mock no necesita API key y es determinista: ideal para demos y pruebas.

```powershell
cd server
$env:JAVA_HOME="C:\Users\ACER\.jdks\ms-21.0.12"
mvn spring-boot:run
```

Si `mvn` no está en el PATH:

```powershell
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.1\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" spring-boot:run
```

Verificar:

```powershell
curl.exe http://localhost:8080/actuator/health
curl.exe http://localhost:8080/api/v1/device/contrato
```

### 3. Simulador del ESP32

```powershell
python sim\esp32_sim.py --modo demo --nuevo
```

- `demo`: crea sesión, recorre 5 preguntas, guarda WAV en `sim/out/` y cierra.
- `contrato`: valida el contrato en vivo.
- `reintentos`: prueba idempotencia, audio vacío/largo y error recuperable.
- `todo`: ejecuta todo.

Opciones útiles:

```powershell
python sim\esp32_sim.py --modo todo --nuevo --base http://192.168.1.50:8080
python sim\esp32_sim.py --modo demo --real   # sin cabeceras X-Mock-*
```

> El simulador envía tonos, no voz. En modo Gemini esos tonos se evalúan como
> `SIN_CONFIANZA`; para probar respuestas correctas usar el micrófono del ESP32
> o un WAV con voz.

### 4. Consola web

```powershell
cd web
npm install
npm run dev
```

Abrir `http://localhost:5173`. Vite redirige `/api` al servidor en el puerto
8080.

## Modo Gemini

Para usar evaluación y voz reales:

```powershell
$env:GEMINI_API_KEY="tu-api-key"
$env:MINIA_AI_MODE="gemini"
$env:MINIA_TTS_MODE="gemini"
cd server
mvn spring-boot:run
```

Variables principales:

| Variable | Default | Descripción |
|---|---|---|
| `MINIA_AI_MODE` | `mock` | `mock` o `gemini`. |
| `MINIA_TTS_MODE` | `mock` | `mock` o `gemini`. |
| `GEMINI_API_KEY` | vacío | Obligatoria en modo Gemini. |
| `GEMINI_MODEL` | `gemini-3.5-flash` | Modelo de evaluación. |
| `GEMINI_TTS_MODEL` | `gemini-2.5-flash-preview-tts` | Modelo de voz. |
| `GEMINI_TTS_VOICE` | `Kore` | Voz de Gemini TTS. |
| `DEVICE_TOKEN` | `dev-token` | Token compartido con el ESP32. |
| `DB_URL` | `jdbc:postgresql://localhost:5433/minia` | PostgreSQL. |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origen de la consola. |

Copiar `.env.example` a `.env` como referencia. El servidor lee variables de
entorno, no el archivo `.env` automáticamente.

### Modo híbrido recomendado

El plan gratuito de Gemini limita mucho el TTS (≈3 solicitudes por minuto). Para
una demo estable se recomienda evaluar con Gemini y usar la voz local del mock:

```powershell
$env:GEMINI_API_KEY="tu-api-key"
$env:MINIA_AI_MODE="gemini"
$env:MINIA_TTS_MODE="mock"
```

Modelos verificados con una cuenta free:

- `gemini-3.5-flash`: evaluación con audio, funciona.
- `gemini-2.5-flash`: ya no está disponible para cuentas nuevas (`404`).
- `gemini-2.5-flash-preview-tts`: genera voz, pero puede responder `429` por cuota.
- `gemini-3.8-flash-tts`: también genera voz, con la misma limitación de cuota.

> **Privacidad:** en el plan gratuito de Gemini no se deben enviar voces reales
> de niños. Usar tonos sintéticos (el simulador ya lo hace) o una voz adulta con
> consentimiento. El servidor guarda transcripciones, no el audio original.

## Endpoints

### Dispositivo

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/v1/device/contrato` | Contrato del protocolo. |
| POST | `/api/v1/device/sesiones` | Crear o reanudar sesión. |
| POST | `/api/v1/device/sesiones/{id}/turno` | Enviar PCM16LE. |
| GET | `/api/v1/device/sesiones/{id}/audio-actual` | Repetir pregunta actual. |
| POST | `/api/v1/device/sesiones/{id}/cerrar` | Finalizar sesión. |

Cabeceras obligatorias: `X-Device-Token`, `X-Device-Id`. Para idempotencia:
`X-Turno` (UUID).

### Consola

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/v1/console/panel` | Panel completo. |
| GET | `/api/v1/console/sesiones` | Últimas sesiones. |
| GET | `/api/v1/console/sesiones/{id}` | Detalle e historial. |
| GET | `/api/v1/console/textos` | Textos y líneas. |
| GET | `/api/v1/console/diagnostico` | Motores y configuración. |
| POST | `/api/v1/console/reset-demo` | Borrar sesiones/turnos. |

Documentación detallada:

- [`docs/contrato-esp32.md`](docs/contrato-esp32.md)
- [`docs/api.md`](docs/api.md)
- [`docs/guion-demo.md`](docs/guion-demo.md)

## Reglas del flujo

1. Se seleccionan 5 preguntas al azar de las 10 del texto activo.
2. Respuesta correcta → avanza a la siguiente pregunta.
3. Primer error → pista + repetición de la pregunta.
4. Segundo error → se revela la respuesta correcta y avanza.
5. Respuesta inaudible → se repite la pregunta sin penalizar.
6. Fallo de IA → HTTP 200 con `estado=ERROR` y audio para reintentar.
7. Al terminar las 5 preguntas, la sesión queda `FINALIZADA`.

## Estructura

```
proyecto_dev/
├── docker-compose.yml          PostgreSQL 16 en 5433
├── .env.example
├── server/
│   ├── pom.xml
│   └── src/main/
│       ├── java/pe/utec/minia/
│       │   ├── ai/             Gemini + mock de evaluación
│       │   ├── api/            Controladores, DTOs, errores, framing
│       │   ├── audio/          PCM, WAV, resampling
│       │   ├── config/         Propiedades, beans, CORS
│       │   ├── domain/         Entidades y enums
│       │   ├── repository/     Spring Data JPA
│       │   ├── service/        Sesión, contenido, audio, consola
│       │   └── tts/            Gemini TTS + mock + caché
│       └── resources/
│           ├── application.yml
│           └── db/migration/   Flyway: esquema y seed
├── sim/
│   └── esp32_sim.py            Simulador del dispositivo
├── web/
│   ├── package.json
│   └── src/                    Consola React
└── docs/
    ├── api.md
    ├── contrato-esp32.md
    └── guion-demo.md
```

## Pruebas

```powershell
cd server
mvn test
```

El simulador también funciona como prueba de contrato de extremo a extremo:

```powershell
python sim\esp32_sim.py --modo todo --nuevo
```

## Solución de problemas

| Problema | Solución |
|---|---|
| `Port 5432 already in use` | La base usa 5433; no cambiar el mapeo. |
| `Port 8080 already in use` | Cerrar el proceso anterior o definir `SERVER_PORT`. |
| `mvn` no reconocido | Usar la ruta de Maven de IntelliJ (ver arriba). |
| `GEMINI_SIN_API_KEY` | Configurar `GEMINI_API_KEY` o volver a modo mock. |
| El audio del simulador son tonos | Es esperado. En modo Gemini esos tonos dan `SIN_CONFIANZA`; usar micrófono o WAV con voz. |
| Gemini `404` con `gemini-2.5-flash` | Ese modelo ya no está disponible para cuentas nuevas; usar `gemini-3.5-flash`. |
| Gemini TTS `429` | Cuota free muy baja (≈3/min); usar `MINIA_TTS_MODE=mock` o esperar. |
| La consola no carga datos | Verificar que el servidor esté en 8080 y Vite en 5173. |
| Flyway falla por versión | `docker compose down -v` solo si se puede borrar la BD de demo. |

## Estado del MVP

- [x] Contrato binario HTTP v1.
- [x] Sesiones con 5 de 10 preguntas.
- [x] Pista al primer error y respuesta correcta al segundo.
- [x] Evaluación Gemini 3.5 Flash y TTS Gemini seleccionables por variable.
- [x] Caché de TTS en memoria y disco.
- [x] Persistencia PostgreSQL con Flyway.
- [x] Simulador de ESP32 y pruebas de contrato.
- [x] Consola React de sesiones y métricas.
- [ ] WebSocket (v2).
- [ ] Autenticación de la consola.
- [ ] Editor de textos/preguntas desde la consola.
- [ ] Despliegue en Raspberry Pi 5.

## Privacidad y Ley 29733

- El servidor guarda transcripciones y metadatos; no guarda el audio original.
- No enviar voces reales de menores a servicios gratuitos de terceros.
- Informar a padres y docentes sobre el tratamiento de datos.
- Permitir eliminar sesiones desde el panel interno (`reset-demo` en demo).
- No registrar tokens ni URLs sensibles en logs.

## Licencia

Este proyecto se distribuye bajo la **PolyForm Noncommercial License 1.0.0**.
Permite uso personal, educativo e investigación; prohíbe el uso comercial.
Ver [LICENSE](LICENSE).
