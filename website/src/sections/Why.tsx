import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import './sections.css';
import './Why.css';

export function Why() {
  const dict = useDict();

  return (
    <section className="section wh" id="why">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.why.eyebrow}</p>
          <h2>{dict.why.title}</h2>
          <p className="lede">{dict.why.lede}</p>
        </div>

        <ul className="wh__pillars">
          {dict.why.pillars.map((p) => (
            <li key={p.k}>
              <GlassCard className="wh__pillar" spotlight>
                <span className="wh__k plate" aria-hidden="true">
                  {p.k}
                </span>
                <h3>{p.title}</h3>
                <p>{p.desc}</p>
              </GlassCard>
            </li>
          ))}
        </ul>

        <GlassCard className="wh__diff" readable>
          <h3 className="wh__diff-title">{dict.why.diffTitle}</h3>
          <dl className="wh__dl">
            {dict.why.diff.map((d) => (
              <div key={d.k} className="wh__row">
                <dt>{d.k}</dt>
                <dd>{d.v}</dd>
              </div>
            ))}
          </dl>
          <p className="wh__note">{dict.why.diffNote}</p>
        </GlassCard>
      </div>
    </section>
  );
}
