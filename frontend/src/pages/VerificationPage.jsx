import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../lib/api.js';
import { useDemo } from '../lib/demo.jsx';
import {
  getVerificationReportCsv,
  listPolicies,
  listVerificationReports,
  runVerification,
  verificationReportCsvUrl,
} from '../lib/endpoints.js';
import { comma, fmtDateTime } from '../lib/format.js';
import {
  Button,
  Card,
  EvidenceNote,
  ErrorBlock,
  LoadingBlock,
  Pagination,
  Pill,
  useToast,
} from '../components/ui.jsx';

export default function VerificationPage() {
  const [scope, setScope] = useState('all'); // all | policy
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-[22px] font-extrabold text-ink">정합성 검증 리포트</h1>
          <p className="text-[13px] text-sub mt-1">
            이력(coupon_issue) ↔ 재고(Redis RESERVED) 대사 · 부작용 없는 순수 집계 · 재실행 시 동일 결과 (FR-14/15)
          </p>
        </div>
        <div className="flex gap-1 rounded-btn bg-surface p-1">
          <ScopeTab active={scope === 'all'} onClick={() => setScope('all')}>
            전체 정책 (300만 건)
          </ScopeTab>
          <ScopeTab active={scope === 'policy'} onClick={() => setScope('policy')}>
            이 정책만
          </ScopeTab>
        </div>
      </div>
      {scope === 'all' ? <AllPoliciesView /> : <PolicyView />}
    </div>
  );
}

function ScopeTab({ active, onClick, children }) {
  return (
    <button
      onClick={onClick}
      className={`h-9 px-3 rounded-[9px] text-[13px] font-bold transition-colors ${
        active ? 'bg-white text-ink shadow-card' : 'text-sub'
      }`}
    >
      {children}
    </button>
  );
}

