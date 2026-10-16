// React Bits 上游组件的统一出口。源文件逐字拷自上游，出处见 ./SOURCE.json 与 ./LICENSE.upstream.txt。
//
// 上游是 .jsx，TS 从解构默认值推断时会把未给默认值的回调判为必填；实际运行时
// SplitText 用 onCompleteRef.current?.()、CountUp 用 typeof fn === 'function' 保护，
// 所以这里按真实契约把 props 标注为可选，不改上游一个字。
import type { ComponentType, HTMLAttributes, JSX, ReactNode } from 'react';

import AnimatedContentRaw from './AnimatedContent/AnimatedContent.jsx';
import ClickSparkRaw from './ClickSpark/ClickSpark.jsx';
import MagnetRaw from './Magnet/Magnet.jsx';
import SpotlightCardRaw from './SpotlightCard/SpotlightCard.jsx';
import SplitTextRaw from './SplitText/SplitText.jsx';

export type SplitTextProps = {
  text: string;
  className?: string;
  delay?: number;
  duration?: number;
  ease?: string;
  splitType?: string;
  from?: Record<string, number>;
  to?: Record<string, number>;
  threshold?: number;
  rootMargin?: string;
  textAlign?: 'left' | 'center' | 'right';
  tag?: keyof JSX.IntrinsicElements;
  onLetterAnimationComplete?: () => void;
};

export type CountUpProps = {
  to: number;
  from?: number;
  direction?: 'up' | 'down';
  delay?: number;
  duration?: number;
  className?: string;
  startWhen?: boolean;
  separator?: string;
  onStart?: () => void;
  onEnd?: () => void;
};

export type ShinyTextProps = {
  text: string;
  disabled?: boolean;
  speed?: number;
  className?: string;
  color?: string;
  shineColor?: string;
  spread?: number;
  yoyo?: boolean;
  pauseOnHover?: boolean;
  direction?: 'left' | 'right';
  delay?: number;
};

export type MagnetProps = Omit<HTMLAttributes<HTMLDivElement>, 'children'> & {
  children: ReactNode;
  padding?: number;
  disabled?: boolean;
  magnetStrength?: number;
  activeTransition?: string;
  inactiveTransition?: string;
  wrapperClassName?: string;
  innerClassName?: string;
};

export type ClickSparkProps = {
  children: ReactNode;
  sparkColor?: string;
  sparkSize?: number;
  sparkRadius?: number;
  sparkCount?: number;
  duration?: number;
  easing?: string;
  extraScale?: number;
};

export type AuroraProps = {
  colorStops?: [string, string, string] | string[];
  amplitude?: number;
  blend?: number;
  speed?: number;
  lightMode?: boolean;
};

export type SpotlightCardProps = HTMLAttributes<HTMLDivElement> & {
  children: ReactNode;
  className?: string;
  spotlightColor?: string;
};

export type AnimatedContentProps = Omit<HTMLAttributes<HTMLDivElement>, 'children'> & {
  children: ReactNode;
  container?: HTMLElement | null;
  distance?: number;
  direction?: 'vertical' | 'horizontal';
  reverse?: boolean;
  duration?: number;
  ease?: string;
  initialOpacity?: number;
  animateOpacity?: boolean;
  scale?: number;
  threshold?: number;
  delay?: number;
  onComplete?: () => void;
};

export const SplitText = SplitTextRaw as ComponentType<SplitTextProps>;
export const Magnet = MagnetRaw as ComponentType<MagnetProps>;
export const ClickSpark = ClickSparkRaw as ComponentType<ClickSparkProps>;
export const SpotlightCard = SpotlightCardRaw as ComponentType<SpotlightCardProps>;
export const AnimatedContent = AnimatedContentRaw as ComponentType<AnimatedContentProps>;

// 未用到的上游组件仍留在本目录里，但不从这里导出：它们各自 import 了 CSS，
// 属于副作用，一旦出现在出口就无法被 tree-shake，会把 motion 与多余样式拖进首屏。
// 需要时再加导出与类型标注。

// CountUp / ShinyText（依赖 motion）与 Aurora（依赖 ogl）刻意不从这出口引出：
// 前两者由 ui/Counter.tsx（rAF）与 ui/Shine.tsx（CSS 渐变）等价代劳，
// Aurora 在 Hero 里动态导入，
// 两者都只为把体积挪出首屏关键路径。源码仍在各自子目录，需要时再引。
