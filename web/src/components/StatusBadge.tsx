const etiquetas: Record<string, string> = {
  CREADA: "Creada",
  PREGUNTANDO: "Preguntando",
  EVALUANDO: "Evaluando",
  PISTA: "Pista",
  RESPUESTA: "Respuesta",
  SIN_CONFIANZA: "Sin confianza",
  FINALIZADA: "Finalizada",
  ERROR: "Error",
  CORRECTO: "Correcto",
  INCORRECTO: "Incorrecto",
  NINGUNO: "Ninguno",
};

function clase(valor: string) {
  return `badge badge-${valor.toLowerCase().replace(/_/g, "-")}`;
}

export function StatusBadge({ estado }: { estado: string }) {
  return <span className={clase(estado)}>{etiquetas[estado] ?? estado}</span>;
}
