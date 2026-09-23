import { useState } from 'react';
import { useDict } from '../../i18n';

export function CopyLine({ text }: { text: string }) {
  const dict = useDict();
  const [done, setDone] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
    }
    setDone(true);
    setTimeout(() => setDone(false), 1600);
  };

  return (
    <p className="copyable">
      <code>{text}</code>
      <button type="button" onClick={copy} aria-live="polite">
        {done ? dict.cta.copied : dict.cta.copy}
      </button>
    </p>
  );
}
