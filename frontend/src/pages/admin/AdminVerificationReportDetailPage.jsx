import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import {
  getVerificationMismatches,
  getVerificationReport,
  verificationReportCsvUrl,
} from '../../lib/endpoints.js';
import { Badge, Card, DataTable, LoadingBlock, PageHeader, ProgressBar } from '../../components/ui.jsx';
import Pagination from '../../components/Pagination.jsx';
import { comma, fmtDateTime } from '../../lib/format.js';
import { SEVERITY, analyzeMismatches, infoFor, parseDiscrepancyType } from '../../lib/verification.js';

const STATUS_TONE = { SUCCESS: 'ok', MISMATCH_FOUND: 'bad', FAILED: 'bad', PENDING: 'soon' };
const STATUS_LABEL = {
  SUCCESS: '성공 (SUCCESS)',
  MISMATCH_FOUND: '불일치 발견 (MISMATCH_FOUND)',
  FAILED: '실패 (FAILED)',
  PENDING: '진행 중 (PENDING)',
};

// 유형별 요약을 내려면 전체 행이 필요하다. 백엔드가 어차피 CSV 파일을 통째로 읽어 메모리에서
// 자르는 구조라(VerificationService.getVerificationReportMismatches) 한 번에 받아오고
// 목록 페이지네이션은 화면에서 처리한다.
const FETCH_LIMIT = 5000;
const PAGE_SIZE = 20;

const VERDICT_STYLE = {
  critical: { bar: 'bg-danger', text: 'text-danger', chip: 'bad', title: '심각' },
  warning: { bar: 'bg-danger', text: 'text-ink', chip: 'soon', title: '주의' },
  info: { bar: 'bg-mint', text: 'text-ink', chip: 'plain', title: '참고' },
  none: { bar: 'bg-mint', text: 'text-mint', chip: 'ok', title: '정상' },
};

