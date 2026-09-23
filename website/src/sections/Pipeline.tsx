import { useEffect, useRef, useState } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { useDict } from '../i18n';
import { useReducedMotion } from '../hooks/useReducedMotion';
import './sections.css';
import './Pipeline.css';

gsap.registerPlugin(ScrollTrigger);

const WIDE = '(min-width: 641px)';

export function Pipeline() {
  const dict = useDict();
  const reduced = useReducedMotion();
  const sectionRef = useRef<HTMLElement>(null);
  const trackRef = useRef<HTMLOListElement>(null);
  const [wide, setWide] = useState(() => matchMedia(WIDE).matches);

  useEffect(() => {
    const mq = matchMedia(WIDE);
    const onChange = () => setWide(mq.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // 小屏与 reduced-motion 走原生横向滑动：钉住式横滚在触摸设备上容易抖
  useEffect(() => {
    if (reduced || !wide) return;
    const section = sectionRef.current;
    const track = trackRef.current;
    if (!section || !track) return;

    const distance = () => Math.max(0, track.scrollWidth - window.innerWidth + 64);

    const tween = gsap.to(track, {
      x: () => -distance(),
      ease: 'none',
      scrollTrigger: {
        trigger: section,
        start: 'top top',
        end: () => `+=${distance()}`,
        pin: true,
        scrub: 0.6,
        anticipatePin: 1,
        invalidateOnRefresh: true,
      },
    });

    return () => {
      tween.scrollTrigger?.kill();
      tween.kill();
    };
  }, [reduced, wide]);

  return (
    <section className="pl" id="pipeline" ref={sectionRef} aria-label={dict.pipeline.title}>
      <div className="pl__head shell">
        <p className="eyebrow">{dict.pipeline.eyebrow}</p>
        <h2>{dict.pipeline.title}</h2>
        <p className="lede">{dict.pipeline.lede}</p>
      </div>

      <div className="pl__viewport">
        <ol className="pl__track" ref={trackRef}>
          {dict.pipeline.steps.map((s, i) => (
            <li key={s.name} className="pl__step">
              <span className="pl__idx plate">{String(i + 1).padStart(2, '0')}</span>
              <h3>{s.name}</h3>
              <p>{s.desc}</p>
              {i < dict.pipeline.steps.length - 1 && <span className="pl__arrow" aria-hidden="true">→</span>}
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
