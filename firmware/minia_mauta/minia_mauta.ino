#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <driver/i2s.h>
#include <math.h>
#include <ArduinoJson.h>

#ifndef PI
#define PI 3.14159265358979323846f
#endif

// =========================================================
// 1. CONFIGURACIÓN
// =========================================================
const char* WIFI_SSID     = "TU_NOMBRE_DE_WIFI";      // 2.4 GHz
const char* WIFI_PASSWORD = "TU_PASSWORD_DE_WIFI";

const char* URL_BASE = "https://minia-mauta.onrender.com";
const char* DEVICE_TOKEN = "4c5c9b87-33d2-4cc8-bbb6-5f1eb906d494";
const char* DEVICE_ID = "esp32-01";

// =========================================================
// 2. PINES (idénticos al código original)
// =========================================================
#define I2S_WS   15
#define I2S_SCK  14
#define I2S_SD   33
#define I2S_DOUT 22

#define PIN_LED_VERDE 18
#define PIN_LED_ROJO  19

// =========================================================
// 3. PARÁMETROS TÉCNICOS
// =========================================================
#define I2S_PORT                  I2S_NUM_0
#define SAMPLE_RATE               16000
#define BUFFER_LEN                256
#define CHUNK_MUESTRAS_100MS      1600

#define TIEMPO_SILENCIO_VAD_MS    1200
#define DURACION_MAX_LOCUCION_MS  30000

#define GANANCIA_VOZ              4.0f
#define MINIMO_SONIDO_FUERTE      200.0f
#define VOLUMEN_BOCINA            0.15f
#define FRECUENCIA_PITIDO_HZ      880.0f
#define DURACION_PITIDO_MS        250

int32_t rawBuffer[BUFFER_LEN];
bool i2sActivo = false;

float dc_xPrev = 0.0f, dc_yPrev = 0.0f;
float hp_xPrev = 0.0f, hp_yPrev = 0.0f;
float lp_yPrev = 0.0f;
float pisoRuidoFijo = 40.0f;
float energiaSuavizada = 0.0f;

int pregunta_n = 1;
int aciertos = 0;
int intento = 1;
bool sesionTerminada = false;
String sesionId = "";

// Buffer de captura estático (32000 muestras = 2 s a 16 kHz = 64 KB)
#define MAX_MUESTRAS_CAPTURA 32000
int16_t bufferCaptura[MAX_MUESTRAS_CAPTURA];
size_t muestrasCapturadas = 0;

// =========================================================
// 4. I2S HALF-DUPLEX
// =========================================================
void iniciarModoEscucha() {
  if (i2sActivo) { i2s_driver_uninstall(I2S_PORT); i2sActivo = false; }
  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 6,
    .dma_buf_len = 128,
    .use_apll = false
  };
  i2s_pin_config_t pins = {
    .bck_io_num = I2S_SCK,
    .ws_io_num = I2S_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = I2S_SD
  };
  i2s_driver_install(I2S_PORT, &cfg, 0, NULL);
  i2s_set_pin(I2S_PORT, &pins);
  i2s_start(I2S_PORT);
  i2sActivo = true;
  dc_xPrev = dc_yPrev = hp_xPrev = hp_yPrev = lp_yPrev = 0.0f;
}

void deshabilitarI2S() {
  if (i2sActivo) { i2s_stop(I2S_PORT); i2s_driver_uninstall(I2S_PORT); i2sActivo = false; }
}

void iniciarModoParlante() {
  if (i2sActivo) { i2s_driver_uninstall(I2S_PORT); i2sActivo = false; }
  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
    .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 6,
    .dma_buf_len = 256,
    .use_apll = false,
    .tx_desc_auto_clear = true
  };
  i2s_pin_config_t pins = {
    .bck_io_num = I2S_SCK,
    .ws_io_num = I2S_WS,
    .data_out_num = I2S_DOUT,
    .data_in_num = I2S_PIN_NO_CHANGE
  };
  i2s_driver_install(I2S_PORT, &cfg, 0, NULL);
  i2s_set_pin(I2S_PORT, &pins);
  i2s_zero_dma_buffer(I2S_PORT);
  i2sActivo = true;
}

