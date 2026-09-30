import { useCountUp } from '../../hooks/useCountUp';
import { useReducedMotion } from '../../hooks/useReducedMotion';

/**
 * 数字滚动。上游 React Bits 的 CountUp 依赖 motion（44KB gzip），
 * 只为一个计数器不合算，故本地用 rAF 实现；CountUp 源码仍留在 reactbits/ 目录。
 */
export function Counter({ to, prefix = '', suffix = '', duration }: { to: number; prefix?: string; suffix?: string; duration?: number }) {
  const reduced = useReducedMotion();
  const [ref, value] = useCountUp(to, duration);

  return (
    <span ref={ref}>
      {prefix}
      {reduced ? to : value}
      {suffix}
    </span>
  );
}
