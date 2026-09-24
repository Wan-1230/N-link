import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';

export function useCountUp(target: number, durationMs = 1600): [RefObject<HTMLElement | null>, number] {
  const ref = useRef<HTMLElement | null>(null);
  const [value, setValue] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    let raf = 0;
    let started = false;

    const run = () => {
      if (started) return;
      started = true;
      const begin = performance.now();
      const tick = (now: number) => {
        const t = Math.min(1, (now - begin) / durationMs);
        setValue(Math.round(target * (1 - (1 - t) ** 3)));
        if (t < 1) raf = requestAnimationFrame(tick);
      };
      raf = requestAnimationFrame(tick);
    };

    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          run();
          io.disconnect();
        }
      },
      { threshold: 0.4 }
    );
    io.observe(el);

    return () => {
      io.disconnect();
      cancelAnimationFrame(raf);
    };
  }, [target, durationMs]);

  return [ref, value];
}
