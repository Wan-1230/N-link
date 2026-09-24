import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { zh } from './zh';
import type { Dict } from './zh';
import { en } from './en';

export type Lang = 'zh' | 'en';

const STORAGE_KEY = 'nl-lang';

const dicts: Record<Lang, Dict> = { zh, en };

function readInitialLang(): Lang {
  const stored = typeof localStorage !== 'undefined' ? localStorage.getItem(STORAGE_KEY) : null;
  return stored === 'en' ? 'en' : 'zh';
}

export function fill(template: string, vars: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (whole, key: string) =>
    key in vars ? String(vars[key]) : whole
  );
}

type LangApi = {
  lang: Lang;
  dict: Dict;
  setLang: (lang: Lang) => void;
  toggle: () => void;
};

const LangContext = createContext<LangApi | null>(null);

export function LangProvider({ children }: { children: ReactNode }) {
  const [lang, setLang] = useState<Lang>(readInitialLang);

  useEffect(() => {
    document.documentElement.lang = lang;
    // document.title 归 router 按路由维护（路由元数据与语言无关），这里不抢
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      /* 隐私模式下写入失败可忽略，语言仍在本页生效 */
    }
  }, [lang]);

  const toggle = useCallback(() => {
    // 切换语言时保持当前锚点，否则用户会被弹回页首
    const hash = window.location.hash;
    setLang((prev) => (prev === 'zh' ? 'en' : 'zh'));
    if (hash) requestAnimationFrame(() => window.scrollTo(0, 0));
  }, []);

  const value = useMemo<LangApi>(
    () => ({ lang, dict: dicts[lang], setLang, toggle }),
    [lang, toggle]
  );

  return <LangContext.Provider value={value}>{children}</LangContext.Provider>;
}

export function useLang(): LangApi {
  const ctx = useContext(LangContext);
  if (!ctx) throw new Error('useLang must be used inside LangProvider');
  return ctx;
}

export function useDict(): Dict {
  return useLang().dict;
}
