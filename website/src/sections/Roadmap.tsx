import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import './sections.css';
import './Roadmap.css';

export function Roadmap() {
  const dict = useDict();

  return (
    <section className="section rm" id="roadmap">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.roadmap.eyebrow}</p>
          <h2>{dict.roadmap.title}</h2>
          <p className="lede">{dict.roadmap.lede}</p>
        </div>

        <div className="grid grid--2">
          <GlassCard className="card">
            <h3 className="rm__h rm__h--done">{dict.roadmap.doneTitle}</h3>
            <ul className="rm__done">
              {dict.roadmap.done.map((d) => (
                <li key={d}>
                  <span className="rm__tick" aria-hidden="true" />
                  {d}
                </li>
              ))}
            </ul>
          </GlassCard>

          <GlassCard className="card">
            <h3 className="rm__h">{dict.roadmap.todoTitle}</h3>
            <ul className="rm__todo">
              {dict.roadmap.todo.map((t) => (
                <li key={t.t}>
                  <span className="rm__bullet" aria-hidden="true" />
                  <div>
                    <p className="rm__t">{t.t}</p>
                    <p className="rm__s">{t.s}</p>
                  </div>
                </li>
              ))}
            </ul>
          </GlassCard>
        </div>
      </div>
    </section>
  );
}
