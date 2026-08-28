import { useEffect, useMemo, useRef, useState } from 'react';

/* 쿠폰 정책이 많아도(수십~수백 개) 스크롤로 다 고를 수 있는 드롭다운.
 * - 최신(id 큰) 순 정렬
 * - 제목/#id 검색
 * - 목록은 약 10개 높이로 고정하고 나머지는 스크롤 */
export default function PolicySelect({ policies, value, onChange }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    const onKey = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const sorted = useMemo(() => [...policies].sort((a, b) => b.id - a.id), [policies]);
  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase();
    if (!t) return sorted;
    return sorted.filter((p) => `#${p.id} ${p.title}`.toLowerCase().includes(t));
  }, [sorted, q]);

  const current = policies.find((p) => p.id === Number(value));

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="h-7 rounded-md border border-line bg-white px-2 font-semibold max-w-[280px] truncate text-left flex items-center gap-1"
      >
        <span className="truncate">
          {current ? `#${current.id} · ${current.title}` : '정책 선택'}
        </span>
        <span className="text-sub shrink-0">▾</span>
      </button>

      {open && (
        <div className="absolute left-0 z-40 mt-1 w-[320px] rounded-lg border border-line bg-white shadow-card overflow-hidden">
          <input
            autoFocus
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="정책 검색 (#id 또는 제목)"
            className="w-full h-8 px-2.5 text-[12px] border-b border-hairline outline-none"
          />
          <ul className="max-h-[320px] overflow-y-auto py-1">
            {filtered.map((p) => {
              const active = p.id === Number(value);
              return (
                <li key={p.id}>
                  <button
                    type="button"
                    onClick={() => {
                      onChange(p.id);
                      setOpen(false);
                      setQ('');
                    }}
                    className={`w-full text-left px-2.5 py-1.5 text-[12px] hover:bg-surface truncate ${
                      active ? 'text-mint font-bold bg-mint-weak' : 'text-ink'
                    }`}
                  >
                    #{p.id} · {p.title}
                  </button>
                </li>
              );
            })}
            {filtered.length === 0 && (
              <li className="px-2.5 py-2 text-[12px] text-sub">검색 결과가 없어요</li>
            )}
          </ul>
          <div className="px-2.5 py-1.5 border-t border-hairline text-[11px] text-sub nums">
            {filtered.length}개 {q ? `(전체 ${policies.length})` : ''}
          </div>
        </div>
      )}
    </div>
  );
}
