# Simulador visual del hardware MinIA Mauta

Aplicación de escritorio en Python que muestra el hardware físico del MVP en una vista isométrica 2.5D: ESP32 NodeMCU, micrófono INMP441, amplificador MAX98357A, bocina, LEDs, resistencias, switch, protoboard y cableado. No muestra carcasa ni al servidor.

El audio y las peticiones HTTP son reales: usa el micrófono de la PC, envía PCM16 al backend y reproduce el PCM16 que devuelve el servidor.

## Requisitos

- Python 3.11 o superior.
- Backend Spring Boot en `http://localhost:8080` y PostgreSQL en `5433`.
- Dependencias:

```powershell
pip install -r sim/requirements-visual.txt
```

`pywin32` solo es necesario para el modo texto con la voz SAPI de Windows.

## Ejecutar

Desde la raíz del proyecto:

```powershell
python sim/run_visual.py
```

Opciones:

```powershell
# Empezar en modo texto
python sim/run_visual.py --modo texto

# Forzar resultados con backend mock (solo demostración)
python sim/run_visual.py --forzar CORRECTO

# Sesión nueva con otro deviceId
python sim/run_visual.py --nuevo

# Probar sin micrófono ni parlante
python sim/run_visual.py --sin-audio

# Prueba de humo: abre y cierra a los 2 segundos
python sim/run_visual.py --sin-audio --smoke
```

## Controles

- Clic en el switch o `P`: encender/apagar.
- Mantener `Espacio` o clic en el micrófono: grabar y enviar.
- `Enter`: enviar la respuesta escrita.
- `M`: alternar `MIC`, `AUTO` y `TEXTO`.
- `Tab`: enfocar la caja de texto.
- `Esc`: salir.

## Modos de entrada

- `MIC`: usa el micrófono real. Si no hay micrófono, avisa.
- `TEXTO`: escribes la respuesta y SAPI genera el audio.
- `AUTO`: intenta micrófono y cae a texto si no está disponible.

## Backend mock y Gemini

Por defecto el simulador no envía cabeceras `X-Mock-*`, así que funciona con el backend actual en modo mock y también con Gemini.

Para evaluación real de voz, arranca el backend con:

```powershell
$env:MINIA_AI_MODE='gemini'
$env:GEMINI_API_KEY='...'
$env:MINIA_TTS_MODE='mock'   # recomendado por cuota gratuita de TTS
```

El simulador no modifica el backend ni el frontend.

## Limitaciones

- Es una simulación visual, no un emulador eléctrico del ESP32.
- Los pines y cables son una representación; no hay WiFi, I2S ni DMA reales.
- El micrófono y el parlante son los de la PC, no los módulos físicos.
- La cuota gratuita de TTS de Gemini puede devolver `429`; usa `MINIA_TTS_MODE=mock` para pruebas largas.

## Problemas comunes

- **No aparece el micrófono:** revisa el dispositivo de entrada de Windows y prueba `--mic-index N`.
- **No conecta:** confirma que el backend responde en `http://localhost:8080/api/v1/device/contrato`.
- **No hay voz en modo texto:** instala `pywin32` y verifica la voz `Microsoft Sabina Desktop` en Windows.
- **La ventana no cabe en la pantalla:** usa una resolución de al menos 1280×800.
