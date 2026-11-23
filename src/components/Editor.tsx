import { useState } from 'react';
import { importText } from '../lib/chordpro';
import { useT } from '../i18n';
import { go } from '../router';
import { saveSong, type Song } from '../store';
import { Sheet } from './SongView';

export function Editor({ song }: { song: Song }) {
  const t = useT();
  const [src, setSrc] = useState(song.chordpro);
  return (
    <div className="page editor">
      <button className="link-btn" onClick={() => history.back()}>
        ← {t('back')}
      </button>
      <h1>{t('edit')}</h1>
      <p className="muted small">{t('editorHelp')}</p>
      <div className="import-grid">
        <textarea
          className="field code"
          value={src}
          onChange={(e) => setSrc(e.target.value)}
          onPaste={(e) => {
            // Chords-over-lyrics pasted into the editor become ChordPro.
            const txt = e.clipboardData.getData('text');
            if (txt.split('\n').length > 2 && !/\[[A-G]/.test(txt)) {
              e.preventDefault();
              const conv = importText(txt).replace(/^\{title:[^}]*\}\n(\{artist:[^}]*\}\n)?\n?/, '');
              const el = e.currentTarget;
              setSrc(src.slice(0, el.selectionStart) + conv + src.slice(el.selectionEnd));
            }
          }}
          spellCheck={false}
          rows={24}
        />
        <Sheet chordpro={src} />
      </div>
      <div className="import-foot">
        <button className="btn primary big" onClick={() => void saveSong({ ...song, chordpro: src }).then(() => go(`/song/${song.id}`, true))}>
          {t('save')}
        </button>
        <button className="btn" onClick={() => history.back()}>
          {t('cancel')}
        </button>
      </div>
    </div>
  );
}
