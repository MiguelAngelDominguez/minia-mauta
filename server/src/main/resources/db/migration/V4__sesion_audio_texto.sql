-- Agrega referencia al audio del texto de lectura por sesión.
-- El audio se genera al crear la sesión y se guarda en
-- data/audio-cache/sesion-{sesionId}-texto.pcm; se sirve como WAV al front.

ALTER TABLE sesion ADD COLUMN audio_texto_ruta TEXT;