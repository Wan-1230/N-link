import { useEffect, useState } from 'react';
import { LangToggle } from '../components/ui/LangToggle';
import { useDict } from '../i18n';
import { NAV_IDS, REPO } from '../lib/site';
import { formatCount } from '../lib/format';
import { useReleases } from '../hooks/useReleases';
import './Nav.css';

export function Nav() {
  const dict = useDict();
  const { feed } = useReleases();
  const [scrolled, setScrolled] = useState(false);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 24);
    onScroll();
    addEventListener('scroll', onScroll, { passive: true });
    return () => removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    addEventListener('keydown', onKey);
    return () => removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <header className={`nav${scrolled ? ' nav--scrolled' : ''}`}>
      <div className="nav__inner shell">
        <a className="nav__brand" href="#top" onClick={() => setOpen(false)}>
          <span className="nav__mark" aria-hidden="true" />
          N-Link
        </a>

        <nav className="nav__links" aria-label="N-Link">
          {NAV_IDS.map((id) => (
            <a key={id} className="nav__link" href={`#${id}`} onClick={() => setOpen(false)}>
              {dict.nav.sections[id]}
            </a>
          ))}
        </nav>

        <div className="nav__actions">
          <LangToggle />
          <a className="nav__gh" href={REPO.url} target="_blank" rel="noreferrer">
            <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" fill="currentColor">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.04-2.12 0 0 .67-.21 2.2.82a7.4 7.4 0 0 1 2-.27c.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.4 1.1.12 1.92.06 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
            </svg>
            <span className="plate">{feed.stars == null ? 'GitHub' : formatCount(feed.stars)}</span>
          </a>
          <a className="nav__cta" href={REPO.latest}>
            {dict.cta.download}
          </a>
          <button
            type="button"
            className="nav__burger"
            aria-expanded={open}
            aria-label={open ? dict.nav.close : dict.nav.open}
            onClick={() => setOpen((v) => !v)}
          >
            <span />
            <span />
          </button>
        </div>
      </div>

      {open && (
        <nav className="nav__mobile glass glass--strong" aria-label="N-Link">
          {NAV_IDS.map((id) => (
            <a key={id} href={`#${id}`} onClick={() => setOpen(false)}>
              {dict.nav.sections[id]}
            </a>
          ))}
        </nav>
      )}
    </header>
  );
}