// =========================================================
// 5. FILTRADO DE VOZ (idéntico al original)
// =========================================================
float filtrarMuestraVoz(int32_t raw) {
  float x = (float)(raw >> 15);
  float dcOut = x - dc_xPrev + 0.995f * dc_yPrev;
  dc_xPrev = x; dc_yPrev = dcOut;
  const float aHP = 0.9585f;
  float hpOut = aHP * (hp_yPrev + dcOut - hp_xPrev);
  hp_xPrev = dcOut; hp_yPrev = hpOut;
  const float aLP = 0.5718f;
  lp_yPrev = lp_yPrev + aLP * (hpOut - lp_yPrev);
  return lp_yPrev;
}

void analizarBloqueVoz(float &rmsOut, float &freqHzOut, long &rawCrudoOut) {
  size_t bytesIn = 0;
  i2s_read(I2S_PORT, &rawBuffer, sizeof(rawBuffer), &bytesIn, portMAX_DELAY);
  int muestras = bytesIn / sizeof(int32_t);
  if (muestras <= 0) { rmsOut = 0; freqHzOut = 0; rawCrudoOut = 0; return; }
  double sumaCuadrados = 0; long sumaCruda = 0; int crucesPorCero = 0; float muestraAnterior = 0.0f;
  for (int i = 0; i < muestras; i++) {
    sumaCruda += abs(rawBuffer[i] >> 16);
    float limpia = filtrarMuestraVoz(rawBuffer[i]) * GANANCIA_VOZ;
    if (limpia > 16000.0f) limpia = 16000.0f + (limpia - 16000.0f) * 0.25f;
    else if (limpia < -16000.0f) limpia = -16000.0f + (limpia + 16000.0f) * 0.25f;
    limpia = constrain(limpia, -32000.0f, 32000.0f);
    if (i > 0) {
      if ((muestraAnterior < 0.0f && limpia >= 0.0f) || (muestraAnterior >= 0.0f && limpia < 0.0f)) crucesPorCero++;
    }
    muestraAnterior = limpia;
    sumaCuadrados += (double)limpia * limpia;
  }
  rawCrudoOut = sumaCruda / muestras;
  rmsOut = sqrt(sumaCuadrados / muestras);
  float duracionSeg = (float)muestras / (float)SAMPLE_RATE;
  freqHzOut = (crucesPorCero / 2.0f) / duracionSeg;
}

void calibrarRuidoInicial() {
  Serial.println("Calibrando ruido...");
  iniciarModoEscucha();
  float rms = 0, freq = 0; long rawCrudo = 0;
  for (int i = 0; i < 50; i++) analizarBloqueVoz(rms, freq, rawCrudo);
  float suma = 0, maximo = 0;
  for (int i = 0; i < 70; i++) {
    analizarBloqueVoz(rms, freq, rawCrudo);
    suma += rms;
    if (rms > maximo) maximo = rms;
  }
  pisoRuidoFijo = max((suma / 70.0f) * 0.6f + maximo * 0.6f, 25.0f);
  energiaSuavizada = pisoRuidoFijo;
  Serial.printf("Piso de ruido: %.1f\n", pisoRuidoFijo);
}

// =========================================================
// 6. HTTP HELPERS (Render HTTPS)
// =========================================================
WiFiClientSecure secureClient;

