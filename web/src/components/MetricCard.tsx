interface MetricCardProps {
  label: string;
  value: string | number;
  hint?: string;
  tone?: "brand" | "teal" | "amber" | "red" | "neutral";
}

export function MetricCard({ label, value, hint, tone = "neutral" }: MetricCardProps) {
  return (
    <div className={`metric metric-${tone}`}>
      <span className="metric-label">{label}</span>
      <strong className="metric-value">{value}</strong>
      {hint ? <span className="metric-hint">{hint}</span> : null}
    </div>
  );
}
