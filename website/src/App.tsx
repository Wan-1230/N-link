import { useEffect } from 'react';
import Lenis from 'lenis';
import { ClickSpark } from './components/reactbits';
import { Nav } from './sections/Nav';
import { Hero } from './sections/Hero';
import { LangProvider, useLang } from './i18n';
import { useReducedMotion } from './hooks/useReducedMotion';

function SmoothScroll() {
  const reduced = useReducedMotion();

  useEffect(() => {
    if (reduced) return;
    const lenis = new Lenis({ duration: 1.1, smoothWheel: true });
    let raf = 0;
    const loop = (time: number) => {
      lenis.raf(time);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => {
      cancelAnimationFrame(raf);
      lenis.destroy();
    };
  }, [reduced]);

  return null;
}

function Shell() {
  const { lang } = useLang();
  const reduced = useReducedMotion();

  return (
    <>
      <SmoothScroll />
      <a className="skip-link" href="#main">
        {lang === 'zh' ? '跳到主要内容' : 'Skip to content'}
      </a>
      <ClickSpark
        sparkColor="#ffe100"
        sparkSize={11}
        sparkRadius={17}
        sparkCount={reduced ? 0 : 6}
        duration={420}
      >
        <Nav />
        <main id="main">
          <Hero />
        </main>
      </ClickSpark>
    </>
  );
}

export default function App() {
  return (
    <LangProvider>
      <Shell />
    </LangProvider>
  );
}