// Crea sesion, lee el framing de respuesta, reproduce el audio y devuelve el JSON en responseJson.
bool crearSesion(String& responseJson) {
  HTTPClient http;
  String url = String(URL_BASE) + "/api/v1/device/sesiones";
  http.begin(secureClient, url);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Token", DEVICE_TOKEN);
  http.addHeader("X-Device-Id", DEVICE_ID);

  String body = "{\"deviceId\":\"" + String(DEVICE_ID) + "\",\"alias\":\"ESP32\"}";
  int httpCode = http.POST(body);
  if (httpCode != 200) { http.end(); return false; }

  WiFiClient* stream = http.getStreamPtr();
  uint8_t lenBuf[4];
  if (stream->readBytes(lenBuf, 4) != 4) { http.end(); return false; }
  uint32_t jsonLen = ((uint32_t)lenBuf[0] << 24) | ((uint32_t)lenBuf[1] << 16) |
                     ((uint32_t)lenBuf[2] << 8) | (uint32_t)lenBuf[3];
  // Leer JSON
  char* jsonBuf = (char*)malloc(jsonLen + 1);
  if (!jsonBuf) { http.end(); return false; }
  size_t r = stream->readBytes((uint8_t*)jsonBuf, jsonLen);
  jsonBuf[r] = '\0';
  responseJson = String(jsonBuf);
  free(jsonBuf);

  // Reproducir audio restante en streaming
  iniciarModoParlante();
  uint8_t pcmBuf[512];
  size_t bytesEscritos;
  while (true) {
    int rr = stream->read(pcmBuf, sizeof(pcmBuf));
    if (rr <= 0) break;
    i2s_write(I2S_PORT, pcmBuf, rr, &bytesEscritos, portMAX_DELAY);
  }
  i2s_zero_dma_buffer(I2S_PORT);
  delay(50);
  deshabilitarI2S();

  http.end();
  return true;
}

// Envía PCM16 crudo y reproduce la respuesta en streaming.
// Devuelve el httpCode y llena estado/resultado por referencia.
int httpPostTurno(const char* sesionId, const int16_t* audio, size_t numMuestras,
                  String& estado, String& resultado) {
  HTTPClient http;
  String url = String(URL_BASE) + "/api/v1/device/sesiones/" + sesionId + "/turno";
  http.begin(secureClient, url);
  http.addHeader("Content-Type", "audio/L16;rate=16000;channels=1");
  http.addHeader("X-Device-Token", DEVICE_TOKEN);
  http.addHeader("X-Device-Id", DEVICE_ID);
  // X-Turno UUID simple
  http.addHeader("X-Turno", String(millis()));

  int httpCode = http.POST((uint8_t*)audio, numMuestras * sizeof(int16_t));
  if (httpCode <= 0) { http.end(); return httpCode; }

  // Leer headers
  estado = http.header("X-Estado");
  resultado = http.header("X-Resultado");

  // Reproducir audio de respuesta en streaming
  WiFiClient* stream = http.getStreamPtr();
  // Leer framing: 4 bytes BE length
  uint8_t lenBuf[4];
  if (stream->readBytes(lenBuf, 4) == 4) {
    uint32_t jsonLen = ((uint32_t)lenBuf[0] << 24) | ((uint32_t)lenBuf[1] << 16) |
                       ((uint32_t)lenBuf[2] << 8) | (uint32_t)lenBuf[3];
    // Saltar JSON
    uint8_t tmp[256];
    uint32_t restante = jsonLen;
    while (restante > 0) {
      size_t toRead = min((size_t)restante, sizeof(tmp));
      size_t r = stream->readBytes(tmp, toRead);
      if (r == 0) break;
      restante -= r;
    }
    // Ahora el resto es PCM16 -> reproducir en streaming
    iniciarModoParlante();
    uint8_t pcmBuf[512];
    size_t bytesEscritos;
    while (true) {
      int r = stream->read(pcmBuf, sizeof(pcmBuf));
      if (r <= 0) break;
      i2s_write(I2S_PORT, pcmBuf, r, &bytesEscritos, portMAX_DELAY);
    }
    i2s_zero_dma_buffer(I2S_PORT);
    delay(50);
    deshabilitarI2S();
  }
  http.end();
  return httpCode;
}

