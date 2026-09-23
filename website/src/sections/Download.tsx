import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import { useReleases } from '../hooks/useReleases';
import { formatBytes } from '../lib/format';
import { latestEntry } from '../lib/release-feed';
import { CHANNELS, REPO } from '../lib/site';
import './sections.css';
import './Download.css';

export function Download() {
  const dict = useDict();
  const { feed } = useReleases();
  const latest = latestEntry(feed);

  // 第 4 条「应用内检查更新」不是链接，链到 Releases 会误导；它只能是普通卡片
  const hrefs: (string | null)[] = [REPO.latest, CHANNELS.quark, CHANNELS.baidu, null];
  const size = formatBytes(latest?.apkSize ?? null);

  return (
    <section className="section dl" id="download">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.download.eyebrow}</p>
          <h2>{dict.download.title}</h2>
          <p className="lede">{dict.download.lede}</p>
        </div>

        <p className="dl__latest">
          <span>{dict.download.latestIs}</span>
          <strong className="plate">v{latest?.version ?? '—'}</strong>
          {size && <span className="plate dl__size">{size}</span>}
        </p>

        <div className="grid grid--2">
          {dict.download.channels.map((c, i) => {
            const href = hrefs[i];
            const card = (
              <GlassCard className="dl__ch-card" spotlight>
                <h3>{c.name}</h3>
                <p>{c.desc}</p>
                {href && (
                  <span className="dl__ch-go" aria-hidden="true">
                    ↗
                  </span>
                )}
              </GlassCard>
            );

            return href ? (
              <a key={c.name} className="dl__ch" href={href} target="_blank" rel="noreferrer">
                {card}
              </a>
            ) : (
              <div key={c.name} className="dl__ch dl__ch--plain">
                {card}
              </div>
            );
          })}
        </div>

        <div className="dl__support">
          <GlassCard className="card">
            <h3>{dict.download.feedbackTitle}</h3>
            <p className="dl__desc">{dict.download.feedbackDesc}</p>
            <div className="dl__actions">
              <a className="dl__btn" href={REPO.issues} target="_blank" rel="noreferrer">
                {dict.download.issues}
              </a>
              <span className="dl__inapp">{dict.download.inapp}</span>
            </div>
          </GlassCard>

          <GlassCard className="card dl__donate">
            <div>
              <h3>{dict.download.donateTitle}</h3>
              <p className="dl__desc">{dict.download.donateDesc}</p>
            </div>
            <img className="dl__qr glass" src="/donate-wechat-qr.png" alt={dict.download.donateAlt} width={132} height={132} loading="lazy" />
          </GlassCard>
        </div>
      </div>
    </section>
  );
}
