import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';

/* ── 버튼: 풀 너비, 높이 52px, 굵은 글씨, radius 12px ───────────── */
export function Button({
  children,
  variant = 'primary', // primary | mint | danger | ghost
  disabled,
  loading,
  className = '',
  ...props
}) {
  const base =
    'w-full h-[52px] rounded-btn font-bold text-[16px] inline-flex items-center justify-center gap-2 transition-colors select-none disabled:cursor-not-allowed';
  const styles = {
    primary: 'bg-ink text-white hover:bg-black disabled:bg-line disabled:text-sub',
    mint: 'bg-mint text-white hover:brightness-95 disabled:bg-line disabled:text-sub',
    danger: 'bg-danger text-white hover:brightness-95 disabled:bg-line disabled:text-sub',
    ghost: 'bg-surface text-ink hover:bg-line disabled:text-sub',
  };
  return (
    <button className={`${base} ${styles[variant]} ${className}`} disabled={disabled || loading} {...props}>
      {loading && <Spinner className="text-current" />}
      {children}
    </button>
  );
}

export function Spinner({ className = '' }) {
  return (
    <svg className={`animate-spin ${className}`} width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

/* ── 카드: radius 16px, 아주 약한 그림자 ─────────────────────── */
export function Card({ children, className = '', ...props }) {
  return (
    <div className={`bg-white rounded-card border border-hairline shadow-card ${className}`} {...props}>
      {children}
    </div>
  );
}

/* ── 섹션: 연회색 면으로만 구분 ─────────────────────────────── */
export function Section({ title, desc, right, children, tone = 'plain', className = '' }) {
  return (
    <section className={`${tone === 'surface' ? 'bg-surface' : ''} ${className}`}>
      {(title || right) && (
        <div className="flex items-end justify-between gap-3 mb-4">
          <div>
            {title && <h2 className="text-[18px] font-bold text-ink">{title}</h2>}
            {desc && <p className="text-[13px] text-sub mt-1">{desc}</p>}
          </div>
          {right}
        </div>
      )}
      {children}
    </section>
  );
}

/* ── 프로그레스 바: 얇게 ───────────────────────────────────── */
export function ProgressBar({ value, max, tone = 'mint' }) {
  const ratio = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0;
  return (
    <div className="h-2 w-full rounded-full bg-surface overflow-hidden">
      <div
        className="h-full rounded-full transition-[width] duration-500"
        style={{ width: `${ratio * 100}%`, background: tone === 'danger' ? 'var(--color-danger)' : 'var(--color-mint)' }}
      />
    </div>
  );
}

/* ── 쿠폰 상태 뱃지 (백엔드 실제 모델: ISSUED / USED / EXPIRED) ── */
export function StatusBadge({ status }) {
  const map = {
    ISSUED: { label: '사용 가능', cls: 'bg-mint-weak text-mint' },
    USED: { label: '사용 완료', cls: 'bg-surface text-sub' },
    EXPIRED: { label: '기간 만료', cls: 'bg-surface text-sub' },
  };
  const s = map[status] ?? { label: status, cls: 'bg-surface text-sub' };
  return (
    <span className={`inline-flex items-center h-6 px-2 rounded-md text-[12px] font-bold ${s.cls}`}>
      {s.label}
    </span>
  );
}

export function Pill({ children, tone = 'plain' }) {
  const cls = {
    plain: 'bg-surface text-sub',
    mint: 'bg-mint-weak text-mint',
    danger: 'bg-danger-weak text-danger',
  }[tone];
  return <span className={`inline-flex items-center h-6 px-2 rounded-md text-[12px] font-bold ${cls}`}>{children}</span>;
}

/* ── "이 화면이 무슨 백엔드 과업의 증거인지" 캡션 ─────────────── */
export function EvidenceNote({ children }) {
  return (
    <p className="text-[12px] leading-relaxed text-sub border-l-2 border-line pl-3">
      {children}
    </p>
  );
}

/* ── 백엔드가 노출하지 않는 지표 표시 ───────────────────────── */
export function GapNotice({ children }) {
  return (
    <span className="inline-flex items-center gap-1 text-[11px] font-bold text-sub bg-surface rounded-md px-1.5 h-5">
      백엔드 미집계
      {children ? <span className="font-normal">· {children}</span> : null}
    </span>
  );
}

/* ── 토스트 ────────────────────────────────────────────────── */
const ToastCtx = createContext(null);

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const idRef = useRef(0);

  const show = useCallback((message, tone = 'plain') => {
    const id = ++idRef.current;
    setToasts((t) => [...t, { id, message, tone }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200);
  }, []);

  return (
    <ToastCtx.Provider value={show}>
      {children}
      <div className="fixed left-1/2 -translate-x-1/2 bottom-6 z-50 flex flex-col gap-2 w-[calc(100%-40px)] max-w-[440px]">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`rounded-btn px-4 py-3 text-[14px] font-semibold shadow-card text-white ${
              t.tone === 'mint' ? 'bg-mint' : t.tone === 'danger' ? 'bg-danger' : 'bg-ink'
            }`}
          >
            {t.message}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastCtx);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}

/* ── 하단 시트 ─────────────────────────────────────────────── */
export function BottomSheet({ open, onClose, title, children }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
    };
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center">
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />
      <div className="relative w-full max-w-[480px] bg-white rounded-t-[20px] max-h-[85vh] overflow-y-auto animate-[sheet_.22s_ease-out]">
        <div className="sticky top-0 bg-white px-5 pt-3 pb-3 border-b border-hairline">
          <div className="mx-auto mb-3 h-1 w-9 rounded-full bg-line" />
          <div className="flex items-center justify-between">
            <h3 className="text-[16px] font-bold">{title}</h3>
            <button onClick={onClose} className="text-sub text-[13px] font-semibold">
              닫기
            </button>
          </div>
        </div>
        <div className="px-5 py-4">{children}</div>
      </div>
      <style>{`@keyframes sheet{from{transform:translateY(16px);opacity:.6}to{transform:translateY(0);opacity:1}}`}</style>
    </div>
  );
}