// =========================================================
// 7. VAD / CAPTURA (idéntico al original, con acumulación)
// =========================================================
bool capturarVoz() {
  Serial.println(">>> Capturando voz...");
  iniciarModoEscucha();

  // Buffer estático en RAM (MAX_MUESTRAS_CAPTURA). Sin malloc.
  size_t maxMuestras = MAX_MUESTRAS_CAPTURA;
  if (psramFound()) {
    maxMuestras = min((size_t)(30 * SAMPLE_RATE), (size_t)MAX_MUESTRAS_CAPTURA);
  }

  muestrasCapturadas = 0;
  bool ninoEmpezoAHablar = false;
  unsigned long tiempoInicioEscucha = millis();
  unsigned long tiempoInicioLocucion = 0;
  unsigned long ultimoMomentoVoz = 0;

  while (true) {
    float rmsActual = 0, freqHz = 0; long rawCrudo = 0;
    analizarBloqueVoz(rmsActual, freqHz, rawCrudo);

    // Acumular muestras filtradas
    for (int i = 0; i < BUFFER_LEN; i++) {
      if (muestrasCapturadas < maxMuestras) {
        float limpia = filtrarMuestraVoz(rawBuffer[i]) * GANANCIA_VOZ;
        limpia = constrain(limpia, -32767.0f, 32767.0f);
        bufferCaptura[muestrasCapturadas++] = (int16_t)limpia;
      }
    }

    float umbralVoz = (pisoRuidoFijo * 2.0f) + 50.0f;
    bool frecuenciaValida = (freqHz >= 95.0f && freqHz <= 3200.0f);
    bool esVozClara = (rmsActual > umbralVoz) && frecuenciaValida;
    bool hayVozActiva = esVozClara;

    unsigned long ahora = millis();
    if (hayVozActiva) {
      if (!ninoEmpezoAHablar) {
        ninoEmpezoAHablar = true;
        tiempoInicioLocucion = ahora;
      }
      ultimoMomentoVoz = ahora;
    }

    if (ninoEmpezoAHablar) {
      bool silencioCumplido = (ahora - ultimoMomentoVoz >= TIEMPO_SILENCIO_VAD_MS);
      bool duracionMaxima = (ahora - tiempoInicioLocucion >= DURACION_MAX_LOCUCION_MS);
      if (silencioCumplido || duracionMaxima) {
        deshabilitarI2S();
        return (muestrasCapturadas >= SAMPLE_RATE); // al menos 1 s
      }
    } else if (ahora - tiempoInicioEscucha >= DURACION_MAX_LOCUCION_MS) {
      deshabilitarI2S();
      return false;
    }
  }
}

// =========================================================
// 8. SETUP / LOOP
// =========================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  pinMode(PIN_LED_VERDE, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);
  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi OK: " + WiFi.localIP().toString());

  secureClient.setInsecure();

  calibrarRuidoInicial();

  // Crear sesión
  String respJson;
  if (crearSesion(respJson)) {
    DynamicJsonDocument doc(1024);
    if (deserializeJson(doc, respJson) == DeserializationError::Ok) {
      sesionId = doc["sesionId"].as<String>();
      Serial.println("Sesion: " + sesionId);
    } else {
      Serial.println("No se pudo parsear sesionId");
    }
  } else {
    Serial.println("Error creando sesion");
  }

  pregunta_n = 1; aciertos = 0; intento = 1; sesionTerminada = false;
}

void loop() {
  if (sesionTerminada) { delay(1000); return; }

  if (intento == 1) {
    Serial.printf("Pregunta %d de 5\n", pregunta_n);
    digitalWrite(PIN_LED_VERDE, LOW);
    digitalWrite(PIN_LED_ROJO, LOW);
  }

  bool locucionValida = capturarVoz();
  if (!locucionValida) {
    Serial.println("Sin voz detectada, reintentando...");
    delay(300);
    return;
  }

  String estado, resultado;
  int code = httpPostTurno(sesionId.c_str(), bufferCaptura, muestrasCapturadas, estado, resultado);
  Serial.printf("HTTP %d estado=%s resultado=%s\n", code, estado.c_str(), resultado.c_str());

  if (estado == "FINALIZADA") {
    sesionTerminada = true;
    Serial.println("Sesion finalizada.");
    return;
  }

  if (resultado == "CORRECTO") {
    aciertos++;
    digitalWrite(PIN_LED_VERDE, HIGH);
    digitalWrite(PIN_LED_ROJO, LOW);
  } else {
    digitalWrite(PIN_LED_VERDE, LOW);
    digitalWrite(PIN_LED_ROJO, HIGH);
    if (intento == 1) {
      intento = 2;
      return;
    }
  }

  if (pregunta_n < 5) {
    pregunta_n++;
    intento = 1;
  } else {
    sesionTerminada = true;
  }
  delay(200);
}
