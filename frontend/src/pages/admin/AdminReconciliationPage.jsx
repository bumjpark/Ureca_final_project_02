import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listReconciliationLogs, retryReconciliation } from '../../lib/endpoints.js';
import { Badge, Button, DataTable, InlineError, PageHeader } from '../../components/ui.jsx';
import Pagination from '../../components/Pagination.jsx';
import { fmtDateTime } from '../../lib/format.js';

const TYPES = ['EVENT_REPUBLISH', 'DLT_REPROCESS', 'ISSUE_REPROCESS', 'REDIS_RECOVER'];
const STATUSES = ['PENDING', 'SUCCESS', 'FAILED'];
const STATUS_TONE = { SUCCESS: 'ok', FAILED: 'bad', PENDING: 'soon' };

/**
 * 정합성 재처리 큐 패널. `policyId`가 주어지면(정책 작업 공간) 이 정책과 관련된 재처리 로그만
 * 보여준다(REDIS_RECOVER 등 coupon_issue와 안 묶이는 일부 로그는 policyId 필터에서 자연히
 * 빠진다 — 아래 안내 링크로 전역 화면에서 확인할 수 있게 한다).
 */
export function ReconciliationPanel({ policyId = null }) {
  const [type, setType] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [error, setError] = useState(null);
  const queryClient = useQueryClient();

  const q = useQuery({
    queryKey: ['reconciliation-logs', policyId, type, status, page],
    queryFn: () => listReconciliationLogs({ policyId, type: type || undefined, status: status || undefined, page, size: 15 }),
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['reconciliation-logs'] });

  const retryOne = async (logId) => {
    setError(null);
    try {
      await retryReconciliation({ logId });
      refresh();
    } catch (e) {
      setError(e.message);
    }
  };

  const retryAll = async () => {
    setError(null);
    try {
      await retryReconciliation({ type: type || 'EVENT_REPUBLISH' });
      refresh();
    } catch (e) {
      setError(e.message);
    }
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="정합성 재처리 큐"
        right={
          <div className="flex items-center gap-3">
            <select
              value={type}
              onChange={(e) => {
                setType(e.target.value);
                setPage(0);
              }}
              className="border border-zinc-300 rounded-md px-3 py-2 text-[13px]"
            >
              <option value="">전체 유형</option>
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
            <select
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
              className="border border-zinc-300 rounded-md px-3 py-2 text-[13px]"
            >
              <option value="">전체 상태</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
            <Button onClick={retryAll}>전체 재처리</Button>
          </div>
        }
      />

      <InlineError message={error} />

      <DataTable
        rowKey={(r) => r.id}
        empty="재처리 대상이 없어요"
        columns={[
          { key: 'id', label: 'ID', render: (r) => `#${r.id}` },
          { key: 'type', label: '유형' },
          { key: 'status', label: '상태', render: (r) => <Badge tone={STATUS_TONE[r.status]}>{r.status}</Badge> },
          { key: 'eventKey', label: 'eventKey', render: (r) => <span className="font-mono text-xs">{r.eventKey ?? '-'}</span> },
          { key: 'retryCount', label: '재시도' },
          { key: 'failReason', label: '실패 사유', render: (r) => r.failReason ?? '-' },
          { key: 'createdAt', label: '등록 일시', render: (r) => fmtDateTime(r.createdAt) },
          {
            key: 'actions',
            label: '',
            render: (r) =>
              r.status !== 'SUCCESS' ? (
                <button className="text-xs text-zinc-500 hover:text-zinc-900 hover:underline" onClick={() => retryOne(r.id)}>
                  재처리
                </button>
              ) : null,
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

      {policyId && (
        <div className="text-[11px] text-zinc-400">
          이 정책의 발급 건과 연결된 로그만 보여요. coupon_issue와 안 묶이는 일부 로그(예: Redis 완전 유실
          복구)까지 보려면{' '}
          <Link to="/admin/reconciliation" className="text-zinc-600 underline underline-offset-2">
            전체 재처리 화면
          </Link>
          에서 확인하세요.
        </div>
      )}
    </div>
  );
}

export default function AdminReconciliationPage() {
  return <ReconciliationPanel />;
}
