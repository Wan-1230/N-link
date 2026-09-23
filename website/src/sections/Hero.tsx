import { Suspense, lazy } from 'react';
import { Magnet, SplitText } from '../components/reactbits';
import { Counter } from '../components/ui/Counter';
import { Shine } from '../components/ui/Shine';
import { PhoneMock } from '../components/ui/PhoneMock';
import { fill, useDict, useLang } from '../i18n';
import { useReducedMotion } from '../hooks/useReducedMotion';
import { useReleases } from '../hooks/useReleases';
import { formatDate } from '../lib/format';
import { latestEntry } from '../lib/release-feed';
import { REPO } from '../lib/site';
import './Hero.css';

// WebGL 背景不在关键路径上：动态导入把 ogl 挪出首屏 chunk
const Aurora = lazy(() => import('../components/reactbits/Aurora/Aurora.jsx'));

const AURORA_STOPS = ['#c9b400', '#141a22', '#2f5d7c'];

export function Hero() {
  const dict = useDict();
  const { lang } = useLang();
  const reduced = useReducedMotion();
  const { feed } = useReleases();

  const latest = latestEntry(feed);
  const version = latest?.version ?? feed.latestTag.replace(/^v/, '');
  const apkHref = latest?.apkUrl ?? REPO.latest;

  const title = (
    <>
      <span className="hero__line">{dict.hero.titleA}</span>
      <span className="hero__line hero__line--accent">{dict.hero.titleB}</span>
    </>
  );

  return (
    <section className="hero" id="top">
      <div className="hero__bg" aria-hidden="true">
        {reduced ? (
          <div className="hero__bg-static" />
        ) : (
          <Suspense fallback={<div className="hero__bg-static" />}>
            <Aurora colorStops={AURORA_STOPS} amplitude={0.9} blend={0.6} />
          </Suspense>
        )}
        <div className="hero__veil" />
      </div>

      <div className="hero__inner shell">
        <div className="hero__copy">
          <p className="hero__badge glass">
            <Shine text={`v${version} · ${dict.cta.latest}`} />
            <span className="hero__badge-sep" />
            <span className="hero__badge-note">{dict.hero.badge}</span>
          </p>

          <h1 className="hero__title">
            {reduced ? (
              title
            ) : (
              <>
                <SplitText text={dict.hero.titleA} tag="span" className="hero__line" duration={1.1} delay={40} />
                <SplitText
                  text={dict.hero.titleB}
                  tag="span"
                  className="hero__line hero__line--accent"
                  duration={1.1}
                  delay={140}
                />
              </>
            )}
          </h1>

          <p className="hero__lede">{dict.hero.subtitle}</p>

          <div className="hero__cta">
            <Magnet padding={80} magnetStrength={3}>
              <a className="btn btn--primary" href={apkHref}>
                {fill(dict.hero.primary, { version })}
              </a>
            </Magnet>
            <Magnet padding={80} magnetStrength={4}>
              <a className="btn btn--ghost" href="#changelog">
                {dict.hero.secondary}
              </a>
            </Magnet>
            <Magnet padding={80} magnetStrength={5}>
              <a className="btn btn--quiet" href={REPO.url} target="_blank" rel="noreferrer">
                {dict.cta.github}
              </a>
            </Magnet>
          </div>

          <dl className="hero__stats">
            <div>
              <dt>{dict.hero.statVersion}</dt>
              <dd className="plate">v{version}</dd>
            </div>
            <div>
              <dt>{dict.hero.statUpdated}</dt>
              <dd className="plate">{formatDate(latest?.date ?? '', lang)}</dd>
            </div>
            <div>
              <dt>{dict.hero.statStars}</dt>
              <dd className="plate">{feed.stars == null ? '—' : <Counter to={feed.stars} />}</dd>
            </div>
          </dl>
        </div>

        <div className="hero__stage">
          <PhoneMock version={version} />
        </div>
      </div>

      <p className="hero__hint" aria-hidden="true">
        {dict.hero.scrollHint}
        <span className="hero__hint-line" />
      </p>
    </section>
  );
}
