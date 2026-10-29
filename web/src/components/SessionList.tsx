import type { SesionResumen } from "../types";
import { StatusBadge } from "./StatusBadge";

interface SessionListProps {
  sesiones: SesionResumen[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}

function hora(iso: string) {
  return new Date(iso).toLocaleString("es-PE", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function SessionList({ sesiones, selectedId, onSelect }: SessionListProps) {
  if (sesiones.length === 0) {
    return (
      <div className="empty">
        <span className="empty-icon">◌</span>
        <p>Todavía no hay sesiones.</p>
        <small>Ejecuta el simulador con <code>python sim\esp32_sim.py --modo demo --nuevo</code>.</small>
      </div>
    );
  }

  return (
    <ul className="session-list">
      {sesiones.map((sesion) => {
        const progreso = Math.min(100, Math.round((sesion.preguntaActual / sesion.totalPreguntas) * 100));
        const activa = sesion.id === selectedId;
        return (
          <li key={sesion.id}>
            <button
              type="button"
              className={`session-item${activa ? " is-active" : ""}`}
              onClick={() => onSelect(sesion.id)}
              aria-pressed={activa}
            >
              <div className="session-item-top">
                <div>
                  <strong>{sesion.alias ?? "Sin alias"}</strong>
                  <span className="session-device">{sesion.deviceId}</span>
                </div>
                <StatusBadge estado={sesion.estado} />
              </div>
              <div className="session-meta">
                <span>{sesion.titulo}</span>
                <span>{hora(sesion.iniciadaEn)}</span>
              </div>
              <div className="progress" aria-label={`Progreso ${progreso}%`}>
                <span style={{ width: `${progreso}%` }} />
              </div>
              <div className="session-foot">
                <span>{sesion.preguntaActual}/{sesion.totalPreguntas} preguntas</span>
                <span>{sesion.aciertos} aciertos · {sesion.errores} errores</span>
              </div>
            </button>
          </li>
        );
      })}
    </ul>
  );
}
