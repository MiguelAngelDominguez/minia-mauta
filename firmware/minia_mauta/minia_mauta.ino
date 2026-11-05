// MinIA Mauta - firmware ESP32 (NodeMCU DevKit) con INMP441 + MAX98357A.
// Implementa el contrato v1 de docs/contrato-esp32.md:
//   POST /api/v1/device/sesiones                 -> frame [u32 BE len(JSON)][JSON][PCM16LE]
//   POST /api/v1/device/sesiones/{id}/turno      -> PCM16LE 16 kHz mono (chunked) -> frame
//   GET  /api/v1/device/sesiones/{id}/audio-actual -> PCM16LE crudo (resincronizar)
//
// Requiere: core Arduino-ESP32 (usa la API I2S legacy "driver/i2s.h").
// Antes de compilar: copiar secrets.example.h a secrets.h y editarlo.

#include <WiFi.h>
#include <WiFiClient.h>
#include <driver/i2s.h>
#include <esp_idf_version.h>
#include <math.h>
#include <string.h>

#include "secrets.h"

// =========================================================================
// 1. PINES (protoboard GARAGE). Verificar contra el cableado real.
// =========================================================================
#define PIN_LED_VERDE  18
#define PIN_LED_ROJO   19

// Bus I2S compartido (reloj y selección de canal)
#define I2S_BCLK       14  // SCK (INMP441) y BCLK (MAX98357A)
#define I2S_LRC        15  // WS  (INMP441) y LRC  (MAX98357A). GPIO15 es pin de arranque.
// Datos separados
#define I2S_MIC_SD     32  // SD  (INMP441)  -> entrada
#define I2S_SPK_DIN    22  // DIN (MAX98357A) -> salida

// =========================================================================
// 2. PARÁMETROS (contrato v1). Los #ifndef permiten sobrescribirlos antes.
// =========================================================================
#define CONTRATO_ESPERADO "1"
#define I2S_PORT          I2S_NUM_0
#define SAMPLE_RATE       16000
#define CHUNK_SAMPLES     1600                       // 100 ms
#define CHUNK_BYTES       (CHUNK_SAMPLES * 2)        // 3200 bytes

#ifndef UMBRAL_RMS_VOZ
#define UMBRAL_RMS_VOZ    450.0f   // RMS sin DC. CALIBRAR con DEBUG_VAD=1 en el ambiente real.
#endif
#ifndef TIEMPO_SILENCIO
#define TIEMPO_SILENCIO   1200     // ms de silencio tras hablar que cierran el turno
#endif
#ifndef DURACION_MINIMA
#define DURACION_MINIMA   400      // ms (el servidor exige >= 300)
#endif
#ifndef DURACION_MAXIMA
#define DURACION_MAXIMA   29000    // ms (el servidor acepta <= 30 s = 960000 bytes)
#endif
#ifndef ESPERA_VOZ_INICIAL
#define ESPERA_VOZ_INICIAL 8000    // ms sin hablar antes de enviar igualmente el turno
#endif
#ifndef TIMEOUT_RESPUESTA_MS
#define TIMEOUT_RESPUESTA_MS 45000 // Gemini + TTS pueden tardar; contrato: >= 20 s
#endif
#ifndef DEBUG_VAD
#define DEBUG_VAD 0                // 1 = imprime el RMS de cada bloque de 100 ms
#endif

// Tiempo que tarda en vaciarse la cola DMA de salida (6 x 512 muestras @16 kHz = 192 ms)
#define DMA_BUF_COUNT     6
#define DMA_BUF_LEN       512
#define DMA_DRENAJE_MS    ((DMA_BUF_COUNT * DMA_BUF_LEN * 1000) / SAMPLE_RATE + 60)

// Códigos de retorno internos (>0 son códigos HTTP)
#define ERR_RED           -1
#define ERR_CONTRATO      -2
#define ERR_PROTOCOLO     -3

// =========================================================================
// 3. ESTADO GLOBAL
// =========================================================================
static int16_t pcmChunkBuffer[CHUNK_SAMPLES];
static uint8_t netBuffer[1024];
// Búfer de transmisión: "XXXX\r\n" (máx. 6 bytes con 3200 = C80) + datos + "\r\n"
static uint8_t txBuffer[16 + CHUNK_BYTES + 2];

