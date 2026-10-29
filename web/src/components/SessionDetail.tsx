import type { SesionDetalle } from "../types";
import { StatusBadge } from "./StatusBadge";

interface SessionDetailProps {
  detalle: SesionDetalle | null;
  cargando: boolean;
}

function fecha(iso: string | null) {
  if (!iso) {
    return "En curso";
  }
  return new Date(iso).toLocaleString("es-PE", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function resultadoClase(resultado: string) {
  return `timeline-item timeline-${resultado.toLowerCase()}`;
}

export function SessionDetail({ detalle, cargando }: SessionDetailProps) {
  if (!detalle) {
    return (
      <section className="card detail-placeholder">
        <h2>Detalle de la sesión</h2>
        <p>Selecciona una sesión de la lista para ver el texto, la pregunta actual y el historial de turnos.</p>
        {cargando ? <span className="loading">Cargando…</span> : null}
      </section>
    );
  }

  const progreso = Math.min(100, Math.round((detalle.preguntaActual / detalle.totalPreguntas) * 100));
  const pregunta = detalle.preguntaActualDetalle;

  return (
    <section className="detail">
      <header className="card detail-header">
        <div>
          <div className="eyebrow">Sesión {detalle.id.slice(0, 8)}</div>
          <h2>{detalle.alias ?? "Sin alias"}</h2>
          <p className="muted">
            {detalle.deviceId} · {detalle.titulo} · {detalle.grado}
          </p>
        </div>
        <div className="detail-status">
          <StatusBadge estado={detalle.estado} />
          <span className="muted">{fecha(detalle.finalizadaEn)}</span>
        </div>
      </header>

      <div className="detail-stats">
        <div><span>Pregunta</span><strong>{detalle.preguntaActual}/{detalle.totalPreguntas}</strong></div>
        <div><span>Aciertos</span><strong>{detalle.aciertos}</strong></div>
        <div><span>Errores</span><strong>{detalle.errores}</strong></div>
        <div><span>Turnos</span><strong>{detalle.turnos}</strong></div>
        <div><span>Intentos actuales</span><strong>{detalle.intentosActual}</strong></div>
      </div>

      <div className="progress progress-large">
        <span style={{ width: `${progreso}%` }} />
      </div>

      <div className="detail-grid">
        <article className="card text-card">
          <div className="card-heading">
            <h3>Texto de lectura</h3>
            <span className="muted">Línea de pista: {pregunta.lineaPista}</span>
          </div>
          <ol className="text-lines">
            {detalle.lineas.map((linea, index) => {
              const esPista = index + 1 === pregunta.lineaPista;
              return (
                <li key={index} className={esPista ? "text-line hint" : "text-line"}>
                  <span className="line-number">{index + 1}</span>
                  <span>{linea}</span>
                </li>
              );
            })}
          </ol>
        </article>

        <article className="card question-card">
          <div className="card-heading">
            <h3>Pregunta actual</h3>
            <span className={`chip chip-${pregunta.dificultad.toLowerCase()}`}>{pregunta.dificultad}</span>
          </div>
          <p className="question-text">{pregunta.enunciado}</p>
          <div className="question-meta">
            <span>Banco · orden {pregunta.orden}</span>
            <span>{pregunta.seccionPista}</span>
          </div>
          <details className="answer">
            <summary>Ver respuesta esperada</summary>
            <p>{pregunta.respuestaEsperada}</p>
          </details>
          {pregunta.palabrasClave.length > 0 ? (
            <div className="keywords">
              {pregunta.palabrasClave.map((palabra) => (
                <span key={palabra} className="keyword">{palabra}</span>
              ))}
            </div>
          ) : null}
        </article>
      </div>

      <article className="card history-card">
        <div className="card-heading">
          <h3>Historial de turnos</h3>
          <span className="muted">{detalle.historial.length} registros</span>
        </div>
        {detalle.historial.length === 0 ? (
          <div className="empty empty-compact">
            <p>La sesión todavía no tiene turnos.</p>
          </div>
        ) : (
          <ol className="timeline">
            {detalle.historial.map((turno) => (
              <li key={turno.id} className={resultadoClase(turno.resultado)}>
                <div className="timeline-top">
                  <div className="timeline-title">
                    <strong>Turno {turno.secuencia}</strong>
                    <StatusBadge estado={turno.resultado} />
                    <span className="muted">intento {turno.intento}</span>
                  </div>
                  <span className="muted">
                    {turno.latenciaMs ?? "—"} ms · {turno.audioMs ?? "—"} ms audio
                  </span>
                </div>
                {turno.transcripcion ? (
                  <p className="transcription">“{turno.transcripcion}”</p>
                ) : null}
                {turno.feedback ? <p className="feedback">{turno.feedback}</p> : null}
                {turno.pista ? <p className="hint-text">Pista: {turno.pista}</p> : null}
                <time className="muted">{fecha(turno.creadoEn)}</time>
              </li>
            ))}
          </ol>
        )}
      </article>

      {detalle.estado === "FINALIZADA" ? (
        <div className="final-banner">
          <strong>Actividad finalizada</strong>
          <span>{detalle.aciertos} aciertos y {detalle.errores} preguntas con respuesta revelada.</span>
        </div>
      ) : null}
    </section>
  );
}
