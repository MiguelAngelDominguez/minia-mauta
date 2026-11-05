// Copia este archivo como "secrets.h" (misma carpeta) y ajusta los valores.
// secrets.h está en .gitignore: no lo subas al repositorio.
#pragma once

// Red Wi-Fi (el ESP32 solo soporta 2.4 GHz)
#define WIFI_SSID      "TU_RED_WIFI"
#define WIFI_PASSWORD  "TU_PASSWORD_WIFI"

// IP de la PC donde corre el servidor Spring Boot (puerto de SERVER_PORT)
#define SERVER_HOST    "192.168.1.50"
#define SERVER_PORT    8080

// Debe coincidir con DEVICE_TOKEN del servidor (por defecto "dev-token")
#define DEVICE_TOKEN   "dev-token"

// Identificador estable del robot y alias opcional del niño
#define DEVICE_ID      "esp32-aula-01"
#define ALIAS_NINO     "Mateo"
