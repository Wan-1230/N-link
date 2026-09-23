import { useEffect } from 'react';
import Lenis from 'lenis';
import { ClickSpark } from './components/reactbits';
import { Nav } from './sections/Nav';
import { Hero } from './sections/Hero';
import { Trust } from './sections/Trust';
import { Pipeline } from './sections/Pipeline';
import { Features } from './sections/Features';
import { Why } from './sections/Why';
import { How } from './sections/How';
import { Changelog } from './sections/Changelog';
import { Roadmap } from './sections/Roadmap';
import { Tech } from './sections/Tech';
import { Download } from './sections/Download';
import { Footer } from './sections/Footer';
import { LangProvider, useDict } from './i18n';
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
  const dict = useDict();
  const reduced = useReducedMotion();

  return (
    <>
      <SmoothScroll />
      <a className="skip-link" href="#main">
        {dict.skip}
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
          <Trust />
          <Pipeline />
          <Features />
          <Why />
          <How />
          <Changelog />
          <Roadmap />
          <Tech />
          <Download />
        </main>
        <Footer />
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
