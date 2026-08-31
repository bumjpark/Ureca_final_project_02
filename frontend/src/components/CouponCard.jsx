import { comma, fmtDate } from '../lib/format.js';

/* ── 시그니처 요소 ────────────────────────────────────────────
   실물 쿠폰처럼 절취선(perforation): 좌우 반원 펀치홀 + 가운데 세로 점선.
   장식은 이 컴포넌트에만. 다른 화면엔 넣지 않는다. */

export function discountLabel(policy) {
  if (!policy) return '';
  return policy.couponType === 'RATE'
    ? `${comma(policy.discountValue)}% 할인`
    : `${comma(policy.discountValue)}원 할인`;
}

export default function CouponCard({ policy, remaining, status, footer }) {
  const soldOut = status === 'SOLD_OUT' || remaining === 0;

  return (
    <div className="relative">
      <div
        className={`relative flex bg-white rounded-card border shadow-card overflow-hidden ${
          soldOut ? 'border-hairline' : 'border-hairline'
        }`}
      >
        {/* 본권 */}
        <div className="flex-1 p-6">
          <p className="text-[13px] font-bold text-sub">{policy?.title ?? '쿠폰'}</p>
          <p className="mt-2 text-[34px] leading-none font-extrabold text-ink nums">
            {policy ? discountLabel(policy) : '-'}
          </p>
          <dl className="mt-5 space-y-1.5 text-[13px]">
            <div className="flex gap-2">
              <dt className="w-16 shrink-0 text-sub">사용 조건</dt>
              <dd className="text-ink">
                {policy?.couponType === 'RATE' ? '전 상품 즉시 할인' : '주문 금액에서 즉시 차감'}
              </dd>
            </div>
            <div className="flex gap-2">
              <dt className="w-16 shrink-0 text-sub">유효기간</dt>
              <dd className="text-ink nums">
                {policy?.closeAt ? `${fmtDate(policy.closeAt)}까지` : '소진 시까지'}
              </dd>
            </div>
            <div className="flex gap-2">
              <dt className="w-16 shrink-0 text-sub">수량</dt>
              <dd className="text-ink nums">
                선착순 {comma(policy?.totalQuantity)}장 한정
              </dd>
            </div>
          </dl>
        </div>

        {/* 절취선 */}
        <div className="relative w-0 shrink-0">
          <div className="absolute inset-y-3 left-1/2 -translate-x-1/2 border-l-2 border-dashed border-line" />
        </div>

        {/* 스텁 */}
        <div className="w-[64px] shrink-0 flex items-center justify-center">
          <span
            className={`text-[11px] font-extrabold tracking-[0.25em] ${soldOut ? 'text-sub' : 'text-mint'}`}
            style={{ writingMode: 'vertical-rl' }}
          >
            MYURECA
          </span>
        </div>

        {/* 좌우 반원 펀치홀 — 절취선 위치(스텁 폭 64px 기준) */}
        <Punch side="top" />
        <Punch side="bottom" />
      </div>

      {footer ? <div className="mt-4">{footer}</div> : null}
    </div>
  );
}

function Punch({ side }) {
  // 스텁 폭 64px → 절취선은 오른쪽에서 64px 지점
  const pos = side === 'top' ? { top: -10 } : { bottom: -10 };
  return (
    <span
      aria-hidden
      className="absolute w-5 h-5 rounded-full bg-white border border-hairline"
      style={{ right: 64 - 10, ...pos }}
    />
  );
}
