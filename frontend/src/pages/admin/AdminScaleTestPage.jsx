import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  deleteScaleTest,
  seedScaleTest,
  getScaleTestStatus,
  verifyAllScaleTest,
} from '../../lib/endpoints.js';
import { Badge, Button, Card, DataTable, LoadingBlock, PageHeader } from '../../components/ui.jsx';
import { comma } from '../../lib/format.js';

const STATUS_TONE = { SUCCESS: 'ok', MISMATCH_FOUND: 'bad', FAILED: 'bad', PENDING: 'soon' };
const STATUS_LABEL = {
  SUCCESS: '정상(SUCCESS)',
  MISMATCH_FOUND: '불일치 발견',
  FAILED: '실패',
  PENDING: '진행 중',
};

/**
 * 300만 건 규모 정합성 검증 데모 — "딸깍"으로 시딩·삭제·전체 검증까지.
 *
 * <p>ScaleTestService가 실제로 지금 이 앱이 붙어있는 개발 DB/Redis에 그대로 시딩한다(격리된
 * 임시 인프라 아님) — 그래서 삭제 버튼이 필수 짝이다. 시딩·검증 둘 다 수 분씩 걸릴 수 있어서
 * 버튼을 누른 뒤에도 화면을 떠나지만 않으면 되고, react-query가 완료 시 자동으로 최신 상태를
 * 다시 읽어온다.
 */
export default function AdminScaleTestPage() {
  const queryClient = useQueryClient();
  const [error, setError] = useState(null);

  const statusQ = useQuery({
    queryKey: ['scale-test-status'],
    queryFn: getScaleTestStatus,
  });

  const seedMutation = useMutation({
    mutationFn: seedScaleTest,
    onMutate: () => setError(null),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scale-test-status'] }),
    onError: (e) => setError(e.message ?? '시딩 실패'),
  });

  const verifyMutation = useMutation({
    mutationFn: verifyAllScaleTest,
    onMutate: () => setError(null),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scale-test-status'] }),
    onError: (e) => setError(e.message ?? '검증 실패'),
  });

  const deleteMutation = useMutation({
    mutationFn: deleteScaleTest,
    onMutate: () => setError(null),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['scale-test-status'] }),
    onError: (e) => setError(e.message ?? '삭제 실패'),
  });

  const busy = seedMutation.isPending || verifyMutation.isPending || deleteMutation.isPending;
  const scenarios = statusQ.data?.scenarios ?? [];
  const seeded = scenarios.length > 0;
  const anyVerified = scenarios.some((s) => s.verificationStatus != null);

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="300만 건 규모 정합성 검증 데모"
        sub="정상 정책 1개(약 260만 건) + 대표 불일치 유형 4개(각 10만 건 안팎), 합계 300만 건을 지금 이 개발 DB에 직접 채워 넣습니다"
      />

      <Card className="p-5">
        <div className="flex flex-wrap items-center gap-3">
          <Button onClick={() => seedMutation.mutate()} disabled={busy}>
            {seedMutation.isPending ? '시딩 중... (수 분 소요)' : '300만 건 시딩'}
          </Button>
          <Button
            variant="outline"
            onClick={() => verifyMutation.mutate()}
            disabled={busy || !seeded}
          >
            {verifyMutation.isPending ? '전체 검증 중... (최대 몇 분)' : '전체 정합성 검증 실행'}
          </Button>
          <Button
            variant="danger"
            onClick={() => {
              if (confirm('시딩된 300만 건 규모 테스트 데이터를 전부 삭제할까요? (되돌릴 수 없음)')) {
                deleteMutation.mutate();
              }
            }}
            disabled={busy || !seeded}
          >
            {deleteMutation.isPending ? '삭제 중...' : '전부 삭제'}
          </Button>
          <button
            className="text-xs text-sub hover:text-ink hover:underline ml-auto"
            onClick={() => statusQ.refetch()}
            disabled={busy}
          >
            상태 새로고침
          </button>
        </div>
        <p className="text-[11px] text-sub mt-3 leading-relaxed">
          격리된 임시 인프라가 아니라 <b>지금 붙어있는 이 개발 DB/Redis에 그대로</b> 시딩합니다 —
          정책 제목({'"scale-3m-*"'})과 유저 이메일({'"scaletest-user-*"'})에 전용 접두어를 붙여서
          실제 부하테스트 데이터와 절대 섞이지 않게 해뒀고, 삭제 버튼으로 완전히 되돌릴 수
          있습니다. 삭제하기 전까지는 데이터가 그대로 남아있으니, 확인이 끝나면 삭제해두는 걸
          권장합니다.
        </p>
      </Card>

      {error && (
        <Card className="p-4 border-danger-weak bg-danger-weak">
          <p className="text-[13px] font-semibold text-danger">{error}</p>
        </Card>
      )}

      {statusQ.isLoading && <LoadingBlock />}

      {!statusQ.isLoading && !seeded && (
        <Card className="p-6 text-center text-sub text-[13px]">
          아직 시딩된 데이터가 없어요 — 위 "300만 건 시딩" 버튼을 눌러 시작하세요.
        </Card>
      )}

      {seeded && (
        <>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <SummaryTile label="시딩된 정책 수" value={comma(scenarios.length)} />
            <SummaryTile
              label="coupon_issue 합계"
              value={comma(statusQ.data.totalCouponIssueRows)}
            />
            <SummaryTile label="시딩된 유저 수" value={comma(statusQ.data.totalUsersSeeded)} />
            <SummaryTile
              label="검증 완료"
              value={anyVerified ? `${scenarios.filter((s) => s.verificationStatus).length} / ${scenarios.length}` : '아직 안 함'}
            />
          </div>

          <DataTable
            rowKey={(r) => r.policyId}
            columns={[
              {
                key: 'scenarioType',
                label: '시나리오',
                render: (r) => (
                  <div>
                    <div className="font-semibold text-ink">{r.scenarioType}</div>
                    <div className="text-[11px] text-sub mt-0.5">{r.scenarioDescription}</div>
                  </div>
                ),
              },
              { key: 'policyId', label: '정책', render: (r) => `#${r.policyId}` },
              { key: 'totalQuantity', label: '재고', render: (r) => comma(r.totalQuantity) },
              { key: 'couponIssueRows', label: '발급 건수', render: (r) => comma(r.couponIssueRows) },
              {
                key: 'verificationStatus',
                label: '검증 결과',
                render: (r) =>
                  r.verificationStatus ? (
                    <Badge tone={STATUS_TONE[r.verificationStatus] ?? 'plain'}>
                      {STATUS_LABEL[r.verificationStatus] ?? r.verificationStatus}
                    </Badge>
                  ) : (
                    <span className="text-sub">-</span>
                  ),
              },
              {
                key: 'mismatchCount',
                label: '불일치',
                render: (r) => (r.mismatchCount == null ? '-' : comma(r.mismatchCount)),
              },
              {
                key: 'actions',
                label: '',
                render: (r) => (
                  <Link
                    to={`/admin/${r.policyId}?tab=verification`}
                    className="text-xs text-sub hover:text-ink hover:underline"
                  >
                    상세 →
                  </Link>
                ),
              },
            ]}
            rows={scenarios}
          />
        </>
      )}
    </div>
  );
}

function SummaryTile({ label, value }) {
  return (
    <Card className="p-4">
      <p className="text-[11px] text-sub mb-1.5 font-semibold">{label}</p>
      <p className="text-xl font-bold text-ink nums">{value}</p>
    </Card>
  );
}
