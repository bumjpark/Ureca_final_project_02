// dev 브랜치의 토스풍 디자인 시스템을 적용한 공통 컴포넌트.
// 색은 index.css의 @theme 토큰만 쓴다 — ink(본문) / sub(보조) / line·hairline(경계) /
// surface(연회색 면) / mint(포인트) / danger(경고). 그 외 색은 쓰지 않는다.
// export 이름과 props는 이전과 동일하게 유지했으므로 사용처는 손대지 않아도 된다.

export function Shell({ children }) {
  return (
    <div className="w-full min-h-[640px] bg-white box-border flex flex-col">{children}</div>
  );
}

export function PageHeader({ title, right, sub }) {
  return (
    <div className="flex items-end justify-between mb-1">
      <div>
        <div className="text-[22px] font-bold text-ink tracking-[-0.01em]">{title}</div>
        {sub && <div className="text-[13px] text-sub mt-1">{sub}</div>}
      </div>
      {right}
    </div>
  );
}

export function Card({ className = '', children }) {
  return (
    <div className={`bg-white rounded-card border border-hairline shadow-card p-4 ${className}`}>
      {children}
    </div>
  );
}

export function Button({ variant = 'primary', className = '', disabled, children, ...rest }) {
  const base =
    'inline-flex items-center justify-center rounded-btn text-[14px] font-bold px-5 h-11 ' +
    'transition-colors select-none disabled:cursor-not-allowed';
  const variants = {
    primary: 'bg-ink text-white hover:bg-black disabled:bg-line disabled:text-sub',
    mint: 'bg-mint text-white hover:brightness-95 disabled:bg-line disabled:text-sub',
    outline: 'border border-line bg-white text-ink hover:bg-surface disabled:text-sub disabled:border-hairline',
    ghost: 'bg-surface text-ink hover:bg-line disabled:text-sub',
    danger: 'bg-danger text-white hover:brightness-95 disabled:bg-line disabled:text-sub',
  };
  return (
    <button className={`${base} ${variants[variant] ?? variants.primary} ${className}`} disabled={disabled} {...rest}>
      {children}
    </button>
  );
}

// 의미 기준 톤. 긍정/진행은 민트, 실패/이상은 레드, 나머지는 무채색.
const BADGE_TONES = {
  live: 'bg-mint text-white',
  issued: 'bg-mint-weak text-mint',
  ok: 'bg-mint-weak text-mint',
  soon: 'bg-surface text-ink',
  used: 'bg-surface text-sub',
  done: 'bg-white border border-line text-sub',
  expired: 'bg-white border border-line text-sub',
  bad: 'bg-danger-weak text-danger',
  plain: 'bg-surface text-sub',
};

export function Badge({ tone = 'plain', children }) {
  return (
    <span
      className={`inline-flex items-center h-6 px-2 rounded-md text-[12px] font-bold ${
        BADGE_TONES[tone] ?? BADGE_TONES.plain
      }`}
    >
      {children}
    </span>
  );
}

export function FieldRow({ label, value }) {
  return (
    <div className="flex justify-between text-[14px] py-3 border-b border-hairline last:border-b-0 text-ink">
      <span className="text-sub">{label}</span>
      <span className="font-semibold nums">{value}</span>
    </div>
  );
}

export function Spinner() {
  return (
    <div className="w-9 h-9 rounded-full border-[3px] border-line border-t-mint animate-spin" />
  );
}

export function StatTile({ label, value }) {
  return (
    <div className="bg-surface rounded-card p-4 flex-1">
      <div className="text-[12px] text-sub mb-1.5 font-semibold">{label}</div>
      <div className="text-[22px] font-bold text-ink nums tracking-[-0.02em]">{value}</div>
    </div>
  );
}

export function DataTable({ columns, rows, rowKey, empty = '데이터가 없습니다', onRowClick, isSelected }) {
  return (
    <div className="border border-hairline rounded-card overflow-hidden bg-white shadow-card">
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr className="bg-surface">
            {columns.map((c) => (
              <th
                key={c.key}
                className="text-left text-sub font-bold text-[12px] px-3 py-3 border-b border-hairline"
                style={c.width ? { width: c.width } : undefined}
              >
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={columns.length} className="px-3 py-10 text-center text-sub text-[13px]">
                {empty}
              </td>
            </tr>
          )}
          {rows.map((row) => {
            const selected = isSelected?.(row);
            return (
              <tr
                key={rowKey(row)}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                className={`border-b border-hairline last:border-b-0 transition-colors ${
                  onRowClick ? 'cursor-pointer' : ''
                } ${selected ? 'bg-mint-weak' : 'hover:bg-surface'}`}
              >
                {columns.map((c) => (
                  <td key={c.key} className="px-3 py-3 text-ink align-middle">
                    {c.render ? c.render(row) : row[c.key]}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function Tabs({ options, value, onChange }) {
  return (
    <div className="flex gap-1 border-b border-line">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={`text-[14px] px-4 py-3 border-b-2 -mb-px transition-colors ${
            value === opt.value
              ? 'text-ink font-bold border-ink'
              : 'text-sub font-semibold border-transparent hover:text-ink'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}

export function InlineError({ message }) {
  if (!message) return null;
  return (
    <div className="border border-danger-weak bg-danger-weak rounded-btn px-3 py-2.5 text-[13px] font-semibold text-danger">
      {message}
    </div>
  );
}

export function LoadingBlock({ label = '불러오는 중...' }) {
  return <div className="text-[14px] text-sub py-10 text-center">{label}</div>;
}

/* ── dev 브랜치에서 함께 가져온 프리미티브 ───────────────────── */

// 재고 소진률 등 비율 표시용. tone='danger'면 레드로 전환.
export function ProgressBar({ value, max, tone = 'mint' }) {
  const ratio = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0;
  return (
    <div className="h-2 w-full rounded-full bg-surface overflow-hidden">
      <div
        className="h-full rounded-full transition-[width] duration-500"
        style={{
          width: `${ratio * 100}%`,
          background: tone === 'danger' ? 'var(--color-danger)' : 'var(--color-mint)',
        }}
      />
    </div>
  );
}
