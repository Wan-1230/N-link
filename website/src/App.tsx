import { useEffect } from 'react';
import Lenis from 'lenis';
import { ClickSpark } from './components/reactbits';
import { Nav } from './sections/Nav';
import { Footer } from './sections/Footer';
import { Home } from './pages/Home';
import { Releases } from './pages/Releases';
import { LangProvider, useDict } from './i18n';
import { RouterProvider, useRouter } from './lib/router';
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
  const { path } = useRouter();

  return (
    <>
      <SmoothScroll />
      <a className="skip-link" href="#main">
        {dict.skip}
      </a>
      <ClickSpark sparkColor="#ffe100" sparkSize={11} sparkRadius={17} sparkCount={reduced ? 0 : 6} duration={420}>
        <Nav />
        {/* key 让换页时重挂载，淡入才会真的重播；否则只是同一棵树换个内容，看不出过渡 */}
        <main id="main" className="route" key={path}>
          {path === '/' ? <Home /> : <Releases />}
        </main>
        <Footer />
      </ClickSpark>
    </>
  );
}

export default function App() {
  return (
    <LangProvider>
      <RouterProvider>
        <Shell />
      </RouterProvider>
    </LangProvider>
  );
}
