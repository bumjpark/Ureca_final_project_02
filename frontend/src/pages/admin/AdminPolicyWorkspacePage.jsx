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

function StatusTab({ policyId, policy }) {
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
  // toFixed(1)은 반올림 때문에 99.99%처럼 완전 소진이 아닌데도 "100.0%"로 뭉개져 보이는
  // 문제가 있었다(실측: 9999/10000 발급인데 100%로 표시됨). 백엔드가 이미 소수 둘째자리까지
  // 정확히 계산해서 주므로(CouponStatusService.getCouponStatus) 그 정밀도를 그대로 보여준다.
  const rate = s ? s.issueRate.toFixed(2) : '0.00';

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

  // 이 화면은 출처가 두 개다.
  //   KPI/진행바 = Redis 실시간 재고(총 수량 − stock 키). /issue 성공 즉시 반응한다.
  //   그래프/사용·만료 = DB coupon_issue. Kafka 컨슈머가 행을 넣어야 반응한다.
  // 둘 사이의 간격이 곧 "확정 대기 중"인 건수라, 벌어졌을 때 화면에 드러나게 한다.
  const dbIssued = m?.totalIssuedEver ?? null;
  // Redis stock 키가 통째로 없으면 remaining=총수량으로 방어돼 발급 0으로 보인다 — DB에는
  // 발급이 있는데 KPI만 0인 이 상태를 "정상"으로 오해하지 않도록 따로 잡아낸다.
  const redisStockMissing =
    dbIssued != null && dbIssued > 0 && s.remainingQuantity === s.totalQuantity;

  return (
    <div className="space-y-4">
      {redisStockMissing && (
        <Card className="p-4 ring-1 ring-inset ring-danger">
          <p className="text-[14px] font-bold text-danger">아래 KPI를 믿지 마세요 — Redis 재고 키가 없습니다</p>
          <p className="text-[13px] text-sub mt-1.5 leading-relaxed">
            DB에는 발급이 <b className="text-ink nums">{comma(dbIssued)}</b>건 있는데 Redis 재고가 총 수량 그대로라
            발급 0건으로 계산되고 있어요. 자동 복구 스케줄러가 5초 주기로 재고를 다시 만들어 줍니다 —
            잠시 뒤에도 그대로면 Redis 상태를 확인하세요. 아래 그래프(DB 기준)는 이 영향을 받지 않습니다.
          </p>
        </Card>
      )}

      {/* KPI — dev 대시보드처럼 큰 숫자가 주인공. 재고 소진이면 레드로 전환된다.
          카드마다 출처가 달라서(재고는 Redis, 속도는 DB) 카드 단위로 표시한다. */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Kpi
          label="발급 완료"
          value={comma(s.issuedQuantity)}
          sub={`총 ${comma(s.totalQuantity)}장`}
          tone="mint"
          hero
          source="redis"
        />
        <Kpi
          label="잔여 수량"
          value={comma(s.remainingQuantity)}
          sub={soldOut ? '재고 소진' : `발급률 ${rate}%`}
          tone={soldOut ? 'danger' : 'plain'}
          source="redis"
        />
        <Kpi
          label="초당 발급 속도"
          value={m ? `${comma(m.issuedLastSecond)}` : '-'}
          sub="건/초 · 직전 1초"
          source="db"
        />
        <Kpi label="예상 소진 시점" value={etaLabel} sub="현재 속도 기준" small source="mixed" />
      </div>

      {/* 발급률 진행바 */}
      <Card className="p-5">
        <div className="flex items-end justify-between mb-2">
          <div>
            <p className="text-[12px] font-bold text-sub flex items-center gap-2">
              발급률 <SourceTag source="redis" />
            </p>
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
          <h2 className="text-[15px] font-bold text-ink flex items-center gap-2">
            실시간 발급 추이 <SourceTag source="db" />
          </h2>
          <Badge tone={policy?.status === 'BEFORE_OPEN' ? 'soon' : soldOut ? 'done' : 'live'}>
            {policy?.status === 'BEFORE_OPEN' ? '오픈 전' : soldOut ? '소진 · 정지' : '발급 중'}
          </Badge>
        </div>
        <p className="text-[12px] text-sub mb-3">
          최근 {TIMELINE_SECONDS}초, 1초 단위 확정 건수 · DB에 확정된 시점이 아니라 발급 요청이 성공한
          시각(issued_at) 기준으로 묶습니다
        </p>
        {metricsQ.isLoading ? (
          <LoadingBlock label="그래프 불러오는 중..." />
        ) : (
          <IssuanceChart points={m?.timeline ?? []} />
        )}
      </Card>

      {/* 부가 지표 — 셋 다 DB 출처라 묶어서 한 번만 표시한다 */}
      <div className="flex items-center gap-2 pt-1">
        <h2 className="text-[15px] font-bold text-ink">부가 지표</h2>
        <SourceTag source="db" />
      </div>
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-4">
        <StatTile label="사용 완료" value={m ? comma(m.usedCount) : '-'} />
        <StatTile label="만료" value={m ? comma(m.expiredCount) : '-'} />
        <StatTile label="총 발행 수량" value={comma(s.totalQuantity)} />
      </div>

      {/* 발급 소요 시간 — DB(created_at 기준) 반영 완료까지만 보여준다. Redis 완료 시간은
          제외했다: Redis는 요청 성공 즉시라 사실상 항상 0에 가깝고, 실제로 궁금한 건 "사용자가
          체감하는 전체 지연(E2E)"이기 때문이다. */}
      {m && m.dbElapsedMs != null && (
        <>
          <div className="flex items-center gap-2 pt-1">
            <h2 className="text-[15px] font-bold text-ink">발급 소요 시간</h2>
          </div>
          <div className="grid grid-cols-1 max-w-md gap-4">
            <ElapsedCard
              label="DB 반영 완료 (E2E)"
              ms={m.dbElapsedMs}
              sub="첫 issued_at ~ 마지막 created_at"
              desc="Redis 발급 시작부터 Kafka Consumer가 마지막 건을 DB에 넣을 때까지 전체 지연"
              source="db"
            />
          </div>
        </>
      )}
    </div>
  );
}

// 숫자의 출처. 같은 화면인데 Redis(실시간 재고)와 DB(coupon_issue 집계)가 섞여 있어,
// 어느 쪽을 보고 있는지 모르면 둘이 어긋난 순간을 버그로 오해하게 된다.
const SOURCE_META = {
  redis: {
    label: 'Redis',
    title: 'Redis 실시간 재고(총 수량 − stock 키) 기준 — /issue 성공 즉시 반영됩니다.',
  },
  db: {
    label: 'DB',
    title: 'DB coupon_issue 테이블 집계 기준 — Kafka 컨슈머가 행을 넣은 뒤 반영됩니다.',
  },
  mixed: {
    label: 'Redis+DB',
    title: 'Redis 잔여 재고를 DB 기준 초당 발급 속도로 나눈 값이라 두 출처가 함께 쓰입니다.',
  },
};

export function SourceTag({ source }) {
  const meta = SOURCE_META[source];
  if (!meta) return null;
  return (
    <span
      title={meta.title}
      className="inline-flex items-center h-5 px-1.5 rounded text-[10px] font-bold bg-surface text-sub cursor-help"
    >
      {meta.label}
    </span>
  );
}

// dev 대시보드의 KPI 카드 — 라벨은 작게, 숫자가 주인공(nums로 자릿수 흔들림 방지).
function Kpi({ label, value, sub, tone = 'plain', hero, small, source }) {
  const color = tone === 'mint' ? 'text-mint' : tone === 'danger' ? 'text-danger' : 'text-ink';
  const ring = hero ? `ring-1 ring-inset ${tone === 'danger' ? 'ring-danger' : 'ring-mint'}` : '';
  return (
    <Card className={`p-5 ${ring}`}>
      <div className="flex items-center justify-between gap-2">
        <p className="text-[12px] font-bold text-sub">{label}</p>
        <SourceTag source={source} />
      </div>
      <p className={`mt-2 ${small ? 'text-[22px]' : 'text-[36px]'} leading-none font-extrabold nums ${color}`}>
        {value}
      </p>
      <p className="mt-2 text-[12px] text-sub">{sub}</p>
    </Card>
  );
}

// 소요 시간 카드 — ms를 사람이 읽기 쉬운 형태로 변환해 보여준다.
function ElapsedCard({ label, ms, sub, desc, source }) {
  let display = '-';
  let detail = '';
  if (ms != null) {
    if (ms < 1000) {
      display = `${ms}ms`;
    } else {
      const sec = (ms / 1000).toFixed(2);
      display = `${sec}초`;
      detail = `${Number(ms).toLocaleString('ko-KR')}ms`;
    }
  }
  return (
    <Card className="p-5">
      <div className="flex items-center justify-between gap-2">
        <p className="text-[12px] font-bold text-sub">{label}</p>
        <SourceTag source={source} />
      </div>
      <p className="mt-2 text-[28px] leading-none font-extrabold nums text-ink">{display}</p>
      {detail && <p className="mt-1 text-[13px] font-bold nums text-sub">{detail}</p>}
      <p className="mt-2 text-[11px] text-sub leading-snug">{sub}</p>
      <p className="mt-0.5 text-[11px] text-sub/60 leading-snug">{desc}</p>
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

      {tab === 'status' && <StatusTab policyId={policyId} policy={policyQ.data} />}
      {tab === 'load-test' && <LoadTestPanel policyId={policyId} />}
      {tab === 'queue' && <QueueControlPanel policyId={policyId} allowGlobalToggle={false} />}
      {tab === 'verification' && <VerificationPanel policyId={policyId} />}
      {tab === 'reconciliation' && <ReconciliationPanel policyId={policyId} />}
    </div>
  );
}
