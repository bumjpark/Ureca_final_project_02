import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  getCouponIssuanceMetrics,
  getCouponStatus,
  getPolicy,
  listReconciliationLogs,
} from '../../lib/endpoints.js';
import { useAdminPolicies } from '../../lib/hooks.js';
import { LoadingBlock, PageHeader, StatTile, Tabs } from '../../components/ui.jsx';
import IssuanceChart from '../../components/IssuanceChart.jsx';
import PolicyPicker from '../../components/admin/PolicyPicker.jsx';
import InfraStatusBar from '../../components/admin/InfraStatusBar.jsx';
import { QueueControlPanel } from './AdminQueueControlPage.jsx';
import { VerificationPanel } from './AdminVerificationPage.jsx';
import { ReconciliationPanel } from './AdminReconciliationPage.jsx';
import { LoadTestPanel } from './AdminLoadTestPage.jsx';
import { comma } from '../../lib/format.js';

const TIMELINE_SECONDS = 120;

function StatusTab({ policyId }) {
  const statusQ = useQuery({
    queryKey: ['policy-status', policyId],
    queryFn: () => getCouponStatus(policyId),
    refetchInterval: 2000,
  });
  const metricsQ = useQuery({
    queryKey: ['policy-issuance-metrics', policyId],
    queryFn: () => getCouponIssuanceMetrics(policyId, TIMELINE_SECONDS),
    refetchInterval: 1000,
  });

  const s = statusQ.data;
  const m = metricsQ.data;
  const rate = s ? s.issueRate.toFixed(1) : '0.0';

  // 남은 재고를 지금 속도(직전 1초 발급 수)로 나눠 대략적인 소진 예상 시간을 보여준다.
  let etaLabel = '-';
  if (s && m && m.issuedLastSecond > 0 && s.remainingQuantity > 0) {
    const etaSeconds = s.remainingQuantity / m.issuedLastSecond;
    if (etaSeconds < 1) etaLabel = '1초 이내';
    else if (etaSeconds < 60) etaLabel = `약 ${Math.round(etaSeconds)}초 후`;
    else etaLabel = `약 ${Math.round(etaSeconds / 60)}분 후`;
  } else if (s && s.remainingQuantity === 0) {
    etaLabel = '품절';
  }

  if (statusQ.isLoading) return <LoadingBlock />;
  if (!s) return null;

  return (
    <div className="flex gap-4">
      <div className="flex-[1.3] border border-zinc-300 rounded-lg p-5">
        <div className="flex justify-between text-xs text-zinc-400 mb-2">
          <span>발급률</span>
          <span>{rate}%</span>
        </div>
        <div className="h-3.5 bg-zinc-100 rounded-full overflow-hidden">
          <div className="h-full bg-zinc-900" style={{ width: `${Math.min(100, rate)}%` }} />
        </div>
        <div className="flex justify-between text-[11px] text-zinc-400 mt-2">
          <span>0</span>
          <span>{comma(s.totalQuantity)}장</span>
        </div>

        <div className="flex gap-3 mt-4">
          <StatTile label="발급 완료" value={comma(s.issuedQuantity)} />
          <StatTile label="잔여 수량" value={comma(s.remainingQuantity)} />
          <StatTile label="총 발행 수량" value={comma(s.totalQuantity)} />
        </div>
        <div className="flex gap-3 mt-3">
          <StatTile label="사용 완료" value={m ? comma(m.usedCount) : '-'} />
          <StatTile label="만료" value={m ? comma(m.expiredCount) : '-'} />
          <StatTile label="초당 발급 속도" value={m ? `${comma(m.issuedLastSecond)}건/초` : '-'} />
        </div>
        <div className="mt-3">
          <StatTile label="예상 소진 시점(현재 속도 기준)" value={etaLabel} />
        </div>
      </div>

      <div className="flex-1 border border-zinc-300 rounded-lg p-4">
        <div className="text-xs text-zinc-500 font-semibold mb-1">
          실시간 발급 추이 <span className="text-zinc-400 font-normal">(최근 {TIMELINE_SECONDS}초, 1초 단위)</span>
        </div>
        {metricsQ.isLoading ? <LoadingBlock label="그래프 불러오는 중..." /> : <IssuanceChart points={m?.timeline ?? []} />}
      </div>
    </div>
  );
}

// 재처리 탭에 "미해결 몇 건" 뱃지를 붙이기 위한 가벼운 카운트 조회. FAILED/PENDING 각각
// size=1로만 물어 totalElements(전체 건수)를 얻는다 — 목록 자체는 필요 없다.
function useUnresolvedReconciliationCount(policyId) {
  const failedQ = useQuery({
    queryKey: ['reconciliation-count', policyId, 'FAILED'],
    queryFn: () => listReconciliationLogs({ policyId, status: 'FAILED', page: 0, size: 1 }),
    refetchInterval: 5000,
  });
  const pendingQ = useQuery({
    queryKey: ['reconciliation-count', policyId, 'PENDING'],
    queryFn: () => listReconciliationLogs({ policyId, status: 'PENDING', page: 0, size: 1 }),
    refetchInterval: 5000,
  });
  return (failedQ.data?.totalElements ?? 0) + (pendingQ.data?.totalElements ?? 0);
}

const TABS = [
  { value: 'status', label: '현황' },
  { value: 'load-test', label: '부하테스트' },
  { value: 'queue', label: '대기열' },
  { value: 'verification', label: '정합성 검증' },
  { value: 'reconciliation', label: '재처리' },
];

export default function AdminPolicyWorkspacePage() {
  const { policyId: policyIdParam } = useParams();
  const policyId = Number(policyIdParam);
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get('tab') ?? 'status';

  const policiesQ = useAdminPolicies();
  const policyQ = useQuery({ queryKey: ['admin-policy', policyId], queryFn: () => getPolicy(policyId) });
  const unresolvedCount = useUnresolvedReconciliationCount(policyId);

  const tabOptions = TABS.map((t) =>
    t.value === 'reconciliation' && unresolvedCount > 0 ? { ...t, label: `재처리 (${unresolvedCount})` } : t,
  );

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={policyQ.data ? policyQ.data.title : `정책 #${policyId}`}
        sub={`#${policyId}`}
        right={
          <div className="flex items-center gap-3">
            <InfraStatusBar />
            <PolicyPicker
              value={String(policyId)}
              onChange={(id) => navigate(`/admin/${id}?tab=${tab}`)}
              policies={policiesQ.data?.content}
            />
          </div>
        }
      />

      <Tabs options={tabOptions} value={tab} onChange={(v) => setSearchParams({ tab: v })} />

      {tab === 'status' && <StatusTab policyId={policyId} />}
      {tab === 'load-test' && <LoadTestPanel policyId={policyId} />}
      {tab === 'queue' && <QueueControlPanel policyId={policyId} allowGlobalToggle={false} />}
      {tab === 'verification' && <VerificationPanel policyId={policyId} />}
      {tab === 'reconciliation' && <ReconciliationPanel policyId={policyId} />}
    </div>
  );
}
