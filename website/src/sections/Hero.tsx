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
import { Link } from '../lib/router';
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
                {/* GSAP SplitText 会缓存首次拆分时的 data-original-text，换 text prop
                    是换不掉已渲染内容的；切语言必须靠 key 重挂载，否则标题冻在首次语言 */}
                <SplitText
                  key={`${lang}-title-a`}
                  text={dict.hero.titleA}
                  tag="span"
                  className="hero__line"
                  duration={1.1}
                  delay={40}
                />
                <SplitText
                  key={`${lang}-title-b`}
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
              <Link className="btn btn--ghost" to="/releases" anchor="changelog">
                {dict.hero.secondary}
              </Link>
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
