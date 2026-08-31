import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { useDemo } from '../lib/demo.jsx';
import {
  getHealth,
  getPolicy,
  listReconciliationLogs,
  listVerificationReports,
} from '../lib/endpoints.js';
import { comma, fmtClock } from '../lib/format.js';
import { Card, EvidenceNote, GapNotice, Pill } from '../components/ui.jsx';
import LoadSimulator from '../components/LoadSimulator.jsx';
import { subscribeBurst } from '../lib/loadgen.js';
import { getMetrics, resetMetrics, startMetrics, subscribeMetrics } from '../lib/metricsStore.js';

const MINT = '#00C4B8';
const DANGER = '#FF4D4F';
const SUB = '#8B95A1';
const LINE = '#E5E8EB';

export default function DashboardPage() {
  const { policyId } = useDemo();

  const policyQ = useQuery({
    queryKey: ['policy', policyId],
    queryFn: () => getPolicy(policyId),
    enabled: !!policyId,
  });

  const reportsQ = useQuery({
    queryKey: ['verification-reports', policyId],
    queryFn: () => listVerificationReports({ policyId, size: 5 }),
    enabled: !!policyId,
    refetchInterval: 10_000,
  });

  const reconQ = useQuery({
    queryKey: ['reconciliation-summary'],
    queryFn: async () => {
      const [all, dlt, dltOk, republish, pending, failed] = await Promise.all([
        listReconciliationLogs({ size: 1 }),
        listReconciliationLogs({ type: 'DLT_REPROCESS', size: 1 }),
        listReconciliationLogs({ type: 'DLT_REPROCESS', status: 'SUCCESS', size: 1 }),
        listReconciliationLogs({ type: 'EVENT_REPUBLISH', size: 1 }),
        listReconciliationLogs({ status: 'PENDING', size: 1 }),
        listReconciliationLogs({ status: 'FAILED', size: 1 }),
      ]);
      return {
        all: all.totalElements,
        dlt: dlt.totalElements,
        dltOk: dltOk.totalElements,
        republish: republish.totalElements,
        pending: pending.totalElements,
        failed: failed.totalElements,
      };
    },
    refetchInterval: 5000,
  });

  const healthQ = useQuery({
    queryKey: ['health-deep'],
    queryFn: () => getHealth(true),
    refetchInterval: 5000,
  });

  const policy = policyQ.data;

  // ── 시계열 + KPI: 모듈 싱글턴 스토어에서 (화면 이동해도 유지, 폴링도 스토어가 소유) ──
  const [m, setM] = useState(getMetrics);
  useEffect(() => {
    startMetrics(policyId); // 정책 바뀌면 스토어가 알아서 리셋
    return subscribeMetrics(setM);
  }, [policyId]);
  // 부하 시뮬레이터 "부하 시작" 순간 → 0에서 다시
  useEffect(() => {
    let prev = false;
    return subscribeBurst((st) => {
      if (st.running && !prev) resetMetrics();
      prev = st.running;
    });
  }, []);

  const total = m.total || policy?.totalQuantity || 0;
  const issued = m.issued ?? 0;
  const remaining = m.remaining ?? 0;
  const issueRate = m.issueRate ?? 0;
  const series = m.points;
  const seriesDone = m.done;

  const latestReport = (reportsQ.data?.content ?? []).find((r) => r.status !== 'PENDING');
  const reportOversold = latestReport?.oversoldCount ?? 0;
  const liveOversold = Math.max(0, issued - total);
  const oversold = Math.max(reportOversold, liveOversold);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-[22px] font-extrabold text-ink">관제 대시보드</h1>
          <p className="text-[13px] text-sub mt-1">
            #{policyId} {policy?.title ?? ''} · 부하테스트 시연용 ·{' '}
            {seriesDone
              ? '발급 완료 — 그래프 정지'
              : `갱신 ${m.updatedAt ? fmtClock(new Date(m.updatedAt).toISOString()) : '-'}`}
          </p>
        </div>
        <HealthStrip health={healthQ.data} />
      </div>

      {/* 동시 발급 시뮬레이터 — 브라우저에서 부하를 만들어 아래 그래프를 움직인다 */}
      <LoadSimulator policyId={policyId} />

      {/* KPI 카드 4개 */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Kpi label="총 발급 / 재고" value={comma(issued)} sub={`총 재고 ${comma(total)} · 남은 재고 ${comma(remaining)}`} />
        <Kpi label="남은 재고" value={comma(remaining)} sub={remaining === 0 ? '재고 소진' : `발급률 ${issueRate}%`} tone={remaining === 0 ? 'danger' : 'plain'} />
        <Kpi
          label="초과 발급"
          value={comma(oversold)}
          sub={oversold === 0 ? 'NFR-1 · 초과 발급 0건 유지' : '초과 발급 발생 — 즉시 확인 필요'}
          tone={oversold === 0 ? 'mint' : 'danger'}
          hero
        />
        <Kpi label="발급률" value={`${issueRate}%`} sub={`${comma(issued)} / ${comma(total)}`} />
      </div>

      {/* 부가 지표 — 백엔드가 별도 카운터를 노출하지 않음 */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <GapKpi label="중복 차단 건수" note="Redis Lua가 409로 거절하지만 누적 카운터는 미노출" />
        <GapKpi label="재고 소진 거절 건수" note="Lua Fast-Fail이 400으로 거절하지만 누적 카운터는 미노출" />
        <GapKpi label="초당 요청 수 (RPS)" note="애플리케이션 메트릭(actuator/micrometer) 미탑재 — k6 요약 참조" />
        <Card className="p-4">
          <p className="text-[12px] font-bold text-sub">Consumer 반영 완료</p>
          <p className="mt-1 text-[28px] font-extrabold text-ink nums">{comma(issued)}</p>
          <p className="mt-1 text-[11px] text-sub">coupon_issue 확정 건수 = DB 반영 완료</p>
        </Card>
      </div>

      {/* 라인 차트 */}
      <Card className="p-5">
        <div className="flex items-center justify-between mb-1">
          <h2 className="text-[15px] font-bold text-ink">누적 발급 확정 수</h2>
          <Pill tone={seriesDone ? 'mint' : 'plain'}>
            {seriesDone ? `발급 완료 · ${comma(total)}에서 정지` : '증가 중'}
          </Pill>
        </div>
        <p className="text-[12px] text-sub mb-3">
          Consumer가 DB에 확정한 누적 건수. {comma(total)}에 도달하면 그래프가 그 지점에서 멈춘다 — 초과 발급 0건.
        </p>
        <div className="h-[260px]">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={series} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
              <defs>
                <linearGradient id="fill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={MINT} stopOpacity={0.18} />
                  <stop offset="100%" stopColor={MINT} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke={LINE} vertical={false} />
              <XAxis dataKey="clock" tick={{ fill: SUB, fontSize: 11 }} stroke={LINE} minTickGap={40} />
              <YAxis
                tick={{ fill: SUB, fontSize: 11 }}
                stroke={LINE}
                width={52}
                domain={[0, total > 0 ? Math.ceil(total * 1.05) : 'auto']}
                tickFormatter={(v) => comma(v)}
              />
              <Tooltip content={<ChartTooltip />} />
              {total > 0 && (
                <ReferenceLine
                  y={total}
                  stroke={DANGER}
                  strokeDasharray="4 4"
                  label={{ value: `재고 ${comma(total)}`, fill: DANGER, fontSize: 11, position: 'insideTopRight' }}
                />
              )}
              <Area type="monotone" dataKey="issued" stroke={MINT} strokeWidth={2} fill="url(#fill)" isAnimationActive={false} name="누적 발급 확정" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </Card>

      <div className="grid lg:grid-cols-2 gap-4">
        {/* 초당 발급 확정 */}
        <Card className="p-5">
          <div className="flex items-center justify-between mb-1">
            <h2 className="text-[15px] font-bold text-ink">초당 발급 확정 수</h2>
            {seriesDone && <Pill tone="mint">발급 완료 · 정지</Pill>}
          </div>
          <p className="text-[12px] text-sub mb-3">폴링 간격 사이의 증가분 / 경과 시간 (Consumer 처리량)</p>
          <div className="h-[220px]">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={series} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
                <CartesianGrid stroke={LINE} vertical={false} />
                <XAxis dataKey="clock" tick={{ fill: SUB, fontSize: 11 }} stroke={LINE} minTickGap={40} />
                <YAxis tick={{ fill: SUB, fontSize: 11 }} stroke={LINE} width={44} allowDecimals={false} />
                <Tooltip content={<ChartTooltip unit="건/s" />} />
                <Bar dataKey="rps" fill={MINT} isAnimationActive={false} name="초당 발급 확정" radius={[2, 2, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>

        {/* 발급 결과 대사 (도넛) */}
        <Card className="p-5">
          <h2 className="text-[15px] font-bold text-ink">발급 결과 대사</h2>
          <p className="text-[12px] text-sub mb-3">
            {latestReport
              ? '최근 정합성 검증 리포트 기준 (이력 ↔ 재고)'
              : '검증 리포트 없음 — 발급 확정 / 잔여 재고로 표시'}
          </p>
          <ReconcileDonut report={latestReport} issued={issued} remaining={remaining} />
        </Card>
      </div>

      {/* Kafka 처리 현황 */}
      <Card className="p-5">
        <h2 className="text-[15px] font-bold text-ink mb-1">Kafka 처리 현황</h2>
        <p className="text-[12px] text-sub mb-4">
          reconciliation_log 집계 (재발행 실패건 / DLT 재처리 / 상태 불일치 재처리 / Redis 복구)
        </p>
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          <MiniStat label="재처리 큐 전체" value={reconQ.data?.all} />
          <MiniStat label="DLT 적재/재처리 대상" value={reconQ.data?.dlt} tone={reconQ.data?.dlt ? 'danger' : 'plain'} />
          <MiniStat label="DLT 재처리 성공" value={reconQ.data?.dltOk} tone="mint" />
          <MiniStat label="발행 실패 재발행" value={reconQ.data?.republish} />
          <MiniStat label="대기 / 실패" value={fmtPair(reconQ.data?.pending, reconQ.data?.failed)} raw />
        </div>
      </Card>

      <EvidenceNote>
        이 대시보드는 <b>부하테스트 시연</b>용이다. KPI·차트는 <code>GET /api/coupon-policies/&#123;id&#125;/status</code>
        (DB 정책 + Redis 실시간 재고)를 1초 폴링해 그린다. "초과 발급" 카드는 실시간 <code>issued − total</code>과
        최근 검증 리포트의 <code>oversoldCount</code> 중 큰 값을 쓴다. 중복/소진 거절 누적 건수와 RPS는 백엔드가
        메트릭으로 노출하지 않아 <b>미집계</b>로 표시한다.
      </EvidenceNote>
    </div>
  );
}

function Kpi({ label, value, sub, tone = 'plain', hero }) {
  const color = tone === 'mint' ? 'text-mint' : tone === 'danger' ? 'text-danger' : 'text-ink';
  return (
    <Card className={`p-5 ${hero ? 'ring-1 ring-inset ' + (tone === 'danger' ? 'ring-danger' : 'ring-mint') : ''}`}>
      <p className="text-[12px] font-bold text-sub">{label}</p>
      <p className={`mt-2 text-[36px] leading-none font-extrabold nums ${color}`}>{value}</p>
      <p className="mt-2 text-[12px] text-sub">{sub}</p>
    </Card>
  );
}

function GapKpi({ label, note }) {
  return (
    <Card className="p-4">
      <p className="text-[12px] font-bold text-sub">{label}</p>
      <p className="mt-1 text-[28px] font-extrabold text-line nums">—</p>
      <div className="mt-1">
        <GapNotice>{note}</GapNotice>
      </div>
    </Card>
  );
}

function MiniStat({ label, value, tone = 'plain', raw }) {
  const color = tone === 'mint' ? 'text-mint' : tone === 'danger' ? 'text-danger' : 'text-ink';
  return (
    <div className="rounded-btn bg-surface p-3">
      <p className="text-[12px] font-bold text-sub">{label}</p>
      <p className={`mt-1 text-[22px] font-extrabold nums ${color}`}>
        {value == null ? '…' : raw ? value : comma(value)}
      </p>
    </div>
  );
}

const fmtPair = (a, b) => (a == null || b == null ? null : `${comma(a)} / ${comma(b)}`);

function HealthStrip({ health }) {
  const comps = health?.components ?? {};
  const keys = Object.keys(comps);
  return (
    <div className="flex items-center gap-2">
      {keys.length === 0 && <Pill>헬스 조회 대기</Pill>}
      {keys.map((k) => (
        <span
          key={k}
          className={`inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-[12px] font-bold ${
            comps[k].status === 'UP' ? 'bg-mint-weak text-mint' : 'bg-danger-weak text-danger'
          }`}
        >
          <span className={`h-1.5 w-1.5 rounded-full ${comps[k].status === 'UP' ? 'bg-mint' : 'bg-danger'}`} />
          {k}
        </span>
      ))}
    </div>
  );
}

function ReconcileDonut({ report, issued, remaining }) {
  let data;
  if (report) {
    const mismatch = report.mismatchCount ?? 0;
    const oversold = report.oversoldCount ?? 0;
    const normal = Math.max(0, (report.totalIssued ?? 0) - mismatch);
    data = [
      { name: '정상 발급', value: normal, color: MINT },
      { name: '불일치', value: mismatch, color: DANGER },
      { name: '초과 발급', value: oversold, color: '#191F28' },
    ];
  } else {
    data = [
      { name: '발급 확정', value: issued, color: MINT },
      { name: '잔여 재고', value: remaining, color: LINE },
    ];
  }
  const shown = data.filter((d) => d.value > 0);
  const totalV = data.reduce((a, b) => a + b.value, 0);

  return (
    <div className="flex items-center gap-6">
      <div className="h-[160px] w-[160px] shrink-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={shown.length ? shown : [{ name: '없음', value: 1, color: LINE }]} dataKey="value" innerRadius={48} outerRadius={72} paddingAngle={2} isAnimationActive={false}>
              {(shown.length ? shown : [{ color: LINE }]).map((d, i) => (
                <Cell key={i} fill={d.color} />
              ))}
            </Pie>
            <Tooltip content={<ChartTooltip />} />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="space-y-2 text-[13px]">
        {data.map((d) => (
          <li key={d.name} className="flex items-center gap-2">
            <span className="h-2.5 w-2.5 rounded-sm" style={{ background: d.color }} />
            <span className="text-sub">{d.name}</span>
            <span className="font-extrabold text-ink nums">{comma(d.value)}</span>
            <span className="text-sub nums">
              {totalV > 0 ? `(${((d.value / totalV) * 100).toFixed(1)}%)` : ''}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ChartTooltip({ active, payload, label, unit }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-lg bg-white border border-line shadow-card px-3 py-2 text-[12px]">
      {label && <p className="text-sub mb-1 nums">{label}</p>}
      {payload.map((p, i) => (
        <p key={i} className="font-bold text-ink nums">
          {p.name}: {comma(p.value)}
          {unit ? ` ${unit}` : ''}
        </p>
      ))}
    </div>
  );
}
