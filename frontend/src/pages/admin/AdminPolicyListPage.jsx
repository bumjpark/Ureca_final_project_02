import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { listPolicies, deletePolicy } from '../../lib/endpoints.js';
import { Badge, Button, DataTable, PageHeader } from '../../components/ui.jsx';
import Pagination from '../../components/Pagination.jsx';
import { comma, fmtDateTime, policyStatusBadge } from '../../lib/format.js';

export default function AdminPolicyListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  // 최근에 만든 정책이 위로 오게 id 내림차순. 서버 페이지네이션이라 화면에서 뒤집으면
  // 현재 페이지 안에서만 정렬돼 틀리므로, 정렬은 서버에 맡긴다.
  const SORT = 'id,desc';
  const q = useQuery({
    queryKey: ['admin-policies', page, SORT],
    queryFn: () => listPolicies(page, 10, SORT),
  });

  const remove = async (id) => {
    if (!confirm(`정책 #${id}를 삭제할까요? (오픈 전에만 가능)`)) return;
    try {
      await deletePolicy(id);
      queryClient.invalidateQueries({ queryKey: ['admin-policies'] });
    } catch (e) {
      alert(e.message);
    }
  };

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="쿠폰 정책 관리"
        right={<Button onClick={() => navigate('/admin/new')}>+ 신규 정책 생성</Button>}
      />

      <DataTable
        rowKey={(r) => r.id}
        empty="등록된 정책이 없어요"
        columns={[
          { key: 'id', label: 'ID', render: (r) => `#${r.id}` },
          { key: 'title', label: '제목' },
          {
            key: 'type',
            label: '할인유형',
            render: (r) => (r.couponType === 'RATE' ? `RATE ${r.discountValue}%` : `FIXED ${comma(r.discountValue)}원`),
          },
          { key: 'qty', label: '수량', render: (r) => comma(r.totalQuantity) },
          { key: 'openAt', label: '오픈일시', render: (r) => fmtDateTime(r.openAt) },
          {
            key: 'status',
            label: '상태',
            render: (r) => {
              const b = policyStatusBadge(r.status);
              return <Badge tone={b.tone}>{b.label}</Badge>;
            },
          },
          {
            key: 'actions',
            label: '',
            render: (r) => (
              <div className="flex gap-3 text-xs text-sub">
                <button className="hover:text-ink hover:underline" onClick={() => navigate(`/admin/${r.id}`)}>
                  현황
                </button>
                <button className="hover:text-ink hover:underline" onClick={() => navigate(`/admin/${r.id}/edit`)}>
                  수정
                </button>
                <button className="hover:text-ink hover:underline" onClick={() => remove(r.id)}>
                  삭제
                </button>
              </div>
            ),
          },
        ]}
        rows={q.data?.content ?? []}
      />

      <Pagination
        page={q.data?.page ?? 0}
        totalPages={q.data?.totalPages ?? 0}
        totalElements={q.data?.totalElements ?? 0}
        onChange={setPage}
      />
    </div>
  );
}
