import { GlassCard } from '../components/ui/GlassCard';
import { Shine } from '../components/ui/Shine';
import { useDict, useLang } from '../i18n';
import { useReducedMotion } from '../hooks/useReducedMotion';
import { useReleases } from '../hooks/useReleases';
import { formatBytes, formatDate } from '../lib/format';
import { latestEntry } from '../lib/release-feed';
import { rich } from '../lib/rich';
import { CHANNELS, REPO } from '../lib/site';
import './LatestRelease.css';

export function LatestRelease() {
  const dict = useDict();
  const { lang } = useLang();
  const reduced = useReducedMotion();
  const { feed } = useReleases();

  const latest = latestEntry(feed);
  const version = latest?.version ?? '';
  const size = formatBytes(latest?.apkSize ?? null);

  const channels = [
    { name: dict.download.channels[0].name, desc: dict.download.channels[0].desc, href: latest?.apkUrl ?? REPO.latest, primary: true },
    { name: dict.download.channels[1].name, desc: dict.download.channels[1].desc, href: latest?.channels.quark ?? CHANNELS.quark },
    { name: dict.download.channels[2].name, desc: dict.download.channels[2].desc, href: latest?.channels.baidu ?? CHANNELS.baidu },
    { name: dict.download.channels[3].name, desc: dict.download.channels[3].desc, href: REPO.latest },
  ];

  return (
    <section className="lr" id="latest">
      <div className="shell">
        <p className="eyebrow">{dict.releasesPage.eyebrow}</p>
        <h1 className="lr__h1">{dict.releasesPage.title}</h1>
        <p className="lede">{dict.releasesPage.lede}</p>

        <GlassCard className="lr__card" strong>
          <div className="lr__head">
            <div className="lr__ver">
              <span className="lr__label">{dict.releasesPage.latestBadge}</span>
              <Shine text={`v${version || '—'}`} speed={4} />
            </div>
            <div className="lr__meta">
              {size && <span className="plate lr__size">{size}</span>}
              <span className="plate lr__date">{formatDate(latest?.date ?? '', lang)}</span>
            </div>
          </div>

          {latest?.title && <p className="lr__title">{latest.title}</p>}

          {latest?.bullets.length ? (
            <ul className="lr__bullets">
              {(reduced ? latest.bullets : latest.bullets.slice(0, 6)).map((b) => (
                <li key={b}>{rich(b)}</li>
              ))}
            </ul>
          ) : null}

          <div className="lr__grid">
            {channels.map((c) => (
              <a key={c.name} className={`lr__ch${c.primary ? ' lr__ch--primary' : ''}`} href={c.href} target="_blank" rel="noreferrer">
                <span className="lr__ch-name">{c.name}</span>
                <span className="lr__ch-desc">{c.desc}</span>
              </a>
            ))}
          </div>
        </GlassCard>
      </div>
    </section>
  );
}
