import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import { REPO } from '../lib/site';
import './Support.css';

export function Support() {
  const dict = useDict();

  return (
    <section className="section sp">
      <div className="shell">
        <div className="sp__grid">
          <GlassCard className="card">
            <h3>{dict.download.feedbackTitle}</h3>
            <p className="sp__desc">{dict.download.feedbackDesc}</p>
            <div className="sp__actions">
              <a className="sp__btn" href={REPO.issues} target="_blank" rel="noreferrer">
                {dict.download.issues}
              </a>
              <span className="sp__inapp">{dict.download.inapp}</span>
            </div>
          </GlassCard>

          <GlassCard className="card sp__donate">
            <div>
              <h3>{dict.download.donateTitle}</h3>
              <p className="sp__desc">{dict.download.donateDesc}</p>
            </div>
            <img
              className="sp__qr glass"
              src="/donate-wechat-qr.png"
              alt={dict.download.donateAlt}
              width={132}
              height={132}
              loading="lazy"
            />
          </GlassCard>
        </div>
      </div>
    </section>
  );
}
