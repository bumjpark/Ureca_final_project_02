import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listPolicies, getCouponStatus } from '../lib/endpoints.js';
import { Badge, Card, LoadingBlock } from '../components/ui.jsx';
import Pagination from '../components/Pagination.jsx';
import { comma, fmtDateTime, discountLabel, policyStatusBadge } from '../lib/format.js';

const PAGE_SIZE = 9;

export default function EventListPage() {
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ['event-list', page],
    queryFn: async () => {
      const res = await listPolicies(page, PAGE_SIZE);
      // 재고 조회 결과는 stock으로 담는다 — 예전엔 status로 담았는데, 그러면 정책 자신의
      // status(BEFORE_OPEN/OPEN/CLOSED…)를 덮어써서 배지가 깨진다.
      const items = await Promise.all(
        (res.content ?? []).map(async (p) => {
          try {
            const stock = await getCouponStatus(p.id);
            return { ...p, stock };
          } catch {
            return { ...p, stock: null };
          }
        }),
      );
      return { items, page: res.page, totalPages: res.totalPages, totalElements: res.totalElements };
    },
  });

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-between">
        <div className="text-[22px] font-bold text-ink">쿠폰 이벤트</div>
        <div className="flex gap-5 text-[13px]">
          <span className="text-ink font-semibold">홈</span>
          <Link to="/my-coupons" className="text-sub hover:text-ink">
            내 쿠폰함 &gt;
          </Link>
        </div>
      </div>

      {q.isLoading && <LoadingBlock />}
      {q.data && q.data.items.length === 0 && (
        <div className="text-sm text-sub py-16 text-center">등록된 쿠폰 이벤트가 없어요</div>
      )}

      <div className="grid grid-cols-3 gap-4">
        {(q.data?.items ?? []).map((p) => {
          const badge = policyStatusBadge(p.status);
          const remain = p.stock
            ? `잔여 ${comma(p.stock.remainingQuantity)} / ${comma(p.stock.totalQuantity)}`
            : `총 ${comma(p.totalQuantity)}장`;
          return (
            <Link key={p.id} to={`/events/${p.id}`}>
              <Card className={`flex flex-col hover:border-mint transition-colors ${badge.tone === 'done' ? 'opacity-70' : ''}`}>
                <div className="flex items-center justify-between mb-2.5">
                  <Badge tone={badge.tone}>{badge.label}</Badge>
                  <span className="text-[11px] text-sub">{remain}</span>
                </div>
                <div className="text-[15px] font-semibold text-ink mb-1">{p.title}</div>
                <div className="text-[13px] text-sub mb-1.5">{discountLabel(p.couponType, p.discountValue)}</div>
                <div className="text-xs text-sub">
                  오픈 {fmtDateTime(p.openAt)}
                  {p.closeAt ? ` · 마감 ${fmtDateTime(p.closeAt)}` : ''}
                </div>
              </Card>
            </Link>
          );
        })}
      </div>

      <Pagination
        page={q.data?.page ?? 0}
        totalPages={q.data?.totalPages ?? 0}
        totalElements={q.data?.totalElements ?? 0}
        onChange={setPage}
      />
    </div>
  );
}
