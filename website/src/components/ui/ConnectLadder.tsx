import { useDict } from '../../i18n';
import './ConnectLadder.css';

/**
 * 首屏右侧的连接时序。原先这里是一个手绘的手机外壳加假界面（已删）：
 * 画出来的 UI 会被认出来是假的，连带旁边所有真数字一起掉信用，而仓库里没有真机截图。
 * 换成三条连接方式的步骤与耗时，每个数字都能追到版本说明。
 */
export function ConnectLadder() {
  const dict = useDict();

  return (
    <div className="ladder">
      <p className="ladder__caption">{dict.hero.ladderCaption}</p>
      <ul className="ladder__list">
        {dict.hero.modes.map((m) => (
          <li key={m.name} className="ladder__row">
            <div className="ladder__head">
              <p className="ladder__name">{m.name}</p>
              <p className="ladder__time">{m.time}</p>
            </div>
            <ol className="ladder__steps">
              {m.steps.map((s) => (
                <li key={s}>{s}</li>
              ))}
            </ol>
            <p className="ladder__note">{m.note}</p>
          </li>
        ))}
      </ul>
    </div>
  );
}
