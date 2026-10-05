import { useReducedMotion } from '../../hooks/useReducedMotion';
import type { CSSProperties } from 'react';
import './Shine.css';

/**
 * 流光文字。上游 React Bits 的 ShinyText 走 motion 的 useMotionValue（43KB gzip），
 * 一个扫光效果不值这个价，故用等价的 CSS 渐变实现；上游源码仍留在 reactbits/ 目录。
 */
export function Shine({
  text,
  base = '#ffe100',
  hot = '#fff8c2',
  speed = 3,
}: {
  text: string;
  base?: string;
  hot?: string;
  speed?: number;
}) {
  const reduced = useReducedMotion();

  if (reduced) {
    return <span style={{ color: base }}>{text}</span>;
  }

  return (
    <span
      className="shine"
      style={
        {
          '--shine-base': base,
          '--shine-hot': hot,
          '--shine-speed': `${speed}s`,
        } as CSSProperties
      }
    >
      {text}
    </span>
  );
}
