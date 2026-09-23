import { useState } from 'react';
import { GlassCard } from '../components/ui/GlassCard';
import { CopyLine } from '../components/ui/CopyLine';
import { useDict } from '../i18n';
import { REPO } from '../lib/site';
import './sections.css';
import './How.css';

export function How() {
  const dict = useDict();
  const [tab, setTab] = useState(dict.how.tabs[0].id);

  return (
    <section className="section hw" id="how">
      <div className="shell">
        <div className="sec-head">
          <p className="eyebrow">{dict.how.eyebrow}</p>
          <h2>{dict.how.title}</h2>
          <p className="lede">{dict.how.lede}</p>
        </div>

        <div className="hw__tabs" role="tablist" aria-label={dict.how.title}>
          {dict.how.tabs.map((t) => (
            <button
              key={t.id}
              type="button"
              role="tab"
              id={`tab-${t.id}`}
              aria-selected={tab === t.id}
              aria-controls={`panel-${t.id}`}
              className={`hw__tab${tab === t.id ? ' hw__tab--on' : ''}`}
              onClick={() => setTab(t.id)}
            >
              {t.label}
            </button>
          ))}
        </div>

        {tab === 'user' && (
          <div className="hw__panel" role="tabpanel" id="panel-user" aria-labelledby="tab-user">
            <p className="hw__intro">{dict.how.user.intro}</p>
            <ol className="hw__quick">
              {dict.how.user.steps.map((s, i) => (
                <li key={s.t} className="hw__qitem">
                  <span className="hw__qidx plate" aria-hidden="true">
                    {i + 1}
                  </span>
                  <div>
                    <h3>{s.t}</h3>
                    <p>{s.d}</p>
                  </div>
                </li>
              ))}
            </ol>
            <p className="hw__note">
              {dict.how.user.note}{' '}
              <a href="#download">{dict.how.user.cta}</a>
            </p>
          </div>
        )}

        {tab === 'sta' && (
          <div className="hw__panel" role="tabpanel" id="panel-sta" aria-labelledby="tab-sta">
            <GlassCard className="hw__warn" readable>
              <h3>{dict.how.sta.warnTitle}</h3>
              <p>{dict.how.sta.warnBody}</p>
            </GlassCard>

            <ol className="hw__steps">
              {dict.how.sta.steps.map((s, i) => (
                <li key={s.title} className="hw__step">
                  <span className="hw__step-idx plate" aria-hidden="true">
                    {i + 1}
                  </span>
                  <div>
                    <h3>{s.title}</h3>
                    <ul className="bullets">
                      {s.items.map((it) => (
                        <li key={it}>{it}</li>
                      ))}
                    </ul>
                    <p className="hw__tip">{s.tip}</p>
                  </div>
                </li>
              ))}
            </ol>

            <GlassCard className="hw__fail" readable>
              <div className="tbl-wrap">
                <table className="tbl">
                  <caption>{dict.how.sta.failTitle}</caption>
                  <thead>
                    <tr>
                      {dict.how.sta.failHead.map((h) => (
                        <th key={h} scope="col">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {dict.how.sta.fail.map((f) => (
                      <tr key={f.s}>
                        <td>{f.s}</td>
                        <td>{f.c}</td>
                        <td>{f.a}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </GlassCard>
          </div>
        )}

        {tab === 'dev' && (
          <div className="hw__panel" role="tabpanel" id="panel-dev" aria-labelledby="tab-dev">
            <div className="grid grid--2">
              <GlassCard className="card">
                <h3>{dict.how.dev.reqTitle}</h3>
                <ul className="bullets">
                  {dict.how.dev.req.map((r) => (
                    <li key={r}>{r}</li>
                  ))}
                </ul>
              </GlassCard>
              <GlassCard className="card">
                <h3>{dict.how.dev.testTitle}</h3>
                <CopyLine text="./gradlew :app:testDebugUnitTest" />
                <p className="hw__ch-desc">{dict.how.dev.testDesc}</p>
              </GlassCard>
            </div>

            <GlassCard className="hw__build">
              <h3>{dict.how.dev.buildTitle}</h3>
              <CopyLine text={`git clone ${REPO.url}.git && cd N-Link && ./gradlew :app:assembleDebug`} />
              <p className="hw__out">
                <span>{dict.how.dev.outLabel}</span> <code className="plate">app/build/outputs/apk/debug/app-debug.apk</code>
              </p>
            </GlassCard>

            <GlassCard className="hw__tools">
              <h3>{dict.how.dev.toolsTitle}</h3>
              {dict.how.dev.tools.map((t) => (
                <div key={t.cmd} className="hw__tool">
                  <CopyLine text={t.cmd} />
                  <p className="hw__ch-desc">{t.desc}</p>
                </div>
              ))}
            </GlassCard>
          </div>
        )}
      </div>
    </section>
  );
}
