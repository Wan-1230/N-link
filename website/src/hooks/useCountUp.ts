import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';

/**
 * 到位有两种方式：滚进视野就播一遍；已经被快速滚过去了就直接定格终值。
 *
 * 原来只用 IntersectionObserver + threshold 0.4。跳转式滚动（点锚点、窄屏一次滚过整段）
 * 不产生阈值穿越，回调根本不会触发，数字就永久停在 0 —— 320/390 档实测到
 * 「+0%」「0.0 秒」，属于静默失败，所以换成矩形探测。
 */
export function useCountUp(target: number, durationMs = 1600, decimals = 0): [RefObject<HTMLElement | null>, number] {
  const ref = useRef<HTMLElement | null>(null);
  const [value, setValue] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    // 档位、原因码是整数，实测耗时是小数；取整位数由调用方给，别把 2.8 跑成 3
    const scale = 10 ** decimals;
    const final = Math.round(target * scale) / scale;
    let raf = 0;
    let queued = false;
    let done = false;

    const animate = () => {
      done = true;
      const begin = performance.now();
      const tick = (now: number) => {
        const t = Math.min(1, (now - begin) / durationMs);
        setValue(Math.round(target * (1 - (1 - t) ** 3) * scale) / scale);
        if (t < 1) raf = requestAnimationFrame(tick);
      };
      raf = requestAnimationFrame(tick);
    };

    const probe = () => {
      if (done) return true;
      const r = el.getBoundingClientRect();
      if (r.bottom <= 0) {
        done = true;
        setValue(final);
      } else if (r.top < window.innerHeight * 0.9) {
        animate();
      }
      return done;
    };

    if (probe()) return () => cancelAnimationFrame(raf);

    const onScroll = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => {
        queued = false;
        if (probe()) window.removeEventListener('scroll', onScroll);
      });
    };
    window.addEventListener('scroll', onScroll, { passive: true });

    return () => {
      window.removeEventListener('scroll', onScroll);
      cancelAnimationFrame(raf);
    };
  }, [target, durationMs, decimals]);

  return [ref, value];
}
