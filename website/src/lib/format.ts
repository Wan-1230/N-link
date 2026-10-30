import type { Lang } from '../i18n';

const LOCALES: Record<Lang, string> = { zh: 'zh-CN', en: 'en-US' };

export function formatDate(iso: string, lang: Lang): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(LOCALES[lang], {
    year: 'numeric',
    month: lang === 'zh' ? '2-digit' : 'short',
    day: '2-digit',
  }).format(date);
}

export function formatDateTime(iso: string, lang: Lang): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat(LOCALES[lang], {
    month: lang === 'zh' ? '2-digit' : 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

export function formatBytes(bytes: number | null): string {
  if (!bytes || bytes <= 0) return '';
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1)} MB`;
}

export function formatCount(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}