static String sesionIdActual = "";
static String estadoSesion   = "CREADA";
static String resultadoTurno = "NINGUNO";
static bool   sesionFinalizada = false;
static int    fallosConsecutivos = 0;

// Respuesta HTTP: estado, cabeceras relevantes y modo del cuerpo
struct RespHttp {
  int  status = ERR_RED;
  long contentLength = -1;
  bool chunked = false;
  String version;
  String estado;
  String resultado;
  String sesionId;
};

// Lector de cuerpo: soporta Content-Length, Transfer-Encoding: chunked y lectura hasta cierre
struct Cuerpo {
  WiFiClient* c = nullptr;
  bool chunked = false;
  long restante = -1;     // bytes por leer del tramo actual; -1 = hasta que cierre la conexión
  bool fin = false;
};

// =========================================================================
// 4. UTILIDADES
// =========================================================================
String generarUUIDv4() {
  char uuid[37];
  uint32_t r1 = esp_random(), r2 = esp_random(), r3 = esp_random(), r4 = esp_random();
  snprintf(uuid, sizeof(uuid), "%08x-%04x-4%03x-%04x-%04x%08x",
           (unsigned)r1,
           (unsigned)(uint16_t)(r2 >> 16),
           (unsigned)(uint16_t)(r2 & 0x0FFF),
           (unsigned)(uint16_t)(((r3 >> 16) & 0x3FFF) | 0x8000),
           (unsigned)(uint16_t)(r3 & 0xFFFF),
           (unsigned)r4);
  return String(uuid);
}

// RMS tras restar la media (el INMP441 tiene offset DC que falsearía la VAD)
float calcularRMS(const int16_t* muestras, int cantidad) {
  if (cantidad <= 0) return 0;
  double media = 0;
  for (int i = 0; i < cantidad; i++) media += muestras[i];
  media /= cantidad;
  double suma = 0;
  for (int i = 0; i < cantidad; i++) {
    double d = (double)muestras[i] - media;
    suma += d * d;
  }
  return (float)sqrt(suma / cantidad);
}

void parpadearRojo(int veces, int ms) {
  for (int i = 0; i < veces; i++) {
    digitalWrite(PIN_LED_ROJO, HIGH); delay(ms);
    digitalWrite(PIN_LED_ROJO, LOW);  delay(ms);
  }
}

void apagarLEDs() {
  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);
}

void actualizarLEDsPorResultado(const String& resultado, const String& estado) {
  if (resultado == "CORRECTO") {
    digitalWrite(PIN_LED_VERDE, HIGH);
    digitalWrite(PIN_LED_ROJO, LOW);
  } else if (resultado == "INCORRECTO" || estado == "PISTA" || estado == "ERROR") {
    digitalWrite(PIN_LED_VERDE, LOW);
    digitalWrite(PIN_LED_ROJO, HIGH);
  } else {
    apagarLEDs();
  }
}

// Extrae el valor de una clave string simple de un JSON plano: "clave":"valor"
String valorJson(const String& json, const char* clave) {
  String patron = String("\"") + clave + "\":\"";
  int i = json.indexOf(patron);
  if (i < 0) return "";
  i += patron.length();
  int j = json.indexOf('"', i);
  return (j < 0) ? "" : json.substring(i, j);
}

// =========================================================================
// 5. I2S
// =========================================================================
void iniciarI2S() {
  i2s_config_t cfg;
  memset(&cfg, 0, sizeof(cfg));
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_TX);
  cfg.sample_rate = SAMPLE_RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = DMA_BUF_COUNT;
  cfg.dma_buf_len = DMA_BUF_LEN;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true;

  // memset deja mck_io_num en 0 (GPIO0) en IDF >= 4.4: se fija explícitamente a "sin MCLK".
  i2s_pin_config_t pins;
  memset(&pins, 0, sizeof(pins));
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(4, 4, 0)
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
#endif
  pins.bck_io_num = I2S_BCLK;
  pins.ws_io_num = I2S_LRC;
  pins.data_out_num = I2S_SPK_DIN;
  pins.data_in_num = I2S_MIC_SD;

  if (i2s_driver_install(I2S_PORT, &cfg, 0, NULL) != ESP_OK ||
      i2s_set_pin(I2S_PORT, &pins) != ESP_OK) {
    Serial.println("[ERROR CRÍTICO] No se pudo inicializar I2S.");
    while (true) { parpadearRojo(1, 100); }
  }
  i2s_zero_dma_buffer(I2S_PORT);
}

