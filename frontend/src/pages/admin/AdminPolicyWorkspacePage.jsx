import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  getCouponIssuanceMetrics,
  getCouponStatus,
  getPolicy,
  listReconciliationLogs,
} from '../../lib/endpoints.js';
import { useAdminPolicies } from '../../lib/hooks.js';
import { Badge, Card, LoadingBlock, PageHeader, ProgressBar, StatTile, Tabs } from '../../components/ui.jsx';
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

  const soldOut = s.remainingQuantity === 0;

  return (
    <div className="space-y-4">
      {/* KPI — dev 대시보드처럼 큰 숫자가 주인공. 재고 소진이면 레드로 전환된다. */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Kpi label="발급 완료" value={comma(s.issuedQuantity)} sub={`총 ${comma(s.totalQuantity)}장`} tone="mint" hero />
        <Kpi
          label="잔여 수량"
          value={comma(s.remainingQuantity)}
          sub={soldOut ? '재고 소진' : `발급률 ${rate}%`}
          tone={soldOut ? 'danger' : 'plain'}
        />
        <Kpi label="초당 발급 속도" value={m ? `${comma(m.issuedLastSecond)}` : '-'} sub="건/초 · 직전 1초" />
        <Kpi label="예상 소진 시점" value={etaLabel} sub="현재 속도 기준" small />
      </div>

      {/* 발급률 진행바 */}
      <Card className="p-5">
        <div className="flex items-end justify-between mb-2">
          <div>
            <p className="text-[12px] font-bold text-sub">발급률</p>
            <p className="mt-1 text-[15px] font-bold text-ink nums">
              {comma(s.issuedQuantity)} / {comma(s.totalQuantity)}장
            </p>
          </div>
          <span className={`text-[28px] font-extrabold nums ${soldOut ? 'text-danger' : 'text-mint'}`}>{rate}%</span>
        </div>
        <ProgressBar value={s.issuedQuantity} max={s.totalQuantity} tone={soldOut ? 'danger' : 'mint'} />
      </Card>

      {/* 실시간 추이 */}
      <Card className="p-5">
        <div className="flex items-center justify-between mb-1">
          <h2 className="text-[15px] font-bold text-ink">실시간 발급 추이</h2>
          <Badge tone={soldOut ? 'done' : 'live'}>{soldOut ? '소진 · 정지' : '발급 중'}</Badge>
        </div>
        <p className="text-[12px] text-sub mb-3">최근 {TIMELINE_SECONDS}초, 1초 단위 확정 건수</p>
        {metricsQ.isLoading ? (
          <LoadingBlock label="그래프 불러오는 중..." />
        ) : (
          <IssuanceChart points={m?.timeline ?? []} />
        )}
      </Card>

      {/* 부가 지표 */}
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-4">
        <StatTile label="사용 완료" value={m ? comma(m.usedCount) : '-'} />
        <StatTile label="만료" value={m ? comma(m.expiredCount) : '-'} />
        <StatTile label="총 발행 수량" value={comma(s.totalQuantity)} />
      </div>
    </div>
  );
}

// dev 대시보드의 KPI 카드 — 라벨은 작게, 숫자가 주인공(nums로 자릿수 흔들림 방지).
function Kpi({ label, value, sub, tone = 'plain', hero, small }) {
  const color = tone === 'mint' ? 'text-mint' : tone === 'danger' ? 'text-danger' : 'text-ink';
  const ring = hero ? `ring-1 ring-inset ${tone === 'danger' ? 'ring-danger' : 'ring-mint'}` : '';
  return (
    <Card className={`p-5 ${ring}`}>
      <p className="text-[12px] font-bold text-sub">{label}</p>
      <p className={`mt-2 ${small ? 'text-[22px]' : 'text-[36px]'} leading-none font-extrabold nums ${color}`}>
        {value}
      </p>
      <p className="mt-2 text-[12px] text-sub">{sub}</p>
    </Card>
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
