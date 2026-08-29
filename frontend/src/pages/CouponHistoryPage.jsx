import { useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getCouponDetail, getCouponHistory } from '../lib/endpoints.js';
import { useSession } from '../lib/session.jsx';
import { LoadingBlock } from '../components/ui.jsx';
import { fmtDateTime } from '../lib/format.js';

const TRANSITION_LABEL = {
  'NONE→ISSUED': '발급됨 (신규 → ISSUED)',
  'ISSUED→USED': '사용됨 (ISSUED → USED)',
  'USED→ISSUED': '사용취소 (USED → ISSUED)',
  'ISSUED→EXPIRED': '만료 처리됨 (ISSUED → EXPIRED)',
};

export default function CouponHistoryPage() {
  const { couponIssueId } = useParams();
  const { userId } = useSession();

  const detailQ = useQuery({
    queryKey: ['coupon-detail', couponIssueId],
    queryFn: () => getCouponDetail(couponIssueId, userId),
  });
  const historyQ = useQuery({
    queryKey: ['coupon-history', couponIssueId],
    queryFn: () => getCouponHistory(couponIssueId),
  });

  if (detailQ.isLoading || historyQ.isLoading) return <LoadingBlock />;

  const entries = historyQ.data ?? [];

  return (
    <div className="flex justify-center">
      <div className="w-[520px] flex flex-col gap-4">
        <Link to={`/my-coupons/${couponIssueId}`} className="text-[13px] text-zinc-500">
          &lt; 쿠폰 상세
        </Link>
        <div className="text-lg font-bold text-zinc-900">{detailQ.data?.title} · 이력</div>

        <div className="flex flex-col">
          {entries.length === 0 && <div className="text-sm text-zinc-400 py-8 text-center">이력이 없어요</div>}
          {entries.map((h, i) => {
            const key = `${h.prevStatus}→${h.newStatus}`;
            const isLast = i === entries.length - 1;
            return (
              <div key={i} className="flex gap-3">
                <div className="flex flex-col items-center">
                  <div className="w-2.5 h-2.5 rounded-full bg-zinc-900" />
                  {!isLast && <div className="w-px flex-grow bg-zinc-300" />}
                </div>
                <div className="pb-6 flex-grow">
                  <div className="text-[11px] text-zinc-400">{fmtDateTime(h.createdAt)}</div>
                  <div className="text-[13px] font-semibold text-zinc-900 mt-0.5">
                    {TRANSITION_LABEL[key] ?? `${h.prevStatus} → ${h.newStatus}`}
                  </div>
                  {h.cancelReason && (
                    <div className="text-xs text-zinc-500 mt-1 border border-zinc-200 rounded-md p-2">
                      사유: {h.cancelReason}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
