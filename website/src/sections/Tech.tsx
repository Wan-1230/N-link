import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import './sections.css';
import './Tech.css';

export function Tech() {
  const dict = useDict();

  return (
    <section className="section tc" id="tech">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.tech.eyebrow}</p>
          <h2>{dict.tech.title}</h2>
          <p className="lede">{dict.tech.lede}</p>
        </div>

        <div className="tc__cols">
          <GlassCard className="tc__box" readable>
            <h3 className="tc__h">{dict.tech.stackTitle}</h3>
            <dl className="tc__stack">
              {dict.tech.stack.map((s) => (
                <div key={s.cat} className="tc__row">
                  <dt>{s.cat}</dt>
                  <dd className="plate">{s.items}</dd>
                </div>
              ))}
            </dl>
          </GlassCard>

          <div className="tc__side">
            <GlassCard className="tc__box">
              <h3 className="tc__h">{dict.tech.protoTitle}</h3>
              <div className="chips">
                {dict.tech.proto.map((p) => (
                  <span key={p} className="chip">
                    {p}
                  </span>
                ))}
              </div>
            </GlassCard>

            <GlassCard className="tc__box">
              <h3 className="tc__h">{dict.tech.modulesTitle}</h3>
              <ul className="tc__mods">
                {dict.tech.modules.map((m) => (
                  <li key={m.name}>
                    <code className="plate">{m.name}/</code>
                    <span>{m.desc}</span>
                  </li>
                ))}
              </ul>
            </GlassCard>
          </div>
        </div>
      </div>
    </section>
  );
}
