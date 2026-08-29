// 와이어프레임의 zinc 단색 팔레트를 그대로 옮긴 공통 컴포넌트.
// #18181b→zinc-900, #52525b→zinc-600, #71717a→zinc-500, #a1a1aa→zinc-400,
// #d4d4d8→zinc-300, #e4e4e7→zinc-200, #f4f4f5→zinc-100.

export function Shell({ children }) {
  return (
    <div className="w-full min-h-[640px] bg-white box-border flex flex-col">{children}</div>
  );
}

export function PageHeader({ title, right, sub }) {
  return (
    <div className="flex items-end justify-between mb-1">
      <div>
        <div className="text-[22px] font-bold text-zinc-900">{title}</div>
        {sub && <div className="text-xs text-zinc-400 mt-1">{sub}</div>}
      </div>
      {right}
    </div>
  );
}

export function Card({ className = '', children }) {
  return (
    <div className={`border border-zinc-300 rounded-lg bg-white p-4 ${className}`}>
      {children}
    </div>
  );
}

export function Button({ variant = 'primary', className = '', disabled, children, ...rest }) {
  const base = 'inline-flex items-center justify-center rounded-md text-sm font-semibold px-5 py-2.5 transition-colors';
  const variants = {
    primary: 'bg-zinc-900 text-white hover:bg-zinc-800 disabled:bg-zinc-200 disabled:text-zinc-400',
    outline: 'border border-zinc-300 bg-white text-zinc-600 hover:bg-zinc-50 disabled:text-zinc-300',
    danger: 'bg-white border border-zinc-300 text-zinc-400',
  };
  return (
    <button className={`${base} ${variants[variant]} ${className}`} disabled={disabled} {...rest}>
      {children}
    </button>
  );
}

const BADGE_TONES = {
  live: 'bg-zinc-900 text-white',
  issued: 'bg-zinc-900 text-white',
  soon: 'bg-zinc-200 text-zinc-700',
  used: 'bg-zinc-200 text-zinc-600',
  done: 'bg-white border border-zinc-300 text-zinc-400',
  expired: 'bg-white border border-zinc-300 text-zinc-400',
  ok: 'bg-zinc-200 text-zinc-700',
  bad: 'bg-zinc-900 text-white',
  plain: 'border border-zinc-400 text-zinc-600',
};

export function Badge({ tone = 'plain', children }) {
  return (
    <span className={`inline-block px-2 py-[3px] text-[11px] rounded ${BADGE_TONES[tone] ?? BADGE_TONES.plain}`}>
      {children}
    </span>
  );
}

export function FieldRow({ label, value }) {
  return (
    <div className="flex justify-between text-sm py-3 border-b border-zinc-100 last:border-b-0 text-zinc-700">
      <span className="text-zinc-400">{label}</span>
      <span>{value}</span>
    </div>
  );
}

export function Spinner() {
  return (
    <div className="w-9 h-9 rounded-full border-[3px] border-zinc-200 border-t-zinc-900 animate-spin" />
  );
}

export function StatTile({ label, value }) {
  return (
    <div className="border border-zinc-300 rounded-md p-4 flex-1">
      <div className="text-[11px] text-zinc-400 mb-1.5">{label}</div>
      <div className="text-xl font-bold text-zinc-900">{value}</div>
    </div>
  );
}

export function DataTable({ columns, rows, rowKey, empty = '데이터가 없습니다' }) {
  return (
    <div className="border border-zinc-300 rounded-lg overflow-hidden">
      <table className="w-full border-collapse text-[13px]">
        <thead>
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                className="text-left text-zinc-400 font-semibold text-[11px] px-3 py-2.5 border-b border-zinc-200"
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
              <td colSpan={columns.length} className="px-3 py-6 text-center text-zinc-400 text-xs">
                {empty}
              </td>
            </tr>
          )}
          {rows.map((row) => (
            <tr key={rowKey(row)} className="border-b border-zinc-100 last:border-b-0">
              {columns.map((c) => (
                <td key={c.key} className="px-3 py-3 text-zinc-700 align-middle">
                  {c.render ? c.render(row) : row[c.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Tabs({ options, value, onChange }) {
  return (
    <div className="flex gap-1 border-b border-zinc-200">
      {options.map((opt) => (
        <button
          key={opt.value}
          onClick={() => onChange(opt.value)}
          className={`text-[13px] px-4 py-2.5 border-b-2 -mb-px ${
            value === opt.value
              ? 'text-zinc-900 font-semibold border-zinc-900'
              : 'text-zinc-400 border-transparent'
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
    <div className="border border-zinc-300 bg-zinc-50 rounded-md px-3 py-2 text-xs text-zinc-600">
      {message}
    </div>
  );
}

export function LoadingBlock({ label = '불러오는 중...' }) {
  return <div className="text-sm text-zinc-400 py-10 text-center">{label}</div>;
}