// Descarta lo que haya en la cola DMA de entrada (audio viejo o eco del parlante)
void reiniciarCaptura() {
  i2s_stop(I2S_PORT);
  i2s_zero_dma_buffer(I2S_PORT);
  i2s_start(I2S_PORT);
}

// =========================================================================
// 6. LECTURA/ESCRITURA HTTP
// =========================================================================
bool leerLinea(WiFiClient& client, String& linea, unsigned long timeoutMs) {
  linea = "";
  unsigned long inicio = millis();
  while (millis() - inicio < timeoutMs) {
    if (client.available()) {
      char ch = (char)client.read();
      if (ch == '\n') { linea.trim(); return true; }
      if (linea.length() < 512) linea += ch;
      inicio = millis();
    } else if (!client.connected()) {
      linea.trim();
      return linea.length() > 0;
    } else {
      delay(1);
    }
  }
  return false;
}

bool escribirTodo(WiFiClient& client, const uint8_t* datos, size_t n) {
  size_t enviados = 0;
  unsigned long inicio = millis();
  while (enviados < n) {
    size_t w = client.write(datos + enviados, n - enviados);
    if (w > 0) {
      enviados += w;
      inicio = millis();
    } else {
      if (!client.connected() || millis() - inicio > 5000) return false;
      delay(1);
    }
  }
  return true;
}

// Lee status y cabeceras. Deja el cliente posicionado al inicio del cuerpo.
bool leerCabeceras(WiFiClient& client, RespHttp& r) {
  unsigned long inicio = millis();
  while (!client.available() && client.connected()) {
    if (millis() - inicio > TIMEOUT_RESPUESTA_MS) {
      Serial.println("[ERROR] Timeout esperando respuesta del servidor.");
      return false;
    }
    delay(10);
  }
  String linea;
  if (!leerLinea(client, linea, 5000)) return false;
  int a = linea.indexOf(' ');
  if (a < 0) return false;
  r.status = linea.substring(a + 1, a + 4).toInt();
  if (r.status < 100) return false;

  while (true) {
    if (!leerLinea(client, linea, 5000)) return false;
    if (linea.length() == 0) return true;  // fin de cabeceras
    int sep = linea.indexOf(':');
    if (sep <= 0) continue;
    String k = linea.substring(0, sep);
    String v = linea.substring(sep + 1);
    k.trim(); v.trim();
    if (k.equalsIgnoreCase("Content-Length")) r.contentLength = v.toInt();
    else if (k.equalsIgnoreCase("Transfer-Encoding")) r.chunked = v.equalsIgnoreCase("chunked");
    else if (k.equalsIgnoreCase("X-Contrato-Version")) r.version = v;
    else if (k.equalsIgnoreCase("X-Estado")) r.estado = v;
    else if (k.equalsIgnoreCase("X-Resultado")) r.resultado = v;
    else if (k.equalsIgnoreCase("X-Sesion-Id")) r.sesionId = v;
  }
}

Cuerpo abrirCuerpo(WiFiClient& client, const RespHttp& r) {
  Cuerpo b;
  b.c = &client;
  b.chunked = r.chunked;
  b.restante = r.chunked ? 0 : r.contentLength;  // -1 si no hay longitud: hasta cierre
  b.fin = (!r.chunked && r.contentLength == 0);
  return b;
}