/* ── 상태/로딩/에러 ────────────────────────────────────────── */
export function LoadingBlock({ label = '불러오는 중' }) {
  return (
    <div className="flex items-center justify-center gap-2 py-10 text-sub text-[14px]">
      <Spinner /> {label}
    </div>
  );
}

/* ── 페이지네이션 ──────────────────────────────────────────── */
export function Pagination({ page, pageCount, onChange }) {
  if (pageCount <= 1) return null;
  const size = 5;
  let end = Math.min(pageCount, Math.max(page + 3, size));
  let start = Math.max(0, end - size);
  const nums = [];
  for (let i = start; i < end; i++) nums.push(i);

  return (
    <div className="flex items-center justify-center gap-1 py-4">
      <PgBtn disabled={page === 0} onClick={() => onChange(page - 1)}>
        ‹
      </PgBtn>
      {start > 0 && (
        <>
          <PgBtn onClick={() => onChange(0)}>1</PgBtn>
          {start > 1 && <span className="px-1 text-sub">…</span>}
        </>
      )}
      {nums.map((i) => (
        <PgBtn key={i} active={i === page} onClick={() => onChange(i)}>
          {i + 1}
        </PgBtn>
      ))}
      {end < pageCount && (
        <>
          {end < pageCount - 1 && <span className="px-1 text-sub">…</span>}
          <PgBtn onClick={() => onChange(pageCount - 1)}>{pageCount}</PgBtn>
        </>
      )}
      <PgBtn disabled={page === pageCount - 1} onClick={() => onChange(page + 1)}>
        ›
      </PgBtn>
    </div>
  );
}

function PgBtn({ children, active, disabled, onClick }) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`min-w-8 h-8 px-2 rounded-md text-[13px] font-bold nums transition-colors disabled:opacity-40 ${
        active ? 'bg-ink text-white' : 'text-sub hover:bg-surface hover:text-ink'
      }`}
    >
      {children}
    </button>
  );
}

export function ErrorBlock({ error, onRetry }) {
  return (
    <Card className="p-5 text-center">
      <p className="text-[14px] font-bold text-ink">불러오지 못했어요</p>
      <p className="text-[13px] text-sub mt-1">{error?.message ?? '알 수 없는 오류'}</p>
      {onRetry && (
        <button onClick={onRetry} className="mt-3 text-[13px] font-bold text-mint">
          다시 시도
        </button>
      )}
    </Card>
  );
}
