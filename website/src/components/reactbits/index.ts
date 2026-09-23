// React Bits 上游组件的统一出口。源文件逐字拷自上游，出处见 ./SOURCE.json 与 ./LICENSE.upstream.txt。
//
// 上游是 .jsx，TS 从解构默认值推断时会把未给默认值的回调判为必填；实际运行时
// SplitText 用 onCompleteRef.current?.()、CountUp 用 typeof fn === 'function' 保护，
// 所以这里按真实契约把 props 标注为可选，不改上游一个字。
import type { ComponentType, HTMLAttributes, JSX, ReactNode } from 'react';

import AnimatedContentRaw from './AnimatedContent/AnimatedContent.jsx';
import AuroraRaw from './Aurora/Aurora.jsx';
import BlurText from './BlurText/BlurText.jsx';
import ClickSparkRaw from './ClickSpark/ClickSpark.jsx';
import CountUpRaw from './CountUp/CountUp.jsx';
import Dock from './Dock/Dock.jsx';
import GradientText from './GradientText/GradientText.jsx';
import MagnetRaw from './Magnet/Magnet.jsx';
import ShinyTextRaw from './ShinyText/ShinyText.jsx';
import SpotlightCardRaw from './SpotlightCard/SpotlightCard.jsx';
import SplitTextRaw from './SplitText/SplitText.jsx';
import Stepper from './Stepper/Stepper.jsx';
import TiltedCard from './TiltedCard/TiltedCard.jsx';

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
export const CountUp = CountUpRaw as ComponentType<CountUpProps>;
export const ShinyText = ShinyTextRaw as ComponentType<ShinyTextProps>;
export const Magnet = MagnetRaw as ComponentType<MagnetProps>;
export const ClickSpark = ClickSparkRaw as ComponentType<ClickSparkProps>;
export const Aurora = AuroraRaw as ComponentType<AuroraProps>;
export const SpotlightCard = SpotlightCardRaw as ComponentType<SpotlightCardProps>;
export const AnimatedContent = AnimatedContentRaw as ComponentType<AnimatedContentProps>;

// P3 才接上，届时按同一方式补类型标注。
export { BlurText, Dock, GradientText, Stepper, TiltedCard };
