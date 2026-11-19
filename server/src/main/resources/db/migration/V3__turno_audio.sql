-- Agrega referencia al audio generado por turno (PCM16LE en disco).
-- El audio combinado (feedback + silencio + pregunta) se guarda en
-- data/audio-cache/turno-{turnoId}.pcm y se sirve como WAV al front.

ALTER TABLE turno ADD COLUMN audio_ruta TEXT;

CREATE INDEX ix_turno_audio_ruta ON turno (audio_ruta) WHERE audio_ruta IS NOT NULL;