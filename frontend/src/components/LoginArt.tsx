"use client";

import { useEffect, useRef, useState } from "react";
import styles from "@/app/[lang]/auth.module.css";

type Three = typeof import("three");

/** Repeating motifs, all drawn by one fragment shader; the order matches the `uPattern` branches. */
const PATTERNS = ["Truchet", "Quarts", "Trame", "Diagonales", "Matrice", "Chevrons"] as const;

/** [background, primary, secondary, accent]. */
const PALETTES: [string, string, string, string][] = [
  ["#0d0a1f", "#8f4dff", "#2ec4ff", "#ffb35c"],
  ["#0b0f0c", "#c6ff3d", "#1f6f50", "#ff4fd8"],
  ["#0f1630", "#ff6b5b", "#ffd3a5", "#4fd1c5"],
  ["#0a0d14", "#e8edf7", "#4a5a7a", "#5ee6ff"],
  ["#1a0b1e", "#ff3d7f", "#ff9f43", "#ffe66d"],
  ["#04141c", "#1fb6a8", "#145c8a", "#e9f871"],
];

type Variant = { pattern: number; palette: number; scale: number; seed: number };

const LAST_KEY = "mystix-login-art";

/**
 * A new composition on every visit: another motif, palette, density and seed than the previous sign-in on this
 * browser. Storage only avoids an immediate repeat; without it the draw is simply random.
 */
function drawVariant(): Variant {
  let last: Partial<Variant> = {};
  try {
    last = JSON.parse(window.localStorage.getItem(LAST_KEY) ?? "{}") as Partial<Variant>;
  } catch {
    // private window or blocked storage
  }
  const pick = (n: number, avoid?: number) => {
    const i = Math.floor(Math.random() * (avoid === undefined ? n : n - 1));
    return avoid !== undefined && i >= avoid ? i + 1 : i;
  };
  const variant: Variant = {
    pattern: pick(PATTERNS.length, last.pattern),
    palette: pick(PALETTES.length, last.palette),
    scale: 7 + Math.floor(Math.random() * 8),
    seed: Math.floor(Math.random() * 10000),
  };
  try {
    window.localStorage.setItem(LAST_KEY, JSON.stringify(variant));
  } catch {
    // ignored
  }
  return variant;
}

/**
 * The floating art panel of the sign-in pages: a generative, repeating digital motif that changes on every visit.
 * Decorative (hidden from assistive technologies); three.js loads only here, after the form. Still frame when the
 * visitor prefers reduced motion; paused while the tab is hidden; CSS pattern fallback without WebGL.
 */
export function LoginArt({ label, caption, chips }: { label: string; caption: string; chips: string[] }) {
  const card = useRef<HTMLDivElement>(null);
  const host = useRef<HTMLDivElement>(null);
  const tag = useRef<HTMLSpanElement>(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    // The panel only shows on wide screens: no WebGL work where it is hidden.
    if (!window.matchMedia("(min-width: 961px)").matches) return;
    const variant = drawVariant();
    const [bg, a, b, c] = PALETTES[variant.palette];
    card.current?.style.setProperty("--art-bg", bg);
    card.current?.style.setProperty("--art-a", a);
    card.current?.style.setProperty("--art-b", b);
    card.current?.style.setProperty("--art-c", c);
    if (tag.current) {
      tag.current.textContent = `№ ${String(variant.seed).padStart(4, "0")} · ${PATTERNS[variant.pattern]}`;
    }
    let disposed = false;
    let cleanup: () => void = () => {};
    import("three")
      .then((THREE) => {
        if (disposed || !host.current) return;
        cleanup = mountScene(THREE, host.current, variant, () => setFallback(true));
      })
      .catch(() => setFallback(true));
    return () => {
      disposed = true;
      cleanup();
    };
  }, []);

  return (
    <div ref={card} className={styles.artCard}>
      <div ref={host} className={styles.artCanvas} aria-hidden="true">
        {fallback && <div className={styles.artFallback} />}
      </div>
      <div className={styles.artTop}>
        <span className={styles.artDot} aria-hidden="true" />
        {label}
      </div>
      <span ref={tag} className={styles.artTag} aria-hidden="true" />
      <div className={styles.artBottom}>
        <p>{caption}</p>
        <ul>
          {chips.map((c) => (
            <li key={c}>{c}</li>
          ))}
        </ul>
      </div>
    </div>
  );
}

const VERTEX = /* glsl */ `
varying vec2 vUv;
void main() {
  vUv = uv;
  gl_Position = vec4(position.xy, 0.0, 1.0);
}
`;

const FRAGMENT = /* glsl */ `
precision highp float;
uniform float uTime;
uniform vec2 uRes;
uniform vec2 uMouse;
uniform float uPattern;
uniform float uSeed;
uniform float uScale;
uniform vec3 uBg;
uniform vec3 uA;
uniform vec3 uB;
uniform vec3 uC;
varying vec2 vUv;

const float PI = 3.14159265;

float hash(vec2 p) {
  p = fract(p * vec2(123.34, 456.21) + uSeed * 0.0137);
  p += dot(p, p + 45.32);
  return fract(p.x * p.y);
}

float box(vec2 p, vec2 b) {
  vec2 d = abs(p) - b;
  return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
}

void main() {
  vec2 px = vUv * uRes;
  float cell = min(uRes.x, uRes.y) / uScale;
  vec2 p = px / cell;
  vec2 m = uMouse * uRes / cell;
  float near = smoothstep(4.5, 0.0, length(p - m));
  vec2 id = floor(p);
  vec2 f = fract(p) - 0.5;
  float h = hash(id);
  float t = uTime;
  float aa = 1.4 / cell;
  float wave = 0.5 + 0.5 * sin(length(id - m) * 0.45 - t * 1.6);
  vec3 col = uBg;
  int k = int(uPattern + 0.5);

  if (k == 0) {
    // Truchet: quarter arcs, a random flip per cell, light running along the paths.
    vec2 g = f;
    if (h > 0.5) g.x = -g.x;
    vec2 c1 = g - vec2(0.5);
    vec2 c2 = g + vec2(0.5);
    float d1 = abs(length(c1) - 0.5);
    float d2 = abs(length(c2) - 0.5);
    vec2 c = d1 < d2 ? c1 : c2;
    float d = min(d1, d2);
    float w = 0.07 + near * 0.06;
    float line = 1.0 - smoothstep(w - aa, w + aa, d);
    float u = atan(c.y, c.x) / (PI * 0.5);
    float dash = smoothstep(0.32, 0.0, abs(fract(u * 1.5 - t * 0.35 + h) - 0.5));
    vec3 ink = mix(mix(uB, uA, wave), uC, dash * 0.9);
    col = mix(uBg, ink, line);
    col += uA * 0.12 * (1.0 - smoothstep(0.0, 0.25, d)) * wave;
  } else if (k == 1) {
    // Quarter circles: concentric bands centred on a random corner of each cell.
    vec2 corner = vec2(h < 0.5 ? -0.5 : 0.5, fract(h * 7.0) < 0.5 ? -0.5 : 0.5);
    float r = length(f - corner);
    float bands = fract(r * 3.0 - t * 0.25 - h);
    float fill = smoothstep(0.5 - aa * 3.0, 0.5 + aa * 3.0, bands);
    vec3 a = mod(id.x + id.y, 2.0) < 1.0 ? uA : uB;
    col = mix(uBg, a, fill * (0.55 + 0.45 * wave));
    if (h > 0.9) col = mix(col, uC, fill);
    float gap = 1.0 - smoothstep(0.47, 0.5, max(abs(f.x), abs(f.y)));
    col = mix(uBg, col, gap);
  } else if (k == 2) {
    // Halftone: a dot screen whose radius follows a wave from the pointer.
    float r = 0.06 + 0.32 * wave + near * 0.1;
    float dot = 1.0 - smoothstep(r - aa, r + aa, length(f));
    vec3 a = mix(uB, uA, vUv.y);
    float spark = step(0.97, h) * (0.5 + 0.5 * sin(t * 3.0 + h * 40.0));
    col = mix(uBg, mix(a, uC, spark), dot);
  } else if (k == 3) {
    // Diagonals: each tile cut along one diagonal, two tones, a slow light sweep.
    float side = h < 0.5 ? f.x + f.y : f.x - f.y;
    vec3 a = side > 0.0 ? uA : uB;
    float sweep = 0.5 + 0.5 * sin((id.x - id.y) * 0.35 - t * 0.9);
    float edge = smoothstep(0.0, aa * 2.0, abs(side));
    col = mix(uBg, a, (0.35 + 0.65 * sweep) * edge);
    if (fract(h * 13.0) > 0.93) col = mix(col, uC, 0.85 * edge);
    float gap = 1.0 - smoothstep(0.44, 0.47, max(abs(f.x), abs(f.y)));
    col = mix(uBg, col, gap);
  } else if (k == 4) {
    // Matrix: plus signs, a few cells lit as messages pass, a scan line going down.
    float d = min(box(f, vec2(0.26, 0.045)), box(f, vec2(0.045, 0.26)));
    float plus = 1.0 - smoothstep(-aa, aa, d - near * 0.03);
    float lit = step(0.9, hash(id + floor(t * 1.2 + h * 10.0)));
    float scan = smoothstep(0.08, 0.0, abs(fract(vUv.y * 0.6 + t * 0.12) - 0.5) - 0.0);
    vec3 a = mix(uB * 0.8, uA, max(scan, near * 0.6));
    col = mix(uBg, mix(a, uC, lit), plus);
    col += uC * lit * 0.08 * (1.0 - smoothstep(0.0, 0.5, length(f)));
  } else {
    // Chevrons: zigzag bands in three tones, bent by the pointer.
    float v = p.x * 0.5 + abs(fract(p.y * 0.5) - 0.5) * 2.0 + near * 0.6;
    float band = fract(v * 0.75 - t * 0.18);
    vec3 a = band < 0.33 ? uA : band < 0.66 ? uB : uC;
    float edge = smoothstep(0.0, 0.03, min(abs(band - 0.33), min(abs(band - 0.66), min(band, 1.0 - band))));
    col = mix(uBg, a, (0.75 + 0.25 * wave) * edge);
  }

  // Pointer glow, vignette and a little grain.
  col += mix(uA, uC, 0.5) * near * 0.06;
  col *= 1.0 - 0.45 * pow(length(vUv - vec2(0.5, 0.55)) * 1.25, 2.2);
  col += (hash(px + fract(t)) - 0.5) * 0.025;
  gl_FragColor = vec4(col, 1.0);
}
`;

function mountScene(THREE: Three, host: HTMLDivElement, variant: Variant, onFail: () => void): () => void {
  let renderer: InstanceType<Three["WebGLRenderer"]>;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: false, alpha: false, powerPreference: "low-power" });
  } catch {
    onFail();
    return () => {};
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  renderer.domElement.setAttribute("aria-hidden", "true");
  host.appendChild(renderer.domElement);

  const [bg, a, b, c] = PALETTES[variant.palette].map((hex) => new THREE.Color(hex));
  const uniforms = {
    uTime: { value: (variant.seed % 97) * 0.37 },
    uRes: { value: new THREE.Vector2(1, 1) },
    uMouse: { value: new THREE.Vector2(0.62, 0.58) },
    uPattern: { value: variant.pattern },
    uSeed: { value: variant.seed },
    uScale: { value: variant.scale },
    uBg: { value: bg },
    uA: { value: a },
    uB: { value: b },
    uC: { value: c },
  };
  const geometry = new THREE.PlaneGeometry(2, 2);
  const material = new THREE.ShaderMaterial({ vertexShader: VERTEX, fragmentShader: FRAGMENT, uniforms });
  const scene = new THREE.Scene();
  scene.add(new THREE.Mesh(geometry, material));
  const camera = new THREE.Camera();

  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let running = false;
  let frame = 0;
  let last = performance.now();
  const size = new THREE.Vector2();
  const resize = () => {
    renderer.setSize(host.clientWidth || 1, host.clientHeight || 1, false);
    renderer.getDrawingBufferSize(size);
    uniforms.uRes.value.copy(size);
  };
  const observer = new ResizeObserver(() => {
    resize();
    if (!running) renderer.render(scene, camera);
  });
  observer.observe(host);
  resize();

  // The pointer is eased towards, so the motif breathes around it rather than jumping.
  const target = uniforms.uMouse.value.clone();
  const onPointer = (e: PointerEvent) => {
    const rect = host.getBoundingClientRect();
    target.set((e.clientX - rect.left) / rect.width, 1 - (e.clientY - rect.top) / rect.height);
  };
  window.addEventListener("pointermove", onPointer, { passive: true });

  const loop = (now: number) => {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    uniforms.uTime.value += dt;
    uniforms.uMouse.value.lerp(target, Math.min(1, dt * 3));
    renderer.render(scene, camera);
    frame = requestAnimationFrame(loop);
  };
  const start = () => {
    if (running || motion.matches || document.hidden) return;
    running = true;
    last = performance.now();
    frame = requestAnimationFrame(loop);
  };
  const stop = () => {
    running = false;
    cancelAnimationFrame(frame);
  };
  const onVisibility = () => (document.hidden ? stop() : start());
  const onMotion = () => {
    if (motion.matches) {
      stop();
      renderer.render(scene, camera);
    } else {
      start();
    }
  };
  document.addEventListener("visibilitychange", onVisibility);
  motion.addEventListener("change", onMotion);

  // A first, still frame (the whole picture for reduced motion), then the loop.
  renderer.render(scene, camera);
  start();

  return () => {
    stop();
    observer.disconnect();
    window.removeEventListener("pointermove", onPointer);
    document.removeEventListener("visibilitychange", onVisibility);
    motion.removeEventListener("change", onMotion);
    geometry.dispose();
    material.dispose();
    renderer.dispose();
    renderer.domElement.remove();
  };
}
