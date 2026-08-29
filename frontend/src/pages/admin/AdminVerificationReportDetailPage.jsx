import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { getVerificationMismatches, getVerificationReport, verificationReportCsvUrl } from '../../lib/endpoints.js';
import { Badge, Card, DataTable, LoadingBlock, PageHeader } from '../../components/ui.jsx';
import Pagination from '../../components/Pagination.jsx';
import { comma, fmtDateTime } from '../../lib/format.js';

const STATUS_TONE = { SUCCESS: 'ok', MISMATCH_FOUND: 'bad', FAILED: 'bad', PENDING: 'soon' };
const STATUS_LABEL = {
  SUCCESS: '성공 (SUCCESS)',
  MISMATCH_FOUND: '불일치 발견 (MISMATCH_FOUND)',
  FAILED: '실패 (FAILED)',
  PENDING: '진행 중 (PENDING)',
};

// discrepancyType이 "OVERSOLD(+3)"처럼 괄호로 건수를 담고 있을 수 있어 뱃지 톤 매칭은 접두어로.
function discrepancyTone(type) {
  if (type.startsWith('OVERSOLD') || type.startsWith('STOCK_LEAK')) return 'bad';
  if (type === 'HISTORY_MISMATCH' || type === 'MISSING_HISTORY') return 'bad';
  return 'soon';
}

/** 검증 리포트 상세 — "상세"를 누르면 이 화면으로 이동해서 불일치 목록을 페이지네이션으로 본다. */
export default function AdminVerificationReportDetailPage() {
  const { reportId } = useParams();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);

  const reportQ = useQuery({
    queryKey: ['verification-report', reportId],
    queryFn: () => getVerificationReport(reportId),
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 3000 : false),
  });
  const report = reportQ.data;

  const mismatchesQ = useQuery({
    queryKey: ['verification-mismatches', reportId, page],
    queryFn: () => getVerificationMismatches(reportId, page, 20),
    enabled: report?.status === 'MISMATCH_FOUND',
  });

  if (reportQ.isLoading) return <LoadingBlock />;
  if (!report) return null;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title={`검증 리포트 #${report.id}`}
        sub={`정책 #${report.policyId} · ${fmtDateTime(report.runAt)}`}
        right={
          <button
            className="text-xs text-zinc-500 hover:text-zinc-900 hover:underline"
            onClick={() => navigate(`/admin/${report.policyId}?tab=verification`)}
          >
            ← 이 정책의 검증 탭으로
          </button>
        }
      />

      <Card>
        <div className="flex items-center gap-6 flex-wrap">
          <Badge tone={STATUS_TONE[report.status]}>{STATUS_LABEL[report.status]}</Badge>
          <div className="text-sm text-zinc-700">
            DB 발급 <b>{comma(report.totalIssued)}</b> / 총 발행 <b>{comma(report.totalQuantity)}</b> · Redis 예약{' '}
            <b>{comma(report.totalReserved)}</b> · 불일치 <b className={report.mismatchCount > 0 ? 'text-zinc-900' : ''}>{comma(report.mismatchCount)}</b>
            {report.oversoldCount > 0 && (
              <>
                {' '}
                · 초과발급 <b className="text-zinc-900">{comma(report.oversoldCount)}</b>
              </>
            )}
          </div>
          {report.status !== 'PENDING' && report.reportUrl && (
            <a
              href={verificationReportCsvUrl(report.id)}
              className="text-xs text-zinc-500 hover:text-zinc-900 hover:underline ml-auto"
            >
              CSV 다운로드
            </a>
          )}
        </div>
        {report.status === 'FAILED' && report.failureReason && (
          <div className="text-xs text-zinc-500 mt-3 border-t border-zinc-100 pt-3">실패 사유: {report.failureReason}</div>
        )}
        {report.status === 'PENDING' && (
          <div className="text-xs text-zinc-400 mt-3 border-t border-zinc-100 pt-3">검증이 아직 진행 중이에요 — 자동으로 갱신됩니다</div>
        )}
      </Card>

      {report.status === 'MISMATCH_FOUND' && (
        <div className="flex flex-col gap-3">
          <div className="text-sm font-semibold text-zinc-900">불일치 목록</div>

          <DataTable
            rowKey={(r) => `${r.userId ?? 'policy'}-${r.couponIssueId ?? '-'}-${r.discrepancyType}`}
            empty="불일치 내역이 없어요"
            columns={[
              {
                key: 'discrepancyType',
                label: '유형',
                render: (r) => <Badge tone={discrepancyTone(r.discrepancyType)}>{r.discrepancyType}</Badge>,
              },
              { key: 'userId', label: 'userId', render: (r) => r.userId ?? '-' },
              { key: 'couponIssueId', label: 'couponIssueId', render: (r) => r.couponIssueId ?? '-' },
              { key: 'detectedAt', label: '검출 시각', render: (r) => fmtDateTime(r.detectedAt) },
            ]}
            rows={mismatchesQ.data?.content ?? []}
          />

          <Pagination
            page={mismatchesQ.data?.page ?? 0}
            totalPages={mismatchesQ.data?.totalPages ?? 0}
            totalElements={mismatchesQ.data?.totalElements ?? 0}
            onChange={setPage}
          />

          <div className="text-[11px] text-zinc-400">
            이 불일치는 재처리 큐(reconciliation)에 자동으로 등록되지 않아요 — 재처리 큐는 카프카 발행/소비
            실패 이벤트만 별도로 쌓는 구조라서, 여기서 잡힌 불일치는 수동으로 원인을 확인하고 필요하면 직접
            재처리를 접수해야 해요.
          </div>
        </div>
      )}
    </div>
  );
}