// Lee hasta 'max' bytes del cuerpo. Devuelve n>0, 0 si terminó el cuerpo, -1 si hubo error/timeout.
int leerCuerpo(Cuerpo& b, uint8_t* destino, size_t max, unsigned long timeoutMs = 25000) {
  if (b.fin || max == 0) return 0;
  WiFiClient& c = *b.c;
  if (b.chunked && b.restante == 0) {
    String linea;
    if (!leerLinea(c, linea, timeoutMs)) return -1;
    if (linea.length() == 0 && !leerLinea(c, linea, timeoutMs)) return -1;  // CRLF previo
    long tam = strtol(linea.c_str(), NULL, 16);
    if (tam <= 0) { b.fin = true; return 0; }
    b.restante = tam;
  }
  size_t aLeer = max;
  if (b.restante >= 0 && (size_t)b.restante < aLeer) aLeer = (size_t)b.restante;

  unsigned long inicio = millis();
  while (!c.available()) {
    if (!c.connected()) {
      // Conexión cerrada: si faltaban bytes declarados, el cuerpo quedó truncado
      if (b.restante > 0) return -1;
      b.fin = true;
      return 0;
    }
    if (millis() - inicio > timeoutMs) return -1;
    delay(1);
  }
  int n = c.read(destino, aLeer);
  if (n <= 0) return -1;
  if (b.restante >= 0) {
    b.restante -= n;
    if (b.restante == 0 && !b.chunked) b.fin = true;
  }
  return n;
}

bool leerExactos(Cuerpo& b, uint8_t* destino, size_t n) {
  size_t leidos = 0;
  while (leidos < n) {
    int k = leerCuerpo(b, destino + leidos, n - leidos);
    if (k <= 0) return false;
    leidos += k;
  }
  return true;
}

// Lee (y descarta el exceso de) el cuerpo como texto; sirve para JSON de error.
String leerCuerpoTexto(Cuerpo& b, size_t maxChars) {
  String s;
  int n;
  while ((n = leerCuerpo(b, netBuffer, sizeof(netBuffer), 3000)) > 0) {
    for (int i = 0; i < n && s.length() < maxChars; i++) s += (char)netBuffer[i];
  }
  return s;
}

// =========================================================================
// 7. REPRODUCCIÓN (parlante)
// =========================================================================
// Reproduce PCM16LE leyendo el resto del cuerpo. Mantiene la paridad de bytes
// entre lecturas con un byte de arrastre. Devuelve false si el stream se truncó.
bool reproducir_parlante(Cuerpo& b) {
  Serial.println("[GARAGE] Reproduciendo audio PCM16LE...");
  size_t total = 0;
  bool carry = false;
  uint8_t carryByte = 0;
  bool ok = true;

  while (true) {
    size_t offset = 0;
    if (carry) { netBuffer[0] = carryByte; offset = 1; carry = false; }
    int n = leerCuerpo(b, netBuffer + offset, sizeof(netBuffer) - offset);
    if (n < 0) { Serial.println("[WARN] Stream de audio truncado."); ok = false; break; }
    if (n == 0) break;
    size_t len = offset + n;
    if (len & 1) { carryByte = netBuffer[len - 1]; carry = true; len--; }
    if (len > 0) {
      size_t escritos = 0;
      i2s_write(I2S_PORT, netBuffer, len, &escritos, portMAX_DELAY);
      total += len;
    }
  }
  // i2s_write retorna al copiar a la cola DMA: esperar a que se escuche todo antes de silenciar
  if (total > 0) delay(DMA_DRENAJE_MS);
  i2s_zero_dma_buffer(I2S_PORT);
  Serial.printf("[GARAGE] Audio reproducido: %u bytes.\n", (unsigned)total);
  return ok;
}

