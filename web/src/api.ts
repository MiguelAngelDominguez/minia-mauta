import type { PanelResponse, SesionDetalle } from "./types";

const BASE = import.meta.env.VITE_API_BASE ?? "";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, init);
  if (!response.ok) {
    let mensaje = `Error HTTP ${response.status}`;
    try {
      const body = (await response.json()) as { mensaje?: string };
      if (body.mensaje) {
        mensaje = body.mensaje;
      }
    } catch {
      // El cuerpo no era JSON.
    }
    throw new Error(mensaje);
  }
  return (await response.json()) as T;
}

export const api = {
  panel: () => request<PanelResponse>("/api/v1/console/panel"),
  sesion: (id: string) => request<SesionDetalle>(`/api/v1/console/sesiones/${id}`),
  resetDemo: () =>
    request<{ ok: boolean; mensaje: string }>("/api/v1/console/reset-demo", {
      method: "POST",
    }),
};
