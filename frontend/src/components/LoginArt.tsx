"use client";

import { useEffect, useRef, useState } from "react";
import styles from "@/app/[lang]/auth.module.css";

type Three = typeof import("three");

const VIOLET = 0xb57bff;
const CYAN = 0x5ee6ff;
const AMBER = 0xffb35c;

/**
 * The floating art panel of the sign-in page: a network of exchange partners, with messages hopping along its links.
 * Decorative (the canvas is hidden from assistive technologies); three.js loads only here, after the form. Still
 * frame when the visitor prefers reduced motion; paused while the tab is hidden; CSS fallback without WebGL.
 */
export function LoginArt({ label, caption, chips }: { label: string; caption: string; chips: string[] }) {
  const host = useRef<HTMLDivElement>(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    // The panel only shows on wide screens: no WebGL work where it is hidden.
    if (!window.matchMedia("(min-width: 961px)").matches) return;
    let disposed = false;
    let cleanup: () => void = () => {};
    import("three")
      .then((THREE) => {
        if (disposed || !host.current) return;
        cleanup = mountScene(THREE, host.current, () => setFallback(true));
      })
      .catch(() => setFallback(true));
    return () => {
      disposed = true;
      cleanup();
    };
  }, []);

  return (
    <div className={styles.artCard}>
      <div ref={host} className={styles.artCanvas} aria-hidden="true">
        {fallback && <div className={styles.artFallback} />}
      </div>
      <div className={styles.artTop}>
        <span className={styles.artDot} aria-hidden="true" />
        {label}
      </div>
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

/** Soft round sprite drawn once on a canvas, used for nodes, packets and the core glow. */
function glowTexture(THREE: Three) {
  const size = 64;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = size;
  const ctx = canvas.getContext("2d");
  if (ctx) {
    const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
    g.addColorStop(0, "rgba(255,255,255,1)");
    g.addColorStop(0.25, "rgba(255,255,255,0.85)");
    g.addColorStop(0.6, "rgba(255,255,255,0.18)");
    g.addColorStop(1, "rgba(255,255,255,0)");
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, size, size);
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

function mountScene(THREE: Three, host: HTMLDivElement, onFail: () => void): () => void {
  let renderer: InstanceType<Three["WebGLRenderer"]>;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "low-power" });
  } catch {
    onFail();
    return () => {};
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(0x000000, 0);
  renderer.domElement.setAttribute("aria-hidden", "true");
  host.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  scene.fog = new THREE.FogExp2(0x0c0a1a, 0.06);
  const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100);
  camera.position.set(0, 0, 8.2);

  const disposables: { dispose: () => void }[] = [];
  const keep = <T extends { dispose: () => void }>(item: T) => {
    disposables.push(item);
    return item;
  };
  const sprite = keep(glowTexture(THREE));
  const world = new THREE.Group();
  scene.add(world);

  // Partners: nodes on a Fibonacci sphere, slightly jittered so it reads as a living network.
  const N = 150;
  const nodes: InstanceType<Three["Vector3"]>[] = [];
  const golden = Math.PI * (3 - Math.sqrt(5));
  for (let i = 0; i < N; i++) {
    const y = 1 - (i / (N - 1)) * 2;
    const r = Math.sqrt(1 - y * y);
    const theta = golden * i;
    const radius = 2.25 + (Math.sin(i * 12.9898) * 0.5 + 0.5) * 0.35;
    nodes.push(new THREE.Vector3(Math.cos(theta) * r * radius, y * radius, Math.sin(theta) * r * radius));
  }
  const violet = new THREE.Color(VIOLET);
  const cyan = new THREE.Color(CYAN);
  const nodeColors = new Float32Array(N * 3);
  nodes.forEach((p, i) => {
    const c = violet.clone().lerp(cyan, (p.y / 2.6 + 1) / 2);
    c.toArray(nodeColors, i * 3);
  });
  const nodeGeometry = keep(new THREE.BufferGeometry().setFromPoints(nodes));
  nodeGeometry.setAttribute("color", new THREE.BufferAttribute(nodeColors, 3));
  const nodeMaterial = keep(
    new THREE.PointsMaterial({
      size: 0.16,
      map: sprite,
      vertexColors: true,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  );
  world.add(new THREE.Points(nodeGeometry, nodeMaterial));

  // Links: each partner to its three nearest neighbours.
  const edges: [number, number][] = [];
  const seen = new Set<string>();
  nodes.forEach((p, i) => {
    nodes
      .map((q, j) => ({ j, d: p.distanceToSquared(q) }))
      .filter((x) => x.j !== i)
      .sort((a, b) => a.d - b.d)
      .slice(0, 3)
      .forEach(({ j }) => {
        const key = i < j ? `${i}-${j}` : `${j}-${i}`;
        if (!seen.has(key)) {
          seen.add(key);
          edges.push([i, j]);
        }
      });
  });
  const linePositions = new Float32Array(edges.length * 6);
  const lineColors = new Float32Array(edges.length * 6);
  edges.forEach(([a, b], k) => {
    nodes[a].toArray(linePositions, k * 6);
    nodes[b].toArray(linePositions, k * 6 + 3);
    nodeColors.slice(a * 3, a * 3 + 3).forEach((v, n) => (lineColors[k * 6 + n] = v));
    nodeColors.slice(b * 3, b * 3 + 3).forEach((v, n) => (lineColors[k * 6 + 3 + n] = v));
  });
  const lineGeometry = keep(new THREE.BufferGeometry());
  lineGeometry.setAttribute("position", new THREE.BufferAttribute(linePositions, 3));
  lineGeometry.setAttribute("color", new THREE.BufferAttribute(lineColors, 3));
  const lineMaterial = keep(
    new THREE.LineBasicMaterial({
      vertexColors: true,
      transparent: true,
      opacity: 0.26,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  );
  world.add(new THREE.LineSegments(lineGeometry, lineMaterial));

  // Core: the canonical model at the centre, a slow icosahedron with a soft glow.
  const coreGeometry = keep(new THREE.IcosahedronGeometry(0.85, 1));
  const coreMaterial = keep(
    new THREE.MeshBasicMaterial({ color: VIOLET, wireframe: true, transparent: true, opacity: 0.4 }),
  );
  const core = new THREE.Mesh(coreGeometry, coreMaterial);
  world.add(core);
  const haloMaterial = keep(
    new THREE.SpriteMaterial({
      map: sprite,
      color: 0x8f4dff,
      transparent: true,
      opacity: 0.55,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  );
  const halo = new THREE.Sprite(haloMaterial);
  halo.scale.setScalar(3.4);
  world.add(halo);

  // Orbits.
  const ringGeometry = keep(new THREE.TorusGeometry(3.25, 0.006, 8, 220));
  const rings = [
    { color: CYAN, tilt: [1.15, 0.2, 0] },
    { color: VIOLET, tilt: [1.9, -0.45, 0.3] },
  ].map(({ color, tilt }) => {
    const material = keep(new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.32 }));
    const ring = new THREE.Mesh(ringGeometry, material);
    ring.rotation.set(tilt[0], tilt[1], tilt[2]);
    world.add(ring);
    return ring;
  });

  // Messages: packets hopping from partner to partner along the links.
  const M = 46;
  const neighbours = nodes.map(() => [] as number[]);
  edges.forEach(([a, b]) => {
    neighbours[a].push(b);
    neighbours[b].push(a);
  });
  const packets = Array.from({ length: M }, (_, i) => {
    const [from, to] = edges[(i * 37) % edges.length];
    return { from, to, t: (i * 0.137) % 1, speed: 0.35 + ((i * 7) % 10) / 22 };
  });
  const packetPositions = new Float32Array(M * 3);
  const packetColors = new Float32Array(M * 3);
  const amber = new THREE.Color(AMBER);
  packets.forEach((_, i) => (i % 3 === 0 ? amber : cyan).toArray(packetColors, i * 3));
  const packetGeometry = keep(new THREE.BufferGeometry());
  packetGeometry.setAttribute("position", new THREE.BufferAttribute(packetPositions, 3));
  packetGeometry.setAttribute("color", new THREE.BufferAttribute(packetColors, 3));
  const packetMaterial = keep(
    new THREE.PointsMaterial({
      size: 0.26,
      map: sprite,
      vertexColors: true,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  );
  world.add(new THREE.Points(packetGeometry, packetMaterial));
  const placePackets = (dt: number) => {
    const tmp = new THREE.Vector3();
    packets.forEach((p, i) => {
      p.t += dt * p.speed;
      if (p.t >= 1) {
        p.t -= 1;
        p.from = p.to;
        const options = neighbours[p.from];
        p.to = options[Math.floor(Math.random() * options.length)] ?? p.from;
      }
      tmp.copy(nodes[p.from]).lerp(nodes[p.to], p.t);
      tmp.toArray(packetPositions, i * 3);
    });
    packetGeometry.attributes.position.needsUpdate = true;
  };

  // Far stars for depth.
  const stars = new Float32Array(700 * 3);
  for (let i = 0; i < 700; i++) {
    const v = new THREE.Vector3(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5)
      .normalize()
      .multiplyScalar(9 + Math.random() * 7);
    v.toArray(stars, i * 3);
  }
  const starGeometry = keep(new THREE.BufferGeometry());
  starGeometry.setAttribute("position", new THREE.BufferAttribute(stars, 3));
  const starMaterial = keep(
    new THREE.PointsMaterial({ size: 0.035, color: 0xd9ccff, transparent: true, opacity: 0.55, depthWrite: false }),
  );
  scene.add(new THREE.Points(starGeometry, starMaterial));

  // Size, pointer parallax, motion preferences, visibility.
  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let running = false;
  let frame = 0;
  let last = performance.now();
  let time = 0;
  const resize = () => {
    const w = host.clientWidth || 1;
    const h = host.clientHeight || 1;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  };
  const observer = new ResizeObserver(() => {
    resize();
    if (!running) renderer.render(scene, camera);
  });
  observer.observe(host);
  resize();

  const pointer = { x: 0, y: 0 };
  const onPointer = (e: PointerEvent) => {
    const rect = host.getBoundingClientRect();
    pointer.x = ((e.clientX - rect.left) / rect.width - 0.5) * 2;
    pointer.y = ((e.clientY - rect.top) / rect.height - 0.5) * 2;
  };
  window.addEventListener("pointermove", onPointer, { passive: true });


  const draw = (dt: number) => {
    time += dt;
    world.rotation.y += dt * 0.11;
    world.rotation.x += (pointer.y * 0.18 - world.rotation.x) * Math.min(1, dt * 2);
    world.rotation.z += (-pointer.x * 0.1 - world.rotation.z) * Math.min(1, dt * 2);
    world.position.y = Math.sin(time * 0.55) * 0.09;
    core.rotation.x += dt * 0.25;
    core.rotation.y -= dt * 0.18;
    halo.material.opacity = 0.45 + Math.sin(time * 1.4) * 0.1;
    rings[0].rotation.z += dt * 0.08;
    rings[1].rotation.z -= dt * 0.06;
    placePackets(dt);
    renderer.render(scene, camera);
  };
  const loop = (now: number) => {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    draw(dt);
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
  placePackets(0);
  world.rotation.set(0.12, -0.5, 0);
  renderer.render(scene, camera);
  start();

  return () => {
    stop();
    observer.disconnect();
    window.removeEventListener("pointermove", onPointer);
    document.removeEventListener("visibilitychange", onVisibility);
    motion.removeEventListener("change", onMotion);
    disposables.forEach((d) => d.dispose());
    renderer.dispose();
    renderer.domElement.remove();
  };
}
