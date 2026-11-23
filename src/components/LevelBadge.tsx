import type { Level } from '../lib/difficulty';
import { useT } from '../i18n';

export function LevelBadge({ level, title }: { level: Level; title?: string }) {
  const t = useT();
  const name = t(level === 1 ? 'level1' : level === 2 ? 'level2' : 'level3');
  return (
    <span className={`level level-${level}`} title={title ?? name}>
      <span className="level-bars" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      {name}
    </span>
  );
}
