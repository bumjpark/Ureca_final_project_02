// 여러 관리자 화면에서 반복되던 "정책 선택 드롭다운"을 하나로 통일.
// value는 문자열(select value 특성) — 호출부에서 Number()로 변환해서 쓴다.
export default function PolicyPicker({ value, onChange, policies, allowAll = false, className = '' }) {
  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={`border border-line rounded-md px-3 py-2 text-[13px] ${className}`}
    >
      {allowAll && <option value="">전체 정책</option>}
      {!allowAll && value === '' && <option value="">정책 선택</option>}
      {(policies ?? []).map((p) => (
        <option key={p.id} value={p.id}>
          #{p.id} {p.title}
        </option>
      ))}
    </select>
  );
}