// =========================================================================
// 8. RESPUESTA DEL SERVIDOR
// =========================================================================
// Procesa una respuesta ya recibida (cabeceras leídas). Si es 200 con frame,
// parsea [u32 BE len(JSON)][JSON][PCM], actualiza estado/LEDs y reproduce.
// 'framed' = false para audio-actual (PCM crudo sin frame).
// 'codigo' recibe el "codigo" del JSON de error cuando status != 200.
int procesarRespuesta(WiFiClient& client, RespHttp& r, bool framed, String& codigo) {
  codigo = "";
  Cuerpo cuerpo = abrirCuerpo(client, r);

  if (r.status != 200) {
    String err = leerCuerpoTexto(cuerpo, 400);
    codigo = valorJson(err, "codigo");
    Serial.printf("[HTTP %d] %s\n", r.status, err.c_str());
    return r.status;
  }

  // Contrato §10: la cabecera debe existir y coincidir
  if (r.version != CONTRATO_ESPERADO) {
    Serial.printf("[ERROR CRÍTICO] Contrato incompatible o ausente: '%s'\n", r.version.c_str());
    return ERR_CONTRATO;
  }
  if (r.estado.length()) estadoSesion = r.estado;
  if (r.resultado.length()) resultadoTurno = r.resultado;
  if (r.sesionId.length()) sesionIdActual = r.sesionId;
  if (estadoSesion == "FINALIZADA") sesionFinalizada = true;

  if (framed) {
    uint8_t lenBuf[4];
    if (!leerExactos(cuerpo, lenBuf, 4)) {
      Serial.println("[ERROR] No se pudo leer la longitud del frame.");
      return ERR_PROTOCOLO;
    }
    uint32_t jsonLen = ((uint32_t)lenBuf[0] << 24) | ((uint32_t)lenBuf[1] << 16) |
                       ((uint32_t)lenBuf[2] << 8) | (uint32_t)lenBuf[3];
    if (jsonLen > 64 * 1024) {
      Serial.println("[ERROR] JSON del frame supera 64 KiB.");
      return ERR_PROTOCOLO;
    }
    String jsonMeta;
    jsonMeta.reserve(jsonLen < 2048 ? jsonLen : 2048);
    uint32_t restante = jsonLen;
    while (restante > 0) {
      size_t aLeer = restante < sizeof(netBuffer) ? restante : sizeof(netBuffer);
      if (!leerExactos(cuerpo, netBuffer, aLeer)) return ERR_PROTOCOLO;
      for (size_t i = 0; i < aLeer && jsonMeta.length() < 2048; i++) jsonMeta += (char)netBuffer[i];
      restante -= aLeer;
    }
    Serial.printf("[META] Estado=%s | Resultado=%s | JSON=%s\n",
                  estadoSesion.c_str(), resultadoTurno.c_str(), jsonMeta.c_str());
    if (estadoSesion == "FINALIZADA" || jsonMeta.indexOf("\"fin\":true") >= 0) {
      sesionFinalizada = true;
    }
  }

  actualizarLEDsPorResultado(resultadoTurno, estadoSesion);
  bool ok = reproducir_parlante(cuerpo);
  apagarLEDs();
  return ok ? 200 : ERR_PROTOCOLO;
}

// =========================================================================
// 9. LLAMADAS AL SERVIDOR
// =========================================================================
void cabecerasComunes(WiFiClient& client) {
  client.printf("Host: %s:%d\r\n", SERVER_HOST, (int)SERVER_PORT);
  client.printf("X-Device-Token: %s\r\n", DEVICE_TOKEN);
  client.printf("X-Device-Id: %s\r\n", DEVICE_ID);
}

// Envía un bloque PCM como chunk HTTP en un solo write (cabecera+datos+CRLF).
bool llamarAPI(WiFiClient& client, const uint8_t* pcm, size_t length) {
  if (length == 0) return true;
  if (!client.connected()) return false;
  int h = snprintf((char*)txBuffer, 16, "%X\r\n", (unsigned)length);
  memcpy(txBuffer + h, pcm, length);
  txBuffer[h + length] = '\r';
  txBuffer[h + length + 1] = '\n';
  return escribirTodo(client, txBuffer, h + length + 2);
}

