import { useDict, useLang } from '../i18n';
import { Link } from '../lib/router';
import { formatDateTime } from '../lib/format';
import { useReleases } from '../hooks/useReleases';
import { REPO } from '../lib/site';
import './Footer.css';

export function Footer() {
  const dict = useDict();
  const { lang } = useLang();
  const { feed } = useReleases();

  const product = [
    { anchor: 'features', label: dict.nav.items.features.label },
    { anchor: 'why', label: dict.nav.items.why.label },
    { anchor: 'how', label: dict.nav.items.how.label },
    { anchor: 'tech', label: dict.nav.items.tech.label },
  ];

  const project = [
    { href: REPO.url, label: dict.footer.source },
    { href: REPO.issues, label: dict.footer.issues },
    { href: null, label: dict.nav.releases },
    { href: REPO.readme, label: 'README' },
  ];

  return (
    <footer className="ft2">
      <div className="shell">
        <div className="ft2__cols">
          <div className="ft2__brand">
            <a className="ft2__logo" href="#top">
              <span className="ft2__mark" aria-hidden="true" />
              N-Link
            </a>
            <p className="ft2__tag">{dict.hero.badge}</p>
            <p className="ft2__sync">{dict.footer.syncNote.replace('{time}', formatDateTime(feed.generatedAt, lang))}</p>
          </div>

          <nav className="ft2__col" aria-label={dict.footer.cols.product}>
            <h3>{dict.footer.cols.product}</h3>
            <ul>
              {product.map((l) => (
                <li key={l.anchor}>
                  <Link to="/" anchor={l.anchor}>
                    {l.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>

          <nav className="ft2__col" aria-label={dict.footer.cols.project}>
            <h3>{dict.footer.cols.project}</h3>
            <ul>
              {project.map((l) => (
                <li key={l.label}>
                  {l.href ? (
                    <a href={l.href} target="_blank" rel="noreferrer">
                      {l.label}
                    </a>
                  ) : (
                    <Link to="/releases" anchor="latest">
                      {l.label}
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          </nav>
        </div>

        <div className="ft2__legal">
          <p>{dict.footer.unofficial}</p>
          <p>{dict.footer.license}</p>
          <p>{dict.footer.reactbits}</p>
        </div>

        <div className="ft2__end">
          <span className="plate">© {new Date().getFullYear()} N-Link</span>
          <a className="ft2__top" href="#top">
            {dict.footer.backToTop} ↑
          </a>
        </div>
      </div>
    </footer>
  );
}
