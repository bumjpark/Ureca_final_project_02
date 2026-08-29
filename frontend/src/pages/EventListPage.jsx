import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listPolicies, getCouponStatus } from '../lib/endpoints.js';
import { Badge, Card, LoadingBlock } from '../components/ui.jsx';
import Pagination from '../components/Pagination.jsx';
import { comma, fmtDateTime, discountLabel } from '../lib/format.js';

const PAGE_SIZE = 9;

function policyBadge(policy) {
  const now = new Date();
  const openAt = new Date(policy.openAt);
  const closeAt = policy.closeAt ? new Date(policy.closeAt) : null;
  if (now < openAt) return { tone: 'soon', label: '오픈 예정' };
  if (closeAt && now > closeAt) return { tone: 'done', label: '마감' };
  return { tone: 'live', label: '진행중' };
}

export default function EventListPage() {
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ['event-list', page],
    queryFn: async () => {
      const res = await listPolicies(page, PAGE_SIZE);
      const items = await Promise.all(
        (res.content ?? []).map(async (p) => {
          try {
            const status = await getCouponStatus(p.id);
            return { ...p, status };
          } catch {
            return { ...p, status: null };
          }
        }),
      );
      return { items, page: res.page, totalPages: res.totalPages, totalElements: res.totalElements };
    },
  });

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center justify-between">
        <div className="text-[22px] font-bold text-zinc-900">쿠폰 이벤트</div>
        <div className="flex gap-5 text-[13px]">
          <span className="text-zinc-900 font-semibold">홈</span>
          <Link to="/my-coupons" className="text-zinc-500 hover:text-zinc-900">
            내 쿠폰함 &gt;
          </Link>
        </div>
      </div>

      {q.isLoading && <LoadingBlock />}
      {q.data && q.data.items.length === 0 && (
        <div className="text-sm text-zinc-400 py-16 text-center">등록된 쿠폰 이벤트가 없어요</div>
      )}

      <div className="grid grid-cols-3 gap-4">
        {(q.data?.items ?? []).map((p) => {
          const badge = policyBadge(p);
          const remain = p.status ? `잔여 ${comma(p.status.remainingQuantity)} / ${comma(p.status.totalQuantity)}` : `총 ${comma(p.totalQuantity)}장`;
          return (
            <Link key={p.id} to={`/events/${p.id}`}>
              <Card className={`flex flex-col hover:border-zinc-500 transition-colors ${badge.tone === 'done' ? 'opacity-70' : ''}`}>
                <div className="flex items-center justify-between mb-2.5">
                  <Badge tone={badge.tone}>{badge.label}</Badge>
                  <span className="text-[11px] text-zinc-400">{remain}</span>
                </div>
                <div className="text-[15px] font-semibold text-zinc-900 mb-1">{p.title}</div>
                <div className="text-[13px] text-zinc-600 mb-1.5">{discountLabel(p.couponType, p.discountValue)}</div>
                <div className="text-xs text-zinc-400">
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
