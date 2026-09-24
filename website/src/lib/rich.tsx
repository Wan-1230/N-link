import type { ReactNode } from 'react';

/** Release 正文里的 **粗体** 转成节点；不走 innerHTML，避免把上游文本当标记解析。 */
export function rich(text: string): ReactNode[] {
  return text.split(/\*\*(.+?)\*\*/g).map((part, i) => (i % 2 === 1 ? <strong key={i}>{part}</strong> : part));
}
