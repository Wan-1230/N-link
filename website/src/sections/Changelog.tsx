import { useState } from 'react';
import type { ReactNode } from 'react';
import { AnimatedContent } from '../components/reactbits';
import { fill, useDict, useLang } from '../i18n';
import { useReleases } from '../hooks/useReleases';
import { formatBytes, formatDate, formatDateTime } from '../lib/format';
import type { ReleaseEntry } from '../lib/release-feed';
import { REPO } from '../lib/site';
import './Changelog.css';

const PREVIEW_BULLETS = 3;

/** Release 正文里的 **粗体** 转成节点，不用 innerHTML。 */
function rich(text: string): ReactNode[] {
  return text.split(/\*\*(.+?)\*\*/g).map((part, i) => (i % 2 === 1 ? <strong key={i}>{part}</strong> : part));
}

function Entry({ entry, isLatest }: { entry: ReleaseEntry; isLatest: boolean }) {
  const dict = useDict();
  const { lang } = useLang();
  const [open, setOpen] = useState(false);

  const collapsed = !isLatest && !open && entry.bullets.length > PREVIEW_BULLETS;
  const shown = collapsed ? entry.bullets.slice(0, PREVIEW_BULLETS) : entry.bullets;
  const size = formatBytes(entry.apkSize);

  return (
    <div className={`cl__item glass${isLatest ? ' cl__item--latest' : ''}`}>
      <span className="cl__dot" aria-hidden="true" />
      <header className="cl__head">
        <h3 className="cl__ver plate">v{entry.version}</h3>
        <span className="cl__date plate">{formatDate(entry.date, lang)}</span>
        {isLatest && <span className="cl__flag">{dict.changelog.latest}</span>}
        {entry.prerelease && <span className="cl__flag cl__flag--pre">{dict.changelog.prerelease}</span>}
      </header>

      {entry.title && <p className="cl__title">{rich(entry.title)}</p>}
      {entry.summary && <p className="cl__summary">{rich(entry.summary)}</p>}

      {shown.length > 0 && (
        <ul className="cl__bullets">
          {shown.map((b) => (
            <li key={b}>{rich(b)}</li>
          ))}
        </ul>
      )}

      {collapsed && (
        <button type="button" className="cl__more" onClick={() => setOpen(true)}>
          {fill(dict.changelog.expand, { n: entry.bullets.length })}
        </button>
      )}
      {!collapsed && !isLatest && entry.bullets.length > PREVIEW_BULLETS && (
        <button type="button" className="cl__more" onClick={() => setOpen(false)}>
          {dict.changelog.collapse}
        </button>
      )}

      <footer className="cl__links">
        {entry.apkUrl ? (
          <a className="cl__link cl__link--apk" href={entry.apkUrl}>
            {dict.changelog.apk}
            {size && <span className="cl__size plate">{size}</span>}
          </a>
        ) : (
          <a className="cl__link" href={entry.url}>
            {dict.cta.viewRelease}
          </a>
        )}
        {entry.channels.quark && (
          <a className="cl__link" href={entry.channels.quark} target="_blank" rel="noreferrer">
            {dict.changelog.channels.quark}
          </a>
        )}
        {entry.channels.baidu && (
          <a className="cl__link" href={entry.channels.baidu} target="_blank" rel="noreferrer">
            {dict.changelog.channels.baidu}
          </a>
        )}
        {entry.url && (
          <a className="cl__link cl__link--quiet" href={entry.url} target="_blank" rel="noreferrer">
            {dict.changelog.viewOnGithub}
          </a>
        )}
      </footer>
    </div>
  );
}

export function Changelog() {
  const dict = useDict();
  const { lang } = useLang();
  const { feed, status, refresh } = useReleases();
  const [busy, setBusy] = useState(false);

  const syncLabel = status === 'live' ? dict.changelog.sourceLive : dict.changelog.sourceSnapshot;

  const onRefresh = async () => {
    setBusy(true);
    await refresh();
    setBusy(false);
  };

  return (
    <section className="section cl" id="changelog">
      <div className="shell">
        <div className="cl__top">
          <div>
            <p className="eyebrow">{dict.changelog.eyebrow}</p>
            <h2>{dict.changelog.title}</h2>
            <p className="lede">{dict.changelog.lede}</p>
          </div>

          <div className="cl__meta">
            <span className={`cl__pill${status === 'live' ? ' cl__pill--live' : ''}`}>
              <span className="cl__pill-dot" aria-hidden="true" />
              {syncLabel}
            </span>
            <span className="cl__time plate">
              {fill(dict.changelog.syncedAt, { time: formatDateTime(feed.generatedAt, lang) })}
            </span>
            <button type="button" className="cl__refresh" onClick={onRefresh} disabled={busy}>
              {busy ? dict.changelog.refreshing : dict.changelog.doRefresh}
            </button>
          </div>
        </div>

        {lang === 'en' && <p className="cl__note">{dict.changelog.langNote}</p>}

        {feed.releases.length === 0 ? (
          <p className="cl__empty">
            {dict.changelog.empty}{' '}
            <a href={REPO.latest} target="_blank" rel="noreferrer">
              {dict.cta.github}
            </a>
          </p>
        ) : (
          <>
            <p className="cl__count plate">{fill(dict.changelog.count, { n: feed.releases.length })}</p>
            <ol className="cl__list">
              {feed.releases.map((entry, i) => (
                <li key={entry.tag} className="cl__row">
                  <AnimatedContent distance={26} duration={0.7} delay={i === 0 ? 0 : 0.05} threshold={0.15}>
                    <Entry entry={entry} isLatest={entry.tag === feed.latestTag} />
                  </AnimatedContent>
                </li>
              ))}
            </ol>
          </>
        )}
      </div>
    </section>
  );
}
