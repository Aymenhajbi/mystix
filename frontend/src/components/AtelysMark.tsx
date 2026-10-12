/**
 * Atelys mark, "Trame" direction (ADR-0014): an A built from twelve square modules, like warehouse locations or the
 * modules of a QR code; the apex module is the Mystix point. Drawn on a 48-unit grid.
 */
const MODULES: [number, number][] = [
  [10, 10], [30, 10],
  [0, 20], [10, 20], [20, 20], [30, 20], [40, 20],
  [0, 30], [40, 30],
  [0, 40], [40, 40],
];

export function AtelysMark({
  size = 32,
  body = "#FF5A1F",
  apex = "#8500DE",
  label,
}: {
  size?: number;
  body?: string;
  apex?: string;
  label?: string;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      style={{ flex: "none" }}
    >
      <rect x="20" y="0" width="8" height="8" rx="1.6" fill={apex} />
      {MODULES.map(([x, y]) => (
        <rect key={`${x}-${y}`} x={x} y={y} width="8" height="8" rx="1.6" fill={body} />
      ))}
    </svg>
  );
}
