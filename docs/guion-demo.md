# Guion de demo (5–7 minutos)

Objetivo: mostrar el flujo completo voz → evaluación → audio, la consola interna
y la integración con el ESP32/GARAGE sin depender de Internet ni de voces reales
de niños.

## Antes de la demo

1. Docker Desktop encendido.
2. Base de datos levantada:

   ```powershell
   docker compose up -d
   docker ps --filter name=minia-db
   ```

3. Servidor en modo mock:

   ```powershell
   $env:MINIA_AI_MODE="mock"
   $env:MINIA_TTS_MODE="mock"
   cd server
   mvn spring-boot:run
   ```

   Alternativa con el JAR:

   ```powershell
   java -jar target\minia-server-0.1.0.jar
   ```

4. Consola web:

   ```powershell
   cd web
   npm install
   npm run dev
   ```

5. Abrir `http://localhost:5173`.

## Guion

### 1. Contexto (30 s)

“MinIA Mauta es un robot de comprensión lectora. El niño lee un texto, el robot le
hace 5 preguntas, escucha su respuesta y le da una pista si se equivoca. Al
segundo error le muestra la respuesta correcta y continúa.”

Mostrar la arquitectura:

```
ESP32 (mic + parlante)
   │ HTTP binario / PCM16
   ▼
Spring Boot ──► Gemini (evaluación + TTS)
   │
   └──► PostgreSQL (textos, preguntas, sesiones, turnos)
             ▲
             │ JSON
        Consola React (docente / equipo)
```

### 2. Contrato en vivo (30 s)

```powershell
curl.exe http://localhost:8080/api/v1/device/contrato
```

Señalar:

- `[u32 BE len(JSON)][JSON UTF-8][PCM16LE]`.
- Audio 16 kHz mono, máximo 30 s.
- El ESP32 no habla con Gemini ni con la base de datos.

### 3. Simulador del ESP32 (60 s)

```powershell
python sim\esp32_sim.py --modo demo --nuevo
```

Mostrar en consola:

- Turno 1 correcto → avanza.
- Turno 2 incorrecto → `PISTA`, audio con pista y repetición.
- Turno 3 incorrecto → revela la respuesta correcta y avanza.
- Turno 6 → `FINALIZADA`.

Los WAV quedan en `sim/out/`. Reproducir `turno-02.wav` y `turno-03.wav` para
mostrar que la pista y el avance se escuchan.

### 4. Consola interna (60 s)

En `http://localhost:5173`:

- Métricas: sesiones, activas, turnos, aciertos, latencia.
- Lista de sesiones con estado y progreso.
- Detalle: texto con la línea de pista resaltada, pregunta actual, respuestas
  esperadas e historial de turnos con transcripción y feedback.
- Botón “Reiniciar demo” para limpiar sesiones y dejar los textos.

### 5. Resiliencia (45 s)

```powershell
python sim\esp32_sim.py --modo reintentos --nuevo
```

Mostrar:

- Mismo `X-Turno` → misma respuesta (idempotencia).
- Audio vacío → HTTP 400 `AUDIO_VACIO`.
- Audio > 30 s → HTTP 400 `AUDIO_DEMASIADO_LARGO`.
- Error de IA → HTTP 200, `estado=ERROR` y audio para reintentar.

### 6. Modo Gemini (opcional, 60 s)

```powershell
$env:GEMINI_API_KEY="..."
$env:MINIA_AI_MODE="gemini"
$env:MINIA_TTS_MODE="mock"   # recomendado en plan free
cd server
mvn spring-boot:run
```

Advertir:

- En plan gratuito no se deben enviar voces reales de niños.
- El TTS free tiene cuota muy baja (≈3 solicitudes/minuto); puede devolver `429`.
  Por eso la demo estable usa evaluación Gemini + voz mock.
- Si se activa `MINIA_TTS_MODE=gemini`, la primera síntesis tarda más; el caché
  acelera las repeticiones, pero no evita la cuota de frases nuevas.

### 7. Cierre (30 s)

- El MVP cumple: 5 de 10 preguntas, pista al primer error, respuesta correcta al
  segundo, audio PCM16 y registro en PostgreSQL.
- El contrato permite reemplazar el simulador por el ESP32 real sin cambiar el
  backend.
- Próximos pasos: WebSocket, panel de textos/preguntas editable, métricas por
  aula y despliegue en Raspberry Pi 5.

## Preguntas frecuentes

**¿Por qué HTTP y no WebSocket?**  
El MVP necesita turnos discretos y half-duplex; HTTP es más simple de depurar y
de implementar en el ESP32. WebSocket queda para v2.

**¿Se guarda el audio del niño?**  
No. Solo la transcripción y metadatos. El audio se procesa en memoria.

**¿Qué pasa si Gemini falla?**  
El turno devuelve HTTP 200 con `estado=ERROR` y audio para reintentar; la sesión
no se pierde.

**¿Cómo se integra con GARAGE?**  
GARAGE puede consumir los endpoints de la consola o incrustar el panel. El ESP32
solo requiere la URL base y el token.