/** 검증 리포트 상세 — 불일치를 유형별로 묶어 "무엇이 어떻게 어긋났는지"까지 설명한다. */
export default function AdminVerificationReportDetailPage() {
  const { reportId } = useParams();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [typeFilter, setTypeFilter] = useState(null);

  const reportQ = useQuery({
    queryKey: ['verification-report', reportId],
    queryFn: () => getVerificationReport(reportId),
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 3000 : false),
  });
  const report = reportQ.data;

  const mismatchesQ = useQuery({
    queryKey: ['verification-mismatches-all', reportId],
    queryFn: () => getVerificationMismatches(reportId, 0, FETCH_LIMIT),
    enabled: report?.status === 'MISMATCH_FOUND',
    // 파일이 사라진 리포트(410)는 재시도해도 살아나지 않는다 — 바로 안내로 넘어간다.
    retry: (count, error) => error?.status !== 410 && count < 1,
  });

  // DB에는 report_url이 남아 있는데 CSV 파일이 없는 경우. 앱 컨테이너를 재빌드하면 예전에는
  // reports 디렉터리가 통째로 날아갔다(지금은 호스트 볼륨을 붙여 더는 생기지 않지만, 그 이전에
  // 만들어진 리포트는 여전히 이 상태다).
  const fileMissing = mismatchesQ.error?.errorCode === 'VERIFICATION_REPORT_FILE_MISSING';
  // 세부 내역을 못 읽은 상태 — 행이 0건인 것과 구분해야 한다.
  const detailUnavailable = report?.status === 'MISMATCH_FOUND' && mismatchesQ.isError;

  const allRows = mismatchesQ.data?.content ?? [];
  const truncated = (mismatchesQ.data?.totalElements ?? 0) > allRows.length;
  const analysis = useMemo(() => analyzeMismatches(allRows, report), [allRows, report]);

  const visibleRows = useMemo(
    () =>
      typeFilter
        ? allRows.filter((r) => parseDiscrepancyType(r.discrepancyType).base === typeFilter)
        : allRows,
    [allRows, typeFilter],
  );
  const totalPages = Math.max(1, Math.ceil(visibleRows.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const pageRows = visibleRows.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);

  if (reportQ.isLoading) return <LoadingBlock />;
  if (!report) return null;

  const issueRatio = report.totalQuantity > 0 ? (report.totalIssued / report.totalQuantity) * 100 : 0;
  const verdict = VERDICT_STYLE[analysis.level] ?? VERDICT_STYLE.none;

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title={`검증 리포트 #${report.id}`}
        sub={`정책 #${report.policyId} · ${fmtDateTime(report.runAt)}`}
        right={
          <div className="flex items-center gap-3">
            {report.status !== 'PENDING' && report.reportUrl && (
              <a
                href={verificationReportCsvUrl(report.id)}
                className="text-[13px] font-semibold text-sub hover:text-ink hover:underline"
              >
                CSV 다운로드
              </a>
            )}
            <button
              className="text-[13px] font-semibold text-sub hover:text-ink hover:underline"
              onClick={() => navigate(`/admin/${report.policyId}?tab=verification`)}
            >
              ← 이 정책의 검증 탭으로
            </button>
          </div>
        }
      />

      {/* ── 판정: 이 리포트를 한 줄로 요약한다 ─────────────────────── */}
      {report.status === 'MISMATCH_FOUND' && !mismatchesQ.isLoading && !mismatchesQ.isError && (
        <Card className="p-0 overflow-hidden">
          <div className="flex">
            <div className={`w-1.5 shrink-0 ${verdict.bar}`} />
            <div className="p-5 flex-1">
              <div className="flex items-center gap-2 mb-2">
                <Badge tone={verdict.chip}>{verdict.title}</Badge>
                <span className="text-[12px] text-sub">검증 배치가 내린 판정</span>
              </div>
              <p className={`text-[18px] font-bold ${verdict.text}`}>{analysis.headline}</p>
              <p className="text-[13px] text-sub mt-1.5 leading-relaxed">{analysis.detail}</p>
            </div>
          </div>
        </Card>
      )}

      {/* ── 세 갈래 검사 결과 ────────────────────────────────────── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <CheckCard
          title="개수 정합성"
          ok={analysis.countIntact}
          okLabel="초과 발급 없음"
          badLabel={`초과 발급 ${comma(report.oversoldCount ?? 0)}건`}
        >
          <div className="flex items-end justify-between mb-2">
            <span className="text-[13px] text-ink nums">
              {comma(report.totalIssued)} / {comma(report.totalQuantity)}장
            </span>
            <span className="text-[13px] font-bold text-sub nums">{issueRatio.toFixed(1)}%</span>
          </div>
          <ProgressBar
            value={report.totalIssued}
            max={report.totalQuantity}
            tone={analysis.countIntact ? 'mint' : 'danger'}
          />
        </CheckCard>

        <CheckCard
          title="저장소 간 일치"
          unknown={detailUnavailable}
          ok={!analysis.groups.some((g) => ['REDIS_ONLY', 'DB_ONLY', 'RESERVED_STALE'].includes(g.base))}
          okLabel="Redis와 DB가 같음"
          badLabel="Redis·DB 어긋남"
        >
          <p className="text-[13px] text-ink">
            Redis 예약(reserved) 잔여 <b className="nums">{comma(report.totalReserved)}</b>건
          </p>
          <p className="text-[12px] text-sub mt-1">
            예약은 발급 성공과 DB 확정 사이의 임시 상태다. 검증 시점에 남아있으면 확정이 안 끝난 것이다.
          </p>
        </CheckCard>

        <CheckCard
          title="선착순 순서"
          unknown={detailUnavailable}
          ok={analysis.expectedNotIssued === 0 && analysis.issuedNotExpected === 0}
          okLabel="도착 순번대로 발급됨"
          badLabel={`${Math.max(analysis.expectedNotIssued, analysis.issuedNotExpected)}건 역전`}
        >
          <p className="text-[13px] text-ink">
            밀려남 <b className="nums">{comma(analysis.expectedNotIssued)}</b>명 · 새로 들어옴{' '}
            <b className="nums">{comma(analysis.issuedNotExpected)}</b>명
          </p>
          <p className="text-[12px] text-sub mt-1">
            {analysis.fcfsPaired
              ? '수가 정확히 같아 자리만 맞바뀌었다 — 총 발급 수는 그대로다.'
              : analysis.expectedNotIssued === 0 && analysis.issuedNotExpected === 0
                ? '대기열 도착 순번 상위 N명이 그대로 발급받았다.'
                : '두 수가 달라 단순 교환이 아니다 — 개수 검사를 함께 확인할 것.'}
          </p>
        </CheckCard>
      </div>

      {report.status === 'FAILED' && report.failureReason && (
        <Card className="p-4">
          <p className="text-[13px] font-bold text-danger mb-1">검증 실패</p>
          <p className="text-[13px] text-sub">{report.failureReason}</p>
        </Card>
      )}
      {report.status === 'PENDING' && (
        <Card className="p-4 text-[13px] text-sub">검증이 아직 진행 중이에요 — 자동으로 갱신됩니다</Card>
      )}
      {report.status === 'SUCCESS' && (
        <Card className="p-5 flex items-center gap-3">
          <Badge tone="ok">{STATUS_LABEL[report.status]}</Badge>
          <p className="text-[14px] text-ink">모든 검사를 통과했어요 — 불일치 0건.</p>
        </Card>
      )}

      {/* ── 유형별 진단 ──────────────────────────────────────────── */}
      {report.status === 'MISMATCH_FOUND' && (
        <>
          {mismatchesQ.isLoading && <LoadingBlock label="불일치 내역 분석 중..." />}

          {fileMissing && (
            <Card className="p-0 overflow-hidden">
              <div className="flex">
                <div className="w-1.5 shrink-0 bg-sub" />
                <div className="p-5 flex-1">
                  <div className="flex items-center gap-2 mb-2">
                    <Badge tone="done">리포트 파일 만료</Badge>
                  </div>
                  <p className="text-[15px] font-bold text-ink">
                    불일치 내역 파일이 남아있지 않아요
                  </p>
                  <p className="text-[13px] text-sub mt-1.5 leading-relaxed">
                    이 리포트는 불일치 <b className="text-ink nums">{comma(report.mismatchCount)}건</b>을 발견했다고
                    기록돼 있지만, 세부 내역이 담긴 CSV 파일이 서버에 없습니다. 앱 컨테이너를 다시 빌드할 때
                    리포트 디렉터리가 함께 사라지던 시절에 만들어진 리포트예요. 지금은 호스트 볼륨을 붙여
                    더 이상 사라지지 않습니다.
                  </p>
                  <p className="text-[13px] text-sub mt-2">
                    위 요약 수치(발급 {comma(report.totalIssued)} / 총 {comma(report.totalQuantity)} · 초과{' '}
                    {comma(report.oversoldCount ?? 0)})는 DB에 남아 있어 그대로 유효합니다. 세부 내역이 필요하면
                    같은 정책으로 검증을 다시 실행하세요.
                  </p>
                  <button
                    className="mt-3 text-[13px] font-bold text-mint hover:underline"
                    onClick={() => navigate(`/admin/${report.policyId}?tab=verification`)}
                  >
                    이 정책의 검증 탭에서 다시 실행 →
                  </button>
                </div>
              </div>
            </Card>
          )}

          {mismatchesQ.isError && !fileMissing && (
            <Card className="p-5">
              <p className="text-[15px] font-bold text-danger">불일치 내역을 불러오지 못했어요</p>
              <p className="text-[13px] text-sub mt-1.5">
                {mismatchesQ.error?.message ?? '알 수 없는 오류'}
                {mismatchesQ.error?.errorCode ? ` (${mismatchesQ.error.errorCode})` : ''}
              </p>
              <button
                className="mt-3 text-[13px] font-bold text-mint hover:underline"
                onClick={() => mismatchesQ.refetch()}
              >
                다시 시도
              </button>
            </Card>
          )}

          {!mismatchesQ.isLoading && !mismatchesQ.isError && (
            <div className="flex flex-col gap-3">
              <h2 className="text-[15px] font-bold text-ink">유형별 진단</h2>
              {analysis.groups.map((g) => (
                <DiagnosisCard
                  key={g.base}
                  group={g}
                  active={typeFilter === g.base}
                  onToggle={() => {
                    setTypeFilter(typeFilter === g.base ? null : g.base);
                    setPage(0);
                  }}
                />
              ))}
            </div>
          )}

          {/* ── 원본 목록 ──────────────────────────────────────── */}
          {!mismatchesQ.isLoading && !mismatchesQ.isError && (
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <h2 className="text-[15px] font-bold text-ink">
                  불일치 원본 {typeFilter ? `— ${infoFor(typeFilter).label}` : '전체'}{' '}
                  <span className="text-sub font-semibold nums">({comma(visibleRows.length)}건)</span>
                </h2>
                {typeFilter && (
                  <button
                    className="text-[13px] font-semibold text-mint hover:underline"
                    onClick={() => {
                      setTypeFilter(null);
                      setPage(0);
                    }}
                  >
                    필터 해제
                  </button>
                )}
              </div>

              {truncated && (
                <p className="text-[12px] text-danger font-semibold">
                  불일치가 {comma(mismatchesQ.data.totalElements)}건이라 앞의 {comma(FETCH_LIMIT)}건만
                  불러왔어요 — 전체는 CSV를 받아 확인하세요.
                </p>
              )}

              <DataTable
                rowKey={(r) => `${r.userId ?? 'policy'}-${r.couponIssueId ?? '-'}-${r.discrepancyType}`}
                empty="불일치 내역이 없어요"
                columns={[
                  {
                    key: 'discrepancyType',
                    label: '유형',
                    render: (r) => {
                      const { base } = parseDiscrepancyType(r.discrepancyType);
                      const info = infoFor(base);
                      return (
                        <span className="inline-flex items-center gap-2">
                          <Badge tone={SEVERITY[info.severity].tone}>{info.label}</Badge>
                          <span className="text-[11px] text-sub">{r.discrepancyType}</span>
                        </span>
                      );
                    },
                  },
                  { key: 'userId', label: 'userId', render: (r) => <span className="nums">{r.userId ?? '-'}</span> },
                  {
                    key: 'couponIssueId',
                    label: 'couponIssueId',
                    render: (r) => <span className="nums">{r.couponIssueId ?? '-'}</span>,
                  },
                  { key: 'detectedAt', label: '검출 시각', render: (r) => fmtDateTime(r.detectedAt) },
                ]}
                rows={pageRows}
              />

              <Pagination
                page={safePage}
                totalPages={totalPages}
                totalElements={visibleRows.length}
                onChange={setPage}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}

// unknown=true면 "이상 없음"이라고 단정하지 않는다 — 세부 내역을 못 읽은 것뿐인데
// 행이 0건이라 통과한 것처럼 보이면 거짓 안심을 준다.
function CheckCard({ title, ok, okLabel, badLabel, unknown, children }) {
  return (
    <Card className="p-5">
      <div className="flex items-center justify-between mb-3">
        <p className="text-[12px] font-bold text-sub">{title}</p>
        {unknown ? <Badge tone="done">확인 불가</Badge> : <Badge tone={ok ? 'ok' : 'bad'}>{ok ? okLabel : badLabel}</Badge>}
      </div>
      {unknown ? (
        <p className="text-[13px] text-sub">세부 내역 파일이 없어 이 검사 결과를 알 수 없어요.</p>
      ) : (
        children
      )}
    </Card>
  );
}

function DiagnosisCard({ group, active, onToggle }) {
  const { info, count, base, userIdMin, userIdMax } = group;
  const sev = SEVERITY[info.severity];
  const accent =
    info.severity === 'critical' ? 'bg-danger' : info.severity === 'warning' ? 'bg-sub' : 'bg-mint';

  return (
    <Card className={`p-0 overflow-hidden ${active ? 'ring-1 ring-inset ring-mint' : ''}`}>
      <div className="flex">
        <div className={`w-1.5 shrink-0 ${accent}`} />
        <div className="p-5 flex-1">
          <div className="flex flex-wrap items-center gap-2 mb-3">
            <Badge tone={sev.tone}>{sev.label}</Badge>
            <span className="text-[15px] font-bold text-ink">{info.label}</span>
            <span className="text-[15px] font-extrabold text-ink nums">{comma(count)}건</span>
            <code className="text-[11px] text-sub bg-surface rounded px-1.5 py-0.5">{base}</code>
            {info.autoQueued && <Badge tone="issued">재처리 자동 등록됨</Badge>}
            {info.scope === 'policy' && <Badge tone="plain">정책 전체</Badge>}
            <button
              className="ml-auto text-[13px] font-semibold text-mint hover:underline"
              onClick={onToggle}
            >
              {active ? '필터 해제' : '이 유형만 보기'}
            </button>
          </div>

          {userIdMin != null && (
            <p className="text-[12px] text-sub mt-3 pt-3 border-t border-hairline">
              영향받은 userId 범위 <span className="nums text-ink font-semibold">{comma(userIdMin)} ~ {comma(userIdMax)}</span>
              {info.severity === 'info' && ' — 재고 소진 경계에 몰려 있으면 배치 내 경쟁이 원인일 가능성이 높다.'}
            </p>
          )}
          {!info.autoQueued && info.severity !== 'info' && (
            <p className="text-[12px] text-sub mt-2">
              이 유형은 재처리 큐에 자동 등록되지 않아요 — 직접 확인하고 필요하면 재처리를 접수해야 합니다.
            </p>
          )}
        </div>
      </div>
    </Card>
  );
}

