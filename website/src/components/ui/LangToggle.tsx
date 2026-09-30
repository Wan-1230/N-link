import { useDict, useLang } from '../../i18n';

export function LangToggle() {
  const { toggle } = useLang();
  const dict = useDict();

  return (
    <button
      type="button"
      className="nav__lang glass glass--strong"
      onClick={toggle}
      aria-label={dict.nav.langLabel === 'English' ? 'Switch to English' : '切换为中文'}
    >
      {dict.nav.langLabel}
    </button>
  );
}
