import { GlassCard } from '../components/ui/GlassCard';
import { useDict } from '../i18n';
import './sections.css';
import './Features.css';

export function Features() {
  const dict = useDict();

  return (
    <section className="section ft" id="features">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.features.eyebrow}</p>
          <h2>{dict.features.title}</h2>
          <p className="lede">{dict.features.lede}</p>
        </div>

        <GlassCard className="ft__tbl" readable>
          <div className="tbl-wrap">
            <table className="tbl">
              <caption>{dict.features.channelsCaption}</caption>
              <thead>
                <tr>
                  {dict.features.channelsHead.map((h) => (
                    <th key={h} scope="col">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {dict.features.channels.map((c) => (
                  <tr key={c.name}>
                    <td className="plate">{c.name}</td>
                    <td>{c.role}</td>
                    <td>{c.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>

        <div className="grid grid--2 ft__groups">
          {dict.features.groups.map((g) => (
            <GlassCard key={g.title} className="card" spotlight>
              <h3>{g.title}</h3>
              <ul className="bullets">
                {g.items.map((i) => (
                  <li key={i}>{i}</li>
                ))}
              </ul>
            </GlassCard>
          ))}
        </div>
      </div>
    </section>
  );
}