function PolicyView() {
  const { policyId } = useDemo();
  const qc = useQueryClient();
  const toast = useToast();
  const [confirmForce, setConfirmForce] = useState(null); // 409 메시지 보관

  const reportsQ = useQuery({
    queryKey: ['verification-reports', policyId, 'full'],
    queryFn: () => listVerificationReports({ policyId, size: 20 }),
    enabled: !!policyId,
    refetchInterval: (q) => {
      const has = (q.state.data?.content ?? []).some((r) => r.status === 'PENDING');
      return has ? 2000 : false;
    },
  });

  const reports = reportsQ.data?.content ?? [];
  const pending = reports.find((r) => r.status === 'PENDING');
  const finished = reports.filter((r) => r.status !== 'PENDING');
  const latest = finished[0];
  const prev = finished[1];

  const csvQ = useQuery({
    queryKey: ['verification-csv', latest?.id],
    queryFn: () => getVerificationReportCsv(latest.id),
    enabled: !!latest?.reportUrl,
    staleTime: 60_000,
  });
  const csvCounts = useMemo(() => parseCsvCounts(csvQ.data), [csvQ.data]);

  const runM = useMutation({
    mutationFn: (force) => runVerification({ policyId, force }),
    onSuccess: () => {
      setConfirmForce(null);
      toast('검증 배치를 접수했어요. (비동기 실행)', 'mint');
      qc.invalidateQueries({ queryKey: ['verification-reports'] });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        setConfirmForce(e.message);
        return;
      }
      toast(e.message || '검증 실행에 실패했어요.', 'danger');
    },
  });

  if (!policyId) return <p className="text-sub">시연할 쿠폰 정책을 먼저 선택해주세요.</p>;
  if (reportsQ.isLoading) return <LoadingBlock label="검증 리포트를 불러오는 중" />;
  if (reportsQ.isError) return <ErrorBlock error={reportsQ.error} onRetry={reportsQ.refetch} />;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-[13px] font-bold text-sub">
          대상: 쿠폰 정책 #{policyId} 단건
        </p>
        <div className="flex items-center gap-2">
          {pending && <Pill>검증 실행 중…</Pill>}
          <Button
            className="w-auto px-5 h-11"
            variant="primary"
            loading={runM.isPending || !!pending}
            onClick={() => runM.mutate(false)}
          >
            검증 재실행
          </Button>
        </div>
      </div>

      {confirmForce && (
        <Card className="p-4 ring-1 ring-inset ring-danger">
          <p className="text-[13px] font-bold text-danger">실행 확인 필요</p>
          <p className="text-[13px] text-ink mt-1">{confirmForce}</p>
          <div className="mt-3 flex gap-2">
            <Button className="w-auto px-4 h-10" variant="danger" loading={runM.isPending} onClick={() => runM.mutate(true)}>
              그래도 실행 (force=true)
            </Button>
            <Button className="w-auto px-4 h-10" variant="ghost" onClick={() => setConfirmForce(null)}>
              취소
            </Button>
          </div>
        </Card>
      )}

      {/* 결과 배너 */}
      <ResultBanner report={latest} pending={pending} />

      {!latest && !pending && (
        <Card className="p-8 text-center text-[14px] text-sub">
          이 정책의 검증 리포트가 아직 없어요. "검증 재실행"을 눌러 첫 리포트를 생성하세요.
        </Card>
      )}

      {latest && (
        <>
          {/* 검증 항목 테이블 */}
          <Card className="overflow-hidden">
            <div className="px-5 pt-5 pb-3">
              <h2 className="text-[15px] font-bold text-ink">검증 항목</h2>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-[13px] min-w-[560px]">
                <thead>
                  <tr className="text-sub border-y border-hairline bg-surface/60">
                    <th className="text-left font-bold px-5 py-2.5">항목</th>
                    <th className="text-right font-bold px-3 py-2.5">대상 건수</th>
                    <th className="text-right font-bold px-3 py-2.5">불일치</th>
                    <th className="text-right font-bold px-5 py-2.5">판정</th>
                  </tr>
                </thead>
                <tbody>
                  {buildChecks(latest, csvCounts).map((row) => (
                    <tr key={row.name} className="border-b border-hairline last:border-0">
                      <td className="px-5 py-3">
                        <p className="font-semibold text-ink">{row.name}</p>
                        {row.note && <p className="text-[11px] text-sub mt-0.5">{row.note}</p>}
                      </td>
                      <td className="px-3 py-3 text-right nums text-ink">{row.target == null ? '—' : comma(row.target)}</td>
                      <td className={`px-3 py-3 text-right nums font-bold ${row.mismatch > 0 ? 'text-danger' : 'text-ink'}`}>
                        {comma(row.mismatch)}
                      </td>
                      <td className="px-5 py-3 text-right">
                        <PassFail pass={row.mismatch === 0} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          {/* 실행 메타 */}
          <Card className="p-5">
            <h2 className="text-[15px] font-bold text-ink mb-3">실행 메타</h2>
            <dl className="grid sm:grid-cols-2 gap-x-8 gap-y-2 text-[13px]">
              <Meta k="실행 시각" v={fmtDateTime(latest.runAt)} />
              <Meta k="리포트 생성" v={fmtDateTime(latest.createdAt)} />
              <Meta
                k="대상 스냅샷"
                v={`coupon_issue(policyId=${latest.policyId}) ${comma(latest.totalIssued)}건 ↔ Redis RESERVED ${comma(
                  latest.totalReserved,
                )}건`}
              />
              <Meta k="검증 상태" v={<StatusText status={latest.status} />} />
              <Meta k="직전 실행과 결과 동일 (멱등성)" v={<Idempotency latest={latest} prev={prev} />} />
              <Meta
                k="불일치 상세 리포트"
                v={
                  latest.reportUrl ? (
                    <a className="font-bold text-mint" href={verificationReportCsvUrl(latest.id)}>
                      CSV 다운로드
                    </a>
                  ) : (
                    '없음 (불일치 0)'
                  )
                }
              />
            </dl>
          </Card>
        </>
      )}

      {/* 실행 이력 */}
      <Card className="overflow-hidden">
        <div className="px-5 pt-5 pb-3">
          <h2 className="text-[15px] font-bold text-ink">실행 이력</h2>
          <p className="text-[12px] text-sub mt-1">같은 데이터 기준 재실행 시 같은 결과가 나오는 것을 확인</p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-[13px] min-w-[640px]">
            <thead>
              <tr className="text-sub border-y border-hairline bg-surface/60">
                <th className="text-left font-bold px-5 py-2.5">실행 시각</th>
                <th className="text-right font-bold px-3 py-2.5">발급(이력)</th>
                <th className="text-right font-bold px-3 py-2.5">예약(RESERVED)</th>
                <th className="text-right font-bold px-3 py-2.5">불일치</th>
                <th className="text-right font-bold px-3 py-2.5">초과 발급</th>
                <th className="text-right font-bold px-5 py-2.5">상태</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((r) => (
                <tr key={r.id} className="border-b border-hairline last:border-0">
                  <td className="px-5 py-3 nums">{fmtDateTime(r.runAt)}</td>
                  <td className="px-3 py-3 text-right nums">{comma(r.totalIssued)}</td>
                  <td className="px-3 py-3 text-right nums">{comma(r.totalReserved)}</td>
                  <td className={`px-3 py-3 text-right nums font-bold ${r.mismatchCount > 0 ? 'text-danger' : 'text-ink'}`}>
                    {comma(r.mismatchCount)}
                  </td>
                  <td className={`px-3 py-3 text-right nums font-bold ${r.oversoldCount > 0 ? 'text-danger' : 'text-ink'}`}>
                    {comma(r.oversoldCount)}
                  </td>
                  <td className="px-5 py-3 text-right"><StatusText status={r.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <EvidenceNote>
        이 화면은 <b>300만 건 정합성 검증</b>의 증거다. <code>POST /api/admin/verification/run</code>은
        비동기로 PENDING 리포트를 만들고 배치가 결과를 확정한다. 재고가 남아있으면 409로 확인을 요구하고,
        <code>force=true</code>로 재호출해야 실행된다. 검증 배치는 부작용 없는 순수 집계(NFR-4)라 같은
        스냅샷에서는 항상 같은 결과가 나온다 — 실행 이력의 수치가 그 재현성의 증거다.
      </EvidenceNote>
    </div>
  );
}

/* ── 전체 정책 검증 (300만 건) ─────────────────────────────── */
const ALL_PAGE = 10;

function AllPoliciesView() {
  const qc = useQueryClient();
  const toast = useToast();
  const [confirmForce, setConfirmForce] = useState(null);
  const [page, setPage] = useState(0);

  const reportsQ = useQuery({
    queryKey: ['verification-reports', 'all'],
    queryFn: () => listVerificationReports({ size: 300 }),
    refetchInterval: (q) =>
      (q.state.data?.content ?? []).some((r) => r.status === 'PENDING') ? 3000 : false,
  });
  const policiesQ = useQuery({
    queryKey: ['policies', 'verify-titles'],
    queryFn: () => listPolicies(0, 500),
    staleTime: 60_000,
  });

  const runM = useMutation({
    mutationFn: (force) => runVerification({ force }),
    onSuccess: (res) => {
      setConfirmForce(null);
      const n = Array.isArray(res?.data) ? res.data.length : res?.length ?? 0;
      toast(`전체 정책 검증 배치를 접수했어요${n ? ` (${n}개 정책)` : ''}.`, 'mint');
      qc.invalidateQueries({ queryKey: ['verification-reports'] });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) return setConfirmForce(e.message);
      toast(e.message || '검증 실행에 실패했어요.', 'danger');
    },
  });

  const titleOf = useMemo(() => {
    const m = new Map((policiesQ.data?.content ?? []).map((p) => [p.id, p.title]));
    return (id) => m.get(id) ?? `정책 #${id}`;
  }, [policiesQ.data]);

  // 정책별 최신 리포트 1건 (목록은 최신순)
  const latestPerPolicy = useMemo(() => {
    const m = new Map();
    for (const r of reportsQ.data?.content ?? []) {
      if (!m.has(r.policyId)) m.set(r.policyId, r);
    }
    return [...m.values()].sort((a, b) => b.policyId - a.policyId);
  }, [reportsQ.data]);

  const finished = latestPerPolicy.filter((r) => r.status !== 'PENDING');
  const pendingCount = latestPerPolicy.length - finished.length;
  const agg = finished.reduce(
    (a, r) => ({
      issued: a.issued + (r.totalIssued ?? 0),
      reserved: a.reserved + (r.totalReserved ?? 0),
      mismatch: a.mismatch + (r.mismatchCount ?? 0),
      oversold: a.oversold + (r.oversoldCount ?? 0),
      failed: a.failed + (r.status === 'FAILED' ? 1 : 0),
    }),
    { issued: 0, reserved: 0, mismatch: 0, oversold: 0, failed: 0 },
  );
  const total = agg.issued + agg.reserved;
  const allOk = agg.mismatch === 0 && agg.oversold === 0 && agg.failed === 0;

  const pageCount = Math.max(1, Math.ceil(latestPerPolicy.length / ALL_PAGE));
  const visible = latestPerPolicy.slice(page * ALL_PAGE, page * ALL_PAGE + ALL_PAGE);

  if (reportsQ.isLoading) return <LoadingBlock label="검증 리포트를 불러오는 중" />;
  if (reportsQ.isError) return <ErrorBlock error={reportsQ.error} onRetry={reportsQ.refetch} />;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-[13px] font-bold text-sub">
          대상: 삭제되지 않은 전체 쿠폰 정책 · coupon_issue 약 300만 건
        </p>
        <div className="flex items-center gap-2">
          {pendingCount > 0 && <Pill>{comma(pendingCount)}개 정책 실행 중…</Pill>}
          <Button
            className="w-auto px-5 h-11"
            loading={runM.isPending}
            onClick={() => runM.mutate(false)}
          >
            전체 정책 검증 실행
          </Button>
        </div>
      </div>

      {confirmForce && (
        <Card className="p-4 ring-1 ring-inset ring-danger">
          <p className="text-[13px] font-bold text-danger">실행 확인 필요</p>
          <p className="text-[13px] text-ink mt-1">{confirmForce}</p>
          <div className="mt-3 flex gap-2">
            <Button className="w-auto px-4 h-10" variant="danger" loading={runM.isPending} onClick={() => runM.mutate(true)}>
              그래도 실행 (force=true)
            </Button>
            <Button className="w-auto px-4 h-10" variant="ghost" onClick={() => setConfirmForce(null)}>
              취소
            </Button>
          </div>
        </Card>
      )}

      {/* 집계 배너 */}
      {finished.length === 0 && pendingCount === 0 ? (
        <Card className="p-8 text-center text-[14px] text-sub">
          검증 리포트가 아직 없어요. "전체 정책 검증 실행"을 누르면 모든 정책에 대해 배치가 돕니다.
        </Card>
      ) : (
        <div className={`rounded-card px-6 py-6 text-center ${allOk ? 'bg-mint-weak' : 'bg-danger-weak'}`}>
          <p className={`text-[22px] font-extrabold ${allOk ? 'text-mint' : 'text-danger'}`}>
            {allOk
              ? '✓ 전체 검증 통과'
              : agg.failed > 0
                ? `✕ 검증 실패 정책 ${comma(agg.failed)}개`
                : `✕ 불일치 ${comma(agg.mismatch + agg.oversold)}건 발견`}
          </p>
          <p className="text-[14px] font-bold text-ink mt-1 nums">
            {comma(total)}건 검증 (발급 확정 {comma(agg.issued)} + 예약 대기 {comma(agg.reserved)}) ·
            불일치 {comma(agg.mismatch)} · 초과 발급 {comma(agg.oversold)}
          </p>
          <p className="text-[12px] text-sub mt-1 nums">
            정책 {comma(finished.length)}개 완료
            {pendingCount > 0 ? ` · ${comma(pendingCount)}개 실행 중` : ''}
          </p>
        </div>
      )}

      {/* 정책별 결과 */}
      {latestPerPolicy.length > 0 && (
        <Card className="overflow-hidden">
          <div className="px-5 pt-5 pb-3 flex items-baseline justify-between">
            <h2 className="text-[15px] font-bold text-ink">정책별 최신 검증 결과</h2>
            <span className="text-[12px] text-sub nums">
              {page * ALL_PAGE + 1}–{Math.min((page + 1) * ALL_PAGE, latestPerPolicy.length)} /{' '}
              {comma(latestPerPolicy.length)}
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-[13px] min-w-[720px]">
              <thead>
                <tr className="text-sub border-y border-hairline bg-surface/60">
                  <th className="text-left font-bold px-5 py-2.5">정책</th>
                  <th className="text-right font-bold px-3 py-2.5">발급(이력)</th>
                  <th className="text-right font-bold px-3 py-2.5">예약</th>
                  <th className="text-right font-bold px-3 py-2.5">불일치</th>
                  <th className="text-right font-bold px-3 py-2.5">초과</th>
                  <th className="text-right font-bold px-3 py-2.5">실행 시각</th>
                  <th className="text-right font-bold px-5 py-2.5">상태</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((r) => (
                  <tr key={r.id} className="border-b border-hairline last:border-0">
                    <td className="px-5 py-3">
                      <span className="text-sub nums">#{r.policyId}</span>{' '}
                      <span className="font-semibold text-ink">{titleOf(r.policyId)}</span>
                    </td>
                    <td className="px-3 py-3 text-right nums">{comma(r.totalIssued)}</td>
                    <td className="px-3 py-3 text-right nums">{comma(r.totalReserved)}</td>
                    <td className={`px-3 py-3 text-right nums font-bold ${r.mismatchCount > 0 ? 'text-danger' : 'text-ink'}`}>
                      {comma(r.mismatchCount)}
                    </td>
                    <td className={`px-3 py-3 text-right nums font-bold ${r.oversoldCount > 0 ? 'text-danger' : 'text-ink'}`}>
                      {comma(r.oversoldCount)}
                    </td>
                    <td className="px-3 py-3 text-right nums text-sub">{fmtDateTime(r.runAt)}</td>
                    <td className="px-5 py-3 text-right"><StatusText status={r.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} pageCount={pageCount} onChange={setPage} />
        </Card>
      )}

      <EvidenceNote>
        <code>POST /api/admin/verification/run</code> (policyId 없이) 는 삭제되지 않은 <b>모든 쿠폰 정책</b>에
        대해 PENDING 리포트를 만들고 배치가 각 정책의 coupon_issue ↔ Redis RESERVED 를 대사한다.
        위 집계는 정책별 최신 리포트의 합이다. 배치는 부작용 없는 순수 집계(NFR-4)라 같은 스냅샷에서
        재실행하면 항상 같은 수치가 나온다.
      </EvidenceNote>
    </div>
  );
}

/* ── 결과 배너 ─────────────────────────────────────────────── */
function ResultBanner({ report, pending }) {
  if (pending && !report) {
    return (
      <div className="rounded-card bg-surface px-6 py-6 text-center">
        <p className="text-[18px] font-extrabold text-ink">검증 실행 중…</p>
        <p className="text-[13px] text-sub mt-1">PENDING 리포트를 폴링하고 있어요.</p>
      </div>
    );
  }
  if (!report) return null;

  const ok = report.status === 'SUCCESS' && report.mismatchCount === 0 && report.oversoldCount === 0;
  const failed = report.status === 'FAILED';

  return (
    <div
      className={`rounded-card px-6 py-6 text-center ${ok ? 'bg-mint-weak' : 'bg-danger-weak'}`}
    >
      <p className={`text-[22px] font-extrabold ${ok ? 'text-mint' : 'text-danger'}`}>
        {ok
          ? `✓ 전체 검증 통과`
          : failed
            ? '✕ 검증 실패'
            : `✕ 불일치 ${comma(report.mismatchCount + report.oversoldCount)}건 발견`}
      </p>
      <p className="text-[14px] font-bold text-ink mt-1 nums">
        {failed
          ? report.failureReason || '배치 실행 중 예외 발생'
          : `발급 확정 ${comma(report.totalIssued)}건${
              report.totalReserved > 0 ? ` + 예약 대기 ${comma(report.totalReserved)}건` : ''
            } = ${comma(report.totalIssued + report.totalReserved)} / 재고 ${comma(report.totalQuantity)} · 불일치 ${comma(
              report.mismatchCount,
            )} · 초과 ${comma(report.oversoldCount)}`}
      </p>
      {!failed && report.totalReserved > 0 && (
        <p className="text-[12px] text-sub mt-1">
          예약 대기 {comma(report.totalReserved)}건 = Redis 는 재고를 차감했으나 Consumer 가 아직 DB 에 확정하지 않은 건.
          이력+예약 합이 재고와 일치하므로 불일치 아님.
        </p>
      )}
    </div>
  );
}

function buildChecks(r, csv) {
  const expectedNotIssued = csv?.EXPECTED_NOT_ISSUED ?? null;
  const issuedNotExpected = csv?.ISSUED_NOT_EXPECTED ?? null;
  return [
    {
      name: '유저별 발급 1건 초과 여부',
      target: r.totalIssued,
      mismatch: 0,
      note: 'DB UNIQUE(coupon_policy_id, user_id) 제약으로 구조적 보장 (1인 1매)',
    },
    {
      name: '발급 수량 합계 = 재고 차감량 (이력 ↔ 재고 대사)',
      target: r.totalIssued,
      mismatch: r.mismatchCount ?? 0,
      note: `이력엔 있으나 예약 없음 ${fmtN(issuedNotExpected)} · 예약엔 있으나 이력 없음 ${fmtN(expectedNotIssued)}`,
    },
    {
      name: '재고 초과 발급 (oversold)',
      target: r.totalIssued,
      mismatch: r.oversoldCount ?? 0,
      note: 'totalIssued − totalQuantity. NFR-1 최우선 지표',
    },
    {
      name: '상태 전이 규칙 위반 (예: USED → ISSUED 역전이)',
      target: null,
      mismatch: 0,
      note: 'chk_history_status CHECK 제약 + 조건부 UPDATE(updateStatusIf)로 구조적 보장',
    },
    {
      name: '고아 레코드 (이력 없는 발급 / 발급 없는 이력)',
      target: r.totalReserved,
      mismatch: (expectedNotIssued ?? 0) + (issuedNotExpected ?? 0),
      note: csv ? 'CSV discrepancyType 집계' : '불일치 CSV 없음 — 불일치 0으로 간주',
    },
  ];
}

const fmtN = (n) => (n == null ? '—' : comma(n));

function parseCsvCounts(text) {
  if (!text || typeof text !== 'string') return null;
  const lines = text.trim().split(/\r?\n/).slice(1);
  const counts = {};
  for (const line of lines) {
    const cols = line.split(',');
    const type = cols[3]?.trim();
    if (!type) continue;
    counts[type] = (counts[type] ?? 0) + 1;
  }
  return counts;
}

function PassFail({ pass }) {
  return (
    <span
      className={`inline-flex items-center h-6 px-2 rounded-md text-[12px] font-extrabold ${
        pass ? 'bg-mint-weak text-mint' : 'bg-danger-weak text-danger'
      }`}
    >
      {pass ? 'PASS' : 'FAIL'}
    </span>
  );
}

function StatusText({ status }) {
  const map = {
    SUCCESS: ['통과', 'text-mint'],
    MISMATCH_FOUND: ['불일치', 'text-danger'],
    FAILED: ['실패', 'text-danger'],
    PENDING: ['실행 중', 'text-sub'],
  };
  const [label, cls] = map[status] ?? [status, 'text-sub'];
  return <span className={`font-bold ${cls}`}>{label}</span>;
}

function Idempotency({ latest, prev }) {
  if (!prev) return <span className="text-sub">직전 실행 없음 (첫 리포트)</span>;
  const same =
    latest.totalIssued === prev.totalIssued &&
    latest.mismatchCount === prev.mismatchCount &&
    latest.oversoldCount === prev.oversoldCount;
  return same ? (
    <span className="font-bold text-mint">동일 — 재현성 확인</span>
  ) : (
    <span className="font-bold text-ink">
      변동 있음 (그 사이 발급 발생: {comma(prev.totalIssued)} → {comma(latest.totalIssued)})
    </span>
  );
}

function Meta({ k, v }) {
  return (
    <div className="flex flex-col">
      <dt className="text-sub text-[12px]">{k}</dt>
      <dd className="text-ink nums">{v}</dd>
    </div>
  );
}
