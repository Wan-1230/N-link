import { Counter } from '../components/ui/Counter';
import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import { DEVICE_MODELS } from '../lib/site';
import './sections.css';
import './Trust.css';

export function Trust() {
  const dict = useDict();

  return (
    <section className="section tr" id="trust">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.trust.eyebrow}</p>
          <h2>{dict.trust.title}</h2>
          <p className="lede">{dict.trust.lede}</p>
        </div>

        <div className="tr__models">
          <span className="tr__models-label">{dict.trust.modelsLabel}</span>
          <div className="chips">
            {DEVICE_MODELS.map((m) => (
              <span key={m} className="chip chip--accent">
                {m}
              </span>
            ))}
          </div>
          <p className="tr__models-note">{dict.trust.modelsNote}</p>
        </div>

        <ul className="tr__metrics">
          {dict.trust.metrics.map((m) => (
            <li key={m.label}>
              <GlassCard className="tr__metric">
                <p className="tr__num plate">
                  <Counter to={m.value} prefix={m.prefix} suffix={m.suffix} />
                </p>
                <p className="tr__label">{m.label}</p>
                <p className="tr__note">{m.note}</p>
              </GlassCard>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
