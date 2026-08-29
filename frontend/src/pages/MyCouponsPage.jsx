import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getMyCoupons } from '../lib/endpoints.js';
import { useSession } from '../lib/session.jsx';
import { Badge, DataTable, LoadingBlock, Tabs } from '../components/ui.jsx';
import Pagination from '../components/Pagination.jsx';
import { discountLabel, fmtDateTime } from '../lib/format.js';

const TABS = [
  { value: null, label: '전체' },
  { value: 'ISSUED', label: '발급됨' },
  { value: 'USED', label: '사용완료' },
  { value: 'EXPIRED', label: '만료' },
];

const STATUS_BADGE = { ISSUED: 'issued', USED: 'used', EXPIRED: 'expired' };
const STATUS_LABEL = { ISSUED: '발급됨', USED: '사용완료', EXPIRED: '만료' };

export default function MyCouponsPage() {
  const { userId } = useSession();
  const [tab, setTab] = useState(null);
  const [page, setPage] = useState(0);

  const q = useQuery({
    queryKey: ['my-coupons', userId, tab, page],
    queryFn: () => getMyCoupons(userId, { status: tab ?? undefined, page, size: 10 }),
    enabled: !!userId,
  });

  const coupons = q.data?.coupons ?? [];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div className="text-[22px] font-bold text-zinc-900">내 쿠폰함</div>
        <div className="flex gap-5 text-[13px]">
          <Link to="/events" className="text-zinc-500 hover:text-zinc-900">
            홈
          </Link>
          <span className="text-zinc-900 font-semibold">내 쿠폰함</span>
        </div>
      </div>

      <Tabs
        options={TABS.map((t) => ({ value: String(t.value), label: t.label }))}
        value={String(tab)}
        onChange={(v) => {
          setTab(v === 'null' ? null : v);
          setPage(0);
        }}
      />

      {q.isLoading ? (
        <LoadingBlock />
      ) : (
        <DataTable
          rowKey={(r) => r.couponIssueId}
          empty="쿠폰이 없어요"
          columns={[
            {
              key: 'status',
              label: '상태',
              render: (r) => <Badge tone={STATUS_BADGE[r.displayStatus]}>{STATUS_LABEL[r.displayStatus]}</Badge>,
            },
            {
              key: 'title',
              label: '쿠폰명',
              render: (r) => (
                <Link to={`/my-coupons/${r.couponIssueId}`} className="font-semibold text-zinc-900 hover:underline">
                  {r.title}
                </Link>
              ),
            },
            { key: 'discount', label: '할인내용', render: (r) => discountLabel(r.couponType, r.discountValue) },
            { key: 'issuedAt', label: '일시', render: (r) => fmtDateTime(r.issuedAt) },
          ]}
          rows={coupons}
        />
      )}

      <Pagination
        page={q.data?.page?.number ?? 0}
        totalPages={q.data?.page?.totalPages ?? 0}
        totalElements={q.data?.page?.totalElements ?? 0}
        onChange={setPage}
      />
    </div>
  );
}
