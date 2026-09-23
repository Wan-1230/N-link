import type { HTMLAttributes, ReactNode } from 'react';
import { SpotlightCard } from '../reactbits';

type Props = HTMLAttributes<HTMLDivElement> & {
  children: ReactNode;
  spotlight?: boolean;
  readable?: boolean;
  strong?: boolean;
};

export function GlassCard({ children, spotlight = false, readable = false, strong = false, className = '', ...rest }: Props) {
  const cls = [
    'glass',
    strong && 'glass--strong',
    readable && 'glass--readable',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  if (!spotlight) {
    return (
      <div className={cls} {...rest}>
        {children}
      </div>
    );
  }

  return (
    <SpotlightCard className={cls} spotlightColor="rgba(255, 225, 0, 0.13)">
      {children}
    </SpotlightCard>
  );
}
