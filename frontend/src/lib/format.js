export const nf = new Intl.NumberFormat('ko-KR');

export const comma = (n) => (n == null || Number.isNaN(n) ? '-' : nf.format(n));

export function pct(n, digits = 1) {
  if (n == null || Number.isNaN(n)) return '-';
  return `${Number(n).toFixed(digits)}%`;
}

// "2026. 8. 28. 14:30"
export function fmtDateTime(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function fmtDate(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' });
}

// "HH:MM:SS" 카운트다운 (남은 밀리초 → 문자열)
export function fmtCountdown(ms) {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const p = (x) => String(x).padStart(2, '0');
  return `${p(h)}:${p(m)}:${p(s)}`;
}

export function fmtClock(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleTimeString('ko-KR', { hour12: false });
}