// POST /sesiones/{id}/turno: captura, VAD, envío chunked y reproducción de la respuesta.
int escuchar_micro(const String& turnoUuid, String& codigo) {
  codigo = "";
  WiFiClient client;
  client.setTimeout(30);  // WiFiClient::setTimeout recibe segundos
  if (!client.connect(SERVER_HOST, SERVER_PORT)) {
    Serial.println("[ERROR] No se pudo conectar al servidor para enviar el turno.");
    return ERR_RED;
  }
  client.setNoDelay(true);

  client.printf("POST /api/v1/device/sesiones/%s/turno HTTP/1.1\r\n", sesionIdActual.c_str());
  cabecerasComunes(client);
  client.printf("X-Turno: %s\r\n", turnoUuid.c_str());
  client.print("Content-Type: audio/L16;rate=16000;channels=1\r\n");
  client.print("Transfer-Encoding: chunked\r\n");
  client.print("Connection: close\r\n\r\n");

  // Half-duplex: vaciar la cola de captura y descartar 2 bloques para estabilizar el micrófono
  reiniciarCaptura();
  size_t bytesLeidos = 0;
  for (int i = 0; i < 2; i++) {
    i2s_read(I2S_PORT, (void*)pcmChunkBuffer, CHUNK_BYTES, &bytesLeidos, portMAX_DELAY);
  }

  Serial.println("[GARAGE] Escuchando al niño (habla ahora)...");
  unsigned long inicioGrabacion = millis();
  unsigned long ultimoSonido = millis();
  bool vozDetectada = false;

  while (true) {
    i2s_read(I2S_PORT, (void*)pcmChunkBuffer, CHUNK_BYTES, &bytesLeidos, portMAX_DELAY);
    bytesLeidos &= ~(size_t)1;  // número par de bytes (PCM16)
    if (bytesLeidos == 0) continue;

    float rms = calcularRMS(pcmChunkBuffer, bytesLeidos / 2);
#if DEBUG_VAD
    Serial.printf("[VAD] rms=%.1f umbral=%.1f\n", rms, (float)UMBRAL_RMS_VOZ);
#endif
    if (rms > UMBRAL_RMS_VOZ) {
      vozDetectada = true;
      ultimoSonido = millis();
    }

    if (!llamarAPI(client, (const uint8_t*)pcmChunkBuffer, bytesLeidos)) {
      Serial.println("[ERROR] Falló el envío de audio.");
      client.stop();
      return ERR_RED;
    }

    unsigned long ahora = millis();
    unsigned long total = ahora - inicioGrabacion;
    bool superaMinimo = total >= DURACION_MINIMA;
    bool silencioCompleto = vozDetectada && (ahora - ultimoSonido) >= TIEMPO_SILENCIO;
    bool sinVoz = !vozDetectada && total >= ESPERA_VOZ_INICIAL;
    if (superaMinimo && (silencioCompleto || sinVoz || total >= DURACION_MAXIMA)) break;
  }

  if (!escribirTodo(client, (const uint8_t*)"0\r\n\r\n", 5)) {
    client.stop();
    return ERR_RED;
  }
  Serial.println("[GARAGE] Fin de locución. Esperando evaluación...");

  RespHttp r;
  int status;
  if (!leerCabeceras(client, r)) {
    status = ERR_RED;
  } else {
    status = procesarRespuesta(client, r, true, codigo);
  }
  client.stop();
  return status;
}

// POST /sesiones: crea o reanuda la sesión y reproduce saludo + pregunta.
int crearOReanudarSesion(String& codigo) {
  codigo = "";
  WiFiClient client;
  client.setTimeout(30);
  if (!client.connect(SERVER_HOST, SERVER_PORT)) {
    Serial.println("[ERROR] Servidor Spring Boot inalcanzable.");
    return ERR_RED;
  }
  client.setNoDelay(true);

  String body = String("{\"deviceId\":\"") + DEVICE_ID + "\",\"alias\":\"" + ALIAS_NINO + "\",\"textoId\":null}";
  client.print("POST /api/v1/device/sesiones HTTP/1.1\r\n");
  cabecerasComunes(client);
  client.print("Content-Type: application/json\r\n");
  client.printf("Content-Length: %d\r\n", (int)body.length());
  client.print("Connection: close\r\n\r\n");
  client.print(body);

  sesionFinalizada = false;
  RespHttp r;
  int status;
  if (!leerCabeceras(client, r)) {
    status = ERR_RED;
  } else {
    status = procesarRespuesta(client, r, true, codigo);
  }
  client.stop();
  return status;
}

// GET /sesiones/{id}/audio-actual: resincroniza tras un fallo de red (PCM crudo, sin frame).
int resincronizar(String& codigo) {
  codigo = "";
  WiFiClient client;
  client.setTimeout(30);
  if (!client.connect(SERVER_HOST, SERVER_PORT)) return ERR_RED;
  client.setNoDelay(true);

  client.printf("GET /api/v1/device/sesiones/%s/audio-actual HTTP/1.1\r\n", sesionIdActual.c_str());
  cabecerasComunes(client);
  client.print("Connection: close\r\n\r\n");

  RespHttp r;
  int status;
  if (!leerCabeceras(client, r)) {
    status = ERR_RED;
  } else {
    status = procesarRespuesta(client, r, false, codigo);
  }
  client.stop();
  return status;
}

