import { useCallback, useEffect, useState } from "react";
import { api } from "./api";
import { MetricCard } from "./components/MetricCard";
import { SessionDetail } from "./components/SessionDetail";
import { SessionList } from "./components/SessionList";
import type { PanelResponse, SesionDetalle } from "./types";

export default function App() {
  const [panel, setPanel] = useState<PanelResponse | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detalle, setDetalle] = useState<SesionDetalle | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cargando, setCargando] = useState(true);
  const [actualizando, setActualizando] = useState(false);
  const [reseteando, setReseteando] = useState(false);

  const cargarPanel = useCallback(async (silencioso = false) => {
    if (!silencioso) {
      setCargando(true);
    }
    try {
      const data = await api.panel();
      setPanel(data);
      setError(null);
      setSelectedId((actual) => {
        if (actual && data.sesiones.some((sesion) => sesion.id === actual)) {
          return actual;
        }
        return data.sesiones[0]?.id ?? null;
      });
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "No se pudo cargar el panel.");
    } finally {
      setCargando(false);
    }
  }, []);

  const cargarDetalle = useCallback(async (id: string, silencioso = false) => {
    try {
      const data = await api.sesion(id);
      setDetalle(data);
      if (!silencioso) {
        setError(null);
      }
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "No se pudo cargar la sesión.");
    }
  }, []);

  useEffect(() => {
    void cargarPanel();
    const intervalo = window.setInterval(() => void cargarPanel(true), 5000);
    return () => window.clearInterval(intervalo);
  }, [cargarPanel]);

  useEffect(() => {
    if (!selectedId) {
      setDetalle(null);
      return;
    }
    void cargarDetalle(selectedId);
    const intervalo = window.setInterval(() => void cargarDetalle(selectedId, true), 5000);
    return () => window.clearInterval(intervalo);
  }, [selectedId, cargarDetalle]);

  const actualizar = async () => {
    setActualizando(true);
    try {
      await cargarPanel(true);
      if (selectedId) {
        await cargarDetalle(selectedId, true);
      }
    } finally {
      setActualizando(false);
    }
  };

  const reiniciar = async () => {
    const confirmado = window.confirm(
      "Se eliminarán todas las sesiones y turnos de la demo. Los textos y preguntas se mantienen. ¿Continuar?",
    );
    if (!confirmado) {
      return;
    }
    setReseteando(true);
    try {
      await api.resetDemo();
      setSelectedId(null);
      setDetalle(null);
      await cargarPanel(true);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : "No se pudo reiniciar la demo.");
    } finally {
      setReseteando(false);
    }
  };

  const resumen = panel?.resumen;

  return (
    <div className="page">
      <header className="topbar">
        <div className="brand">
          <div className="logo">M</div>
          <div>
            <h1>MinIA Mauta</h1>
            <p>Consola interna · comprensión lectora por voz</p>
          </div>
        </div>
        <div className="topbar-actions">
          {panel ? (
            <div className="pills">
              <span className="pill">Contrato v{panel.diagnostico.contratoVersion}</span>
              <span className="pill">IA: {panel.diagnostico.modoIa}</span>
              <span className="pill">TTS: {panel.diagnostico.modoTts}</span>
              <span className="pill">{panel.diagnostico.rate / 1000} kHz · PCM16</span>
            </div>
          ) : null}
          <button type="button" className="button button-ghost" onClick={() => void actualizar()} disabled={actualizando}>
            {actualizando ? "Actualizando…" : "Actualizar"}
          </button>
          <button type="button" className="button button-danger" onClick={() => void reiniciar()} disabled={reseteando}>
            {reseteando ? "Limpiando…" : "Reiniciar demo"}
          </button>
        </div>
      </header>

      {error ? (
        <div className="error-banner" role="alert">
          <div>
            <strong>No se pudo conectar con el servidor</strong>
            <span>{error}</span>
          </div>
          <button type="button" className="button button-ghost" onClick={() => void actualizar()}>
            Reintentar
          </button>
        </div>
      ) : null}

      {resumen ? (
        <section className="metrics" aria-label="Métricas generales">
          <MetricCard label="Sesiones" value={resumen.sesiones} hint="Totales registradas" tone="brand" />
          <MetricCard label="Activas" value={resumen.activas} hint="Sin finalizar" tone="teal" />
          <MetricCard label="Turnos" value={resumen.turnos} hint="Evaluaciones realizadas" />
          <MetricCard label="Aciertos" value={resumen.aciertos} hint="Turnos correctos" tone="teal" />
          <MetricCard label="Errores" value={resumen.errores} hint="Turnos incorrectos" tone="amber" />
          <MetricCard
            label="Latencia IA"
            value={`${resumen.latenciaPromedioMs.toFixed(1)} ms`}
            hint="Promedio de evaluación"
            tone="neutral"
          />
        </section>
      ) : cargando ? (
        <div className="loading-card">Cargando panel…</div>
      ) : null}

      <main className="layout">
        <section className="card sessions-card">
          <div className="card-heading">
            <h2>Sesiones</h2>
            <span className="muted">{panel?.sesiones.length ?? 0} recientes</span>
          </div>
          <SessionList
            sesiones={panel?.sesiones ?? []}
            selectedId={selectedId}
            onSelect={setSelectedId}
          />
        </section>
        <SessionDetail detalle={detalle} cargando={cargando} />
      </main>

      <footer className="footer">
        <span>MVP PI2 · Spring Boot + PostgreSQL + Gemini + ESP32</span>
        <span>Los datos se actualizan cada 5 segundos. El audio no se almacena.</span>
      </footer>
    </div>
  );
}
