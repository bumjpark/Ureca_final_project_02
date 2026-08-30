export const comma = (n) => (n == null ? '-' : Number(n).toLocaleString('ko-KR'));

// 서버가 주는 LocalDateTime 문자열(타임존 표기 없음, KST wall-clock)을 그대로 보여준다.
export function fmtDateTime(s) {
  if (!s) return '-';
  return String(s).replace('T', ' ').slice(0, 19);
}

export function fmtClock(s) {
  if (!s) return '-';
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return '-';
  return d.toLocaleTimeString('ko-KR', { hour12: false });
}

// 할인 라벨: RATE는 %, FIXED는 원
export function discountLabel(couponType, discountValue) {
  if (couponType === 'RATE') return `${discountValue}% 할인`;
  return `${comma(discountValue)}원 즉시 할인`;
}