// =========================================================================
// 10. WI-FI Y SESIÓN
// =========================================================================
void conectarWiFiConParpadeo() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("Conectando a Wi-Fi");
  while (WiFi.status() != WL_CONNECTED) {
    parpadearRojo(1, 250);
    Serial.print(".");
  }
  Serial.printf("\nWi-Fi conectado. IP del ESP32: %s\n", WiFi.localIP().toString().c_str());
}

void esperarBackoff() {
  int n = fallosConsecutivos > 4 ? 4 : fallosConsecutivos;
  unsigned long ms = 1000UL << n;  // 1,2,4,8,16 s
  if (ms > 10000) ms = 10000;
  parpadearRojo(1, 200);
  delay(ms);
}

// Error que no se arregla reintentando (token o contrato): señal fija y parada.
void errorFatal(const char* motivo) {
  Serial.printf("=== ERROR FATAL: %s. Corrige la configuración y reinicia. ===\n", motivo);
  digitalWrite(PIN_LED_ROJO, HIGH);
  while (true) delay(1000);
}

// Intenta (re)crear la sesión hasta lograrlo. Los errores fatales detienen el firmware.
void asegurarSesion() {
  sesionIdActual = "";
  while (true) {
    String codigo;
    int st = crearOReanudarSesion(codigo);
    if (st == 200 && sesionIdActual.length() > 0) { fallosConsecutivos = 0; return; }
    if (st == ERR_CONTRATO) errorFatal("versión de contrato incompatible");
    if (st == 401 && codigo == "TOKEN_INVALIDO") errorFatal("DEVICE_TOKEN inválido");
    if (st == 400 && codigo == "DEVICE_ID_REQUERIDO") errorFatal("falta DEVICE_ID");
    fallosConsecutivos++;
    esperarBackoff();
  }
}

// =========================================================================
// 11. SETUP Y LOOP
// =========================================================================
void setup() {
  Serial.begin(115200);
  pinMode(PIN_LED_VERDE, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);
  apagarLEDs();

  iniciarI2S();
  conectarWiFiConParpadeo();

  Serial.println("Iniciando sesión en el servidor MinIA Mauta...");
  asegurarSesion();
  Serial.printf("Sesión activa: %s\n", sesionIdActual.c_str());
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    conectarWiFiConParpadeo();
  }

  if (sesionFinalizada) {
    Serial.println("=== SESIÓN FINALIZADA. Reinicia la placa (switch) para una sesión nueva ===");
    while (true) delay(1000);
  }

  String codigo;
  int st = escuchar_micro(generarUUIDv4(), codigo);

  if (st == 200) {
    fallosConsecutivos = 0;
  } else if (st == ERR_CONTRATO) {
    errorFatal("versión de contrato incompatible");
  } else if (st == 400 && (codigo == "AUDIO_VACIO" || codigo == "AUDIO_INVALIDO" ||
                           codigo == "AUDIO_MUY_CORTO" || codigo == "AUDIO_DEMASIADO_LARGO")) {
    // Recuperable: repetir la grabación sin cerrar la sesión
    Serial.println("[INFO] Audio no válido. Repitiendo captura...");
    delay(500);
  } else if (st == 400 && codigo == "SESION_FINALIZADA") {
    asegurarSesion();
  } else if (st == 404 || (st == 401 && codigo == "DEVICE_NO_COINCIDE")) {
    asegurarSesion();
  } else if (st == 401 && codigo == "TOKEN_INVALIDO") {
    errorFatal("DEVICE_TOKEN inválido");
  } else if (st == 400 && codigo == "DEVICE_ID_REQUERIDO") {
    errorFatal("falta DEVICE_ID");
  } else {
    // Red, timeout, frame truncado, 5xx: backoff y resincronizar con audio-actual
    fallosConsecutivos++;
    Serial.printf("[WARN] Fallo del turno (%d). Reintento #%d\n", st, fallosConsecutivos);
    esperarBackoff();
    String c2;
    int rs = resincronizar(c2);
    if (rs == 404) asegurarSesion();
    else if (rs == 400 && c2 == "SESION_FINALIZADA") asegurarSesion();
  }
}
