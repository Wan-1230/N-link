import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { AnchorHTMLAttributes, ReactNode } from 'react';

/**
 * 只有两个页面，不值得为此背 react-router（约 15KB gzip，产物预算只剩 27KB）。
 * 这里只覆盖我们真正需要的：pushState 导航、popstate 回退、站内锚点跨页跳转。
 */

export type Path = '/' | '/releases';

const PATHS: Path[] = ['/', '/releases'];

function normalize(raw: string): Path {
  const p = raw.replace(/index\.html$/, '').replace(/\/+$/, '') || '/';
  return (PATHS.includes(p as Path) ? p : '/') as Path;
}

const current = (): Path => normalize(window.location.pathname);

type RouterApi = {
  path: Path;
  navigate: (to: Path, anchor?: string) => void;
};

const Ctx = createContext<RouterApi | null>(null);

export function RouterProvider({ children }: { children: ReactNode }) {
  const [path, setPath] = useState<Path>(current);

  useEffect(() => {
    const onPop = () => setPath(current());
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const navigate = useCallback<RouterApi['navigate']>((to, anchor) => {
    if (current() !== to) {
      window.history.pushState({ anchor }, '', to === '/' ? '/' : `${to}/`);
      setPath(to);
    }
    // 换页时锚点目标还没挂上，等一帧再滚
    requestAnimationFrame(() => {
      if (anchor) document.getElementById(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      else window.scrollTo({ top: 0 });
    });
  }, []);

  const value = useMemo(() => ({ path, navigate }), [path, navigate]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useRouter(): RouterApi {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useRouter must be used inside RouterProvider');
  return ctx;
}

type LinkProps = Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> & {
  to: Path;
  anchor?: string;
};

export function Link({ to, anchor, onClick, ...rest }: LinkProps) {
  const { navigate } = useRouter();
  const href = to === '/' ? '/' : `${to}/`;

  return (
    <a
      href={anchor ? `${href}#${anchor}` : href}
      onClick={(e) => {
        onClick?.(e);
        if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        navigate(to, anchor);
      }}
      {...rest}
    />
  );
}
