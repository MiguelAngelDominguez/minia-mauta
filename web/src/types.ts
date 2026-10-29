export type EstadoSesion =
  | "CREADA"
  | "PREGUNTANDO"
  | "EVALUANDO"
  | "PISTA"
  | "RESPUESTA"
  | "SIN_CONFIANZA"
  | "FINALIZADA"
  | "ERROR";

export type Resultado =
  | "CORRECTO"
  | "INCORRECTO"
  | "SIN_CONFIANZA"
  | "NINGUNO"
  | "ERROR";

export interface ResumenPanel {
  sesiones: number;
  activas: number;
  finalizadas: number;
  turnos: number;
  aciertos: number;
  errores: number;
  latenciaPromedioMs: number;
}

export interface SesionResumen {
  id: string;
  deviceId: string;
  alias: string | null;
  textoId: string;
  titulo: string;
  estado: EstadoSesion;
  preguntaActual: number;
  totalPreguntas: number;
  aciertos: number;
  errores: number;
  turnos: number;
  iniciadaEn: string;
  finalizadaEn: string | null;
}

export interface TextoView {
  id: string;
  titulo: string;
  grado: string;
  lineas: string[];
  activo: boolean;
  totalPreguntas: number;
}

export interface DiagnosticoView {
  motorIa: string;
  modoIa: string;
  motorTts: string;
  modoTts: string;
  rate: number;
  canales: number;
  maxSegundos: number;
  contratoVersion: number;
}

export interface PanelResponse {
  resumen: ResumenPanel;
  sesiones: SesionResumen[];
  textos: TextoView[];
  diagnostico: DiagnosticoView;
}

export interface PreguntaView {
  id: string;
  orden: number;
  enunciado: string;
  respuestaEsperada: string;
  palabrasClave: string[];
  lineaPista: number;
  seccionPista: string | null;
  dificultad: string;
}

export interface TurnoView {
  id: string;
  secuencia: number;
  intento: number;
  preguntaId: string;
  resultado: Resultado;
  confianza: number | null;
  transcripcion: string | null;
  pista: string | null;
  feedback: string | null;
  latenciaMs: number | null;
  audioMs: number | null;
  creadoEn: string;
}

export interface SesionDetalle {
  id: string;
  deviceId: string;
  alias: string | null;
  textoId: string;
  titulo: string;
  grado: string;
  lineas: string[];
  estado: EstadoSesion;
  preguntaActual: number;
  totalPreguntas: number;
  aciertos: number;
  errores: number;
  intentosActual: number;
  turnos: number;
  iniciadaEn: string;
  finalizadaEn: string | null;
  preguntaActualDetalle: PreguntaView;
  preguntas: PreguntaView[];
  historial: TurnoView[];
}
