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

// 정책 상태 배지. 서버(CouponPolicyResponse.status)가 주는 값을 그대로 쓴다.
//
// 예전에는 이 목록 화면들이 각자 openAt/closeAt을 현재 시각과 비교해 배지를 직접 계산했는데,
// (1) 같은 로직이 EventListPage와 AdminPolicyListPage에 복붙돼 있었고 (2) 시각만으로는 알 수 없는
// 재고 소진(CLOSED)을 표현하지 못해 매진된 정책도 "진행중"으로 보였다. 서버가 저장된 status를
// 그대로 주던 시절엔 그 값이 오픈 시각이 지나도 BEFORE_OPEN에 머물러 믿을 수 없었지만, 지금은
// CouponPolicy.effectiveStatusAt이 시각을 반영해 계산해주므로 서버 값을 신뢰할 수 있다.
const POLICY_STATUS_BADGE = {
  BEFORE_OPEN: { tone: 'soon', label: '오픈 예정' },
  OPEN: { tone: 'live', label: '진행중' },
  CLOSED: { tone: 'done', label: '소진 마감' },
  EXPIRED: { tone: 'done', label: '마감' },
  DELETED: { tone: 'done', label: '삭제됨' },
};

export function policyStatusBadge(status) {
  // label로는 반드시 문자열만 내보낸다 — 객체가 흘러들어오면 React가 자식으로 렌더링하려다
  // 화면 전체가 죽는다(실제로 status 필드가 다른 값으로 덮여 있던 화면에서 겪음).
  if (typeof status !== 'string') return { tone: 'plain', label: '-' };
  return POLICY_STATUS_BADGE[status] ?? { tone: 'plain', label: status };
}
