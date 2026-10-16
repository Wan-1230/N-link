import './PhoneMock.css';

const CHIPS = ['WiFi-AP', 'WiFi-STA', 'USB'];
const DOCK_ITEMS = 5;

export function PhoneMock({ version }: { version: string }) {
  return (
    <div className="mock" role="img" aria-label={`N-Link 界面示意：实时监看画面、合焦框、曝光参数与悬浮操作坞，版本 ${version}`}>
      <div className="mock__screen">
        <div className="mock__bar">
          <span>N-Link {version}</span>
          <span className="mock__dot" aria-hidden="true" />
          <span>Z 8 · 已连接</span>
        </div>

        <div className="mock__chips">
          {CHIPS.map((c, i) => (
            <span key={c} className={i === 0 ? 'mock__chip mock__chip--on' : 'mock__chip'}>
              {c}
            </span>
          ))}
        </div>

        <div className="mock__stage">
          <div className="mock__grid" aria-hidden="true" />
          <div className="mock__af" aria-hidden="true" />
          <div className="mock__hud">
            <span>1/250</span>
            <span>f/2.8</span>
            <span>ISO 800</span>
            <span>AF-C</span>
          </div>
        </div>

        <div className="mock__shutter">
          <span className="mock__mini" aria-hidden="true" />
          <span className="mock__shutter-ring" aria-hidden="true">
            <span className="mock__shutter-core" />
          </span>
          <span className="mock__mini" aria-hidden="true" />
        </div>

        <div className="mock__dock" aria-hidden="true">
          {Array.from({ length: DOCK_ITEMS }, (_, i) => (
            <span key={i} className={i === 2 ? 'mock__dock-item mock__dock-item--on' : 'mock__dock-item'} />
          ))}
        </div>
      </div>
    </div>
  );
}
