INSERT INTO texto (id, titulo, grado, cuerpo, lineas, activo)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'El zorro y el cuervo',
    'Primaria',
    'Un cuervo encontró un trozo de queso y voló hasta una rama. Un zorro que pasaba por allí lo vio y quiso quitarle el queso. El zorro pensó en una idea para engañar al cuervo. Se acercó y le dijo: "¡Qué plumas tan brillantes tienes!". El cuervo, muy contento, abrió el pico para cantar. En ese momento, el queso cayó al suelo. El zorro lo atrapó y se fue corriendo. El cuervo aprendió que no debía dejarse llevar por los halagos.',
    '["Un cuervo encontró un trozo de queso y voló hasta una rama.","Un zorro que pasaba por allí lo vio y quiso quitarle el queso.","El zorro pensó en una idea para engañar al cuervo.","Se acercó y le dijo: \"¡Qué plumas tan brillantes tienes!\".","El cuervo, muy contento, abrió el pico para cantar.","En ese momento, el queso cayó al suelo.","El zorro lo atrapó y se fue corriendo.","El cuervo aprendió que no debía dejarse llevar por los halagos."]'::jsonb,
    TRUE
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO pregunta (id, texto_id, orden, enunciado, respuesta_esperada, palabras_clave, linea_pista, seccion_pista, dificultad)
VALUES
('22222222-2222-2222-2222-222222222201','11111111-1111-1111-1111-111111111111',1,'¿Qué encontró el cuervo?','Un trozo de queso.','["queso"]'::jsonb,1,'Inicio del texto','FACIL'),
('22222222-2222-2222-2222-222222222202','11111111-1111-1111-1111-111111111111',2,'¿Dónde estaba el cuervo cuando lo vio el zorro?','En una rama.','["rama"]'::jsonb,2,'Inicio del texto','FACIL'),
('22222222-2222-2222-2222-222222222203','11111111-1111-1111-1111-111111111111',3,'¿Qué quería el zorro?','Quitarle el queso al cuervo.','["queso","quitar"]'::jsonb,2,'Inicio del texto','FACIL'),
('22222222-2222-2222-2222-222222222204','11111111-1111-1111-1111-111111111111',4,'¿Qué hizo el zorro para conseguir el queso?','Engañar al cuervo con halagos.','["engañ","halag","idea"]'::jsonb,3,'Tercera línea','MEDIA'),
('22222222-2222-2222-2222-222222222205','11111111-1111-1111-1111-111111111111',5,'¿Qué le dijo el zorro al cuervo?','Que tenía plumas muy brillantes.','["plumas","brillantes"]'::jsonb,4,'Cuarta línea','FACIL'),
('22222222-2222-2222-2222-222222222206','11111111-1111-1111-1111-111111111111',6,'¿Por qué el cuervo abrió el pico?','Para cantar, porque estaba contento.','["cantar","contento"]'::jsonb,5,'Quinta línea','MEDIA'),
('22222222-2222-2222-2222-222222222207','11111111-1111-1111-1111-111111111111',7,'¿Qué pasó cuando el cuervo abrió el pico?','El queso cayó al suelo.','["queso","cayó"]'::jsonb,6,'Sexta línea','FACIL'),
('22222222-2222-2222-2222-222222222208','11111111-1111-1111-1111-111111111111',8,'¿Qué hizo el zorro al final?','Atrapó el queso y se fue corriendo.','["atrap","corriendo","fue"]'::jsonb,7,'Séptima línea','FACIL'),
('22222222-2222-2222-2222-222222222209','11111111-1111-1111-1111-111111111111',9,'¿Cuál es la idea principal del texto?','No dejarse llevar por los halagos.','["halag","engaña","aprend"]'::jsonb,8,'Final del texto','MEDIA'),
('22222222-2222-2222-2222-222222222210','11111111-1111-1111-1111-111111111111',10,'¿Qué enseñanza deja la historia?','Que no debemos confiar en halagos falsos ni dejarnos engañar.','["halag","engaña","aprend","confiar"]'::jsonb,8,'Final del texto','DIFICIL')
ON CONFLICT (id) DO NOTHING;
