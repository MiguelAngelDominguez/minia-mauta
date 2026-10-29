CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE texto (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    titulo VARCHAR(200) NOT NULL,
    grado VARCHAR(50) NOT NULL,
    cuerpo TEXT NOT NULL,
    lineas JSONB NOT NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pregunta (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    texto_id UUID NOT NULL REFERENCES texto(id),
    orden INT NOT NULL,
    enunciado TEXT NOT NULL,
    respuesta_esperada TEXT NOT NULL,
    palabras_clave JSONB NOT NULL,
    linea_pista INT NOT NULL,
    seccion_pista VARCHAR(200),
    dificultad VARCHAR(20) NOT NULL DEFAULT 'MEDIA',
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT ux_pregunta_texto_orden UNIQUE (texto_id, orden)
);

CREATE TABLE sesion (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(100) NOT NULL,
    alias VARCHAR(100),
    texto_id UUID NOT NULL REFERENCES texto(id),
    estado VARCHAR(30) NOT NULL,
    pregunta_actual INT NOT NULL DEFAULT 1,
    preguntas_ids JSONB NOT NULL,
    aciertos INT NOT NULL DEFAULT 0,
    errores INT NOT NULL DEFAULT 0,
    intentos_actual INT NOT NULL DEFAULT 0,
    turno_actual INT NOT NULL DEFAULT 0,
    iniciada_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    finalizada_en TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_sesion_activa_device ON sesion (device_id) WHERE finalizada_en IS NULL;
CREATE INDEX ix_sesion_device ON sesion (device_id);

CREATE TABLE turno (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sesion_id UUID NOT NULL REFERENCES sesion(id) ON DELETE CASCADE,
    turn_id UUID NOT NULL,
    secuencia INT NOT NULL,
    pregunta_id UUID NOT NULL REFERENCES pregunta(id),
    intento INT NOT NULL,
    transcripcion TEXT,
    resultado VARCHAR(30) NOT NULL,
    confianza DOUBLE PRECISION,
    pista TEXT,
    feedback TEXT,
    latencia_ms BIGINT,
    audio_ms BIGINT,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_turno_sesion_turn UNIQUE (sesion_id, turn_id)
);

CREATE INDEX ix_turno_sesion ON turno (sesion_id, secuencia);

CREATE TABLE audio_cache (
    hash VARCHAR(64) PRIMARY KEY,
    texto TEXT NOT NULL,
    voz VARCHAR(50) NOT NULL,
    rate INT NOT NULL,
    ruta TEXT NOT NULL,
    bytes BIGINT NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now()
);
