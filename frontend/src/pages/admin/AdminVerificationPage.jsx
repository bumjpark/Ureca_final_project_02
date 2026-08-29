import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listVerificationReports, runVerification, verificationReportCsvUrl } from '../../lib/endpoints.js';
import { useAdminPolicies } from '../../lib/hooks.js';
import { Badge, Button, Card, DataTable, InlineError, PageHeader } from '../../components/ui.jsx';
import PolicyPicker from '../../components/admin/PolicyPicker.jsx';
import Pagination from '../../components/Pagination.jsx';
import { comma, fmtDateTime } from '../../lib/format.js';

const STATUS_TONE = { SUCCESS: 'ok', MISMATCH_FOUND: 'bad', FAILED: 'bad', PENDING: 'soon' };
const STATUS_LABEL = {
  SUCCESS: '성공 (SUCCESS)',
  MISMATCH_FOUND: '불일치 발견 (MISMATCH_FOUND)',
  FAILED: '실패 (FAILED)',
  PENDING: '진행 중 (PENDING)',
};

/**
 * 정합성 검증 실행 + 리포트 조회 패널. `policyId`가 주어지면(정책 작업 공간) 그 정책에 고정되고,
 * 최신 리포트 1건을 상단 카드로 크게 보여준다. 없으면(독립 라우트 `/admin/verification`) 기존처럼
 * 전체 정책을 드롭다운으로 필터링한다. "상세"는 CSV를 내려받지 않아도 어떤 불일치인지 볼 수 있는
 * 별도 리포트 상세 화면(`/admin/verification/reports/:id`)으로 이동한다.
 */
export function VerificationPanel({ policyId = null }) {
  const [localPolicyId, setLocalPolicyId] = useState('');
  const [page, setPage] = useState(0);
  const [force, setForce] = useState(false);
  const [error, setError] = useState(null);
  const [running, setRunning] = useState(false);
  const queryClient = useQueryClient();

  const effectivePolicyId = policyId ?? (localPolicyId ? Number(localPolicyId) : undefined);

  const policiesQ = useAdminPolicies();
  const titleOf = (id) => policiesQ.data?.content?.find((p) => p.id === id)?.title ?? `#${id}`;

  const reportsQ = useQuery({
    queryKey: ['verification-reports', effectivePolicyId, page],
    queryFn: () => listVerificationReports({ policyId: effectivePolicyId, page, size: 10 }),
    refetchInterval: (query) => (query.state.data?.content?.some((r) => r.status === 'PENDING') ? 3000 : false),
  });

  const latestQ = useQuery({
    queryKey: ['verification-reports-latest', policyId],
    queryFn: () => listVerificationReports({ policyId, page: 0, size: 1 }),
    enabled: policyId != null,
  });
  const latest = latestQ.data?.content?.[0];

  const allPoliciesMode = !policyId && !localPolicyId;

  const run = async () => {
    setError(null);
    setRunning(true);
    try {
      await runVerification({ policyId: effectivePolicyId, force });
      setPage(0);
      queryClient.invalidateQueries({ queryKey: ['verification-reports'] });
      queryClient.invalidateQueries({ queryKey: ['verification-reports-latest'] });
    } catch (e) {
      if (e.status === 409) {
        setError(`${e.message} — 강제 실행하려면 아래 체크박스를 켜주세요.`);
      } else {
        setError(e.message);
      }
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={policyId ? '정합성 검증' : '전체 정합성 검증'}
        sub={!policyId ? '정책을 고르지 않으면 등록된 모든 정책을 한 번에 검증합니다' : undefined}
        right={
          <div className="flex items-center gap-3">
            {!policyId && (
              <PolicyPicker
                allowAll
                value={localPolicyId}
                onChange={(v) => {
                  setLocalPolicyId(v);
                  setPage(0);
                }}
                policies={policiesQ.data?.content}
              />
            )}
            <label className="flex items-center gap-1.5 text-xs text-zinc-500">
              <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} />
              강제 실행
            </label>
            <Button onClick={run} disabled={running}>
              {allPoliciesMode ? '전체 정책 검증 실행' : '검증 실행'}
            </Button>
          </div>
        }
      />

      <InlineError message={error} />

      {policyId && (
        <Card>
          <div className="text-xs text-zinc-400 mb-2">최신 리포트</div>
          {latest ? (
            <div className="flex items-center gap-6">
              <Badge tone={STATUS_TONE[latest.status]}>{STATUS_LABEL[latest.status]}</Badge>
              <div className="text-sm text-zinc-700">
                DB 발급 <b>{comma(latest.totalIssued)}</b> · Redis 예약 <b>{comma(latest.totalReserved)}</b> · 불일치{' '}
                <b className={latest.mismatchCount > 0 ? 'text-zinc-900' : ''}>{comma(latest.mismatchCount)}</b>
              </div>
              <Link
                to={`/admin/verification/reports/${latest.id}`}
                className="text-xs text-zinc-500 hover:text-zinc-900 hover:underline"
              >
                상세
              </Link>
              <div className="text-xs text-zinc-400 ml-auto">{fmtDateTime(latest.runAt)}</div>
            </div>
          ) : (
            <div className="text-xs text-zinc-400">아직 실행된 검증이 없어요 — 위 버튼으로 실행해보세요</div>
          )}
        </Card>
      )}

      <DataTable
        rowKey={(r) => r.id}
        empty="검증 이력이 없어요"
        columns={[
          { key: 'runAt', label: '실행 일시', render: (r) => fmtDateTime(r.runAt) },
          ...(policyId ? [] : [{ key: 'policy', label: '대상 정책', render: (r) => titleOf(r.policyId) }]),
          { key: 'issued', label: 'DB 발급 수', render: (r) => comma(r.totalIssued) },
          { key: 'reserved', label: 'Redis 예약 수', render: (r) => comma(r.totalReserved) },
          { key: 'mismatch', label: '불일치 건수', render: (r) => comma(r.mismatchCount) },
          {
            key: 'status',
            label: '상태',
            render: (r) => <Badge tone={STATUS_TONE[r.status]}>{STATUS_LABEL[r.status]}</Badge>,
          },
          {
            key: 'actions',
            label: '',
            render: (r) => (
              <div className="flex gap-3">
                {r.status !== 'PENDING' && (
                  <Link to={`/admin/verification/reports/${r.id}`} className="text-xs text-zinc-500 hover:text-zinc-900 hover:underline">
                    상세
                  </Link>
                )}
                {r.status !== 'PENDING' && r.reportUrl && (
                  <a href={verificationReportCsvUrl(r.id)} className="text-xs text-zinc-500 hover:text-zinc-900 hover:underline">
                    CSV
                  </a>
                )}
              </div>
            ),
          },
        ]}
        rows={reportsQ.data?.content ?? []}
      />

      <Pagination
        page={reportsQ.data?.page ?? 0}
        totalPages={reportsQ.data?.totalPages ?? 0}
        totalElements={reportsQ.data?.totalElements ?? 0}
        onChange={setPage}
      />

      <div className="text-[11px] text-zinc-400">
        "상세"에서 CSV 다운로드 없이 실행 결과와(불일치가 있다면) 그 목록을 바로 확인할 수 있어요
        {policyId && (
          <>
            {' '}· 등록된 모든 정책을 한 번에 검증하려면{' '}
            <Link to="/admin/verification" className="text-zinc-600 underline underline-offset-2">
              전체 정합성 검증
            </Link>
            에서 실행하세요.
          </>
        )}
      </div>
    </div>
  );
}

export default function AdminVerificationPage() {
  return <VerificationPanel />;
}
