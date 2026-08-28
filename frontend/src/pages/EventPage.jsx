import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useDemo } from '../lib/demo.jsx';
import { getCouponStatus, getPolicy } from '../lib/endpoints.js';
import { comma, fmtCountdown } from '../lib/format.js';
import { Button, Card, EvidenceNote, LoadingBlock, ErrorBlock, ProgressBar, Pill } from '../components/ui.jsx';
import CouponCard from '../components/CouponCard.jsx';
import LoadSimulator from '../components/LoadSimulator.jsx';
import { useIssueFlow } from '../hooks/useIssueFlow.js';

export default function EventPage() {
  const { policyId, userId } = useDemo();

  const policyQ = useQuery({
    queryKey: ['policy', policyId],
    queryFn: () => getPolicy(policyId),
    enabled: !!policyId,
  });

  const statusQ = useQuery({
    queryKey: ['coupon-status', policyId],
    queryFn: () => getCouponStatus(policyId),
    enabled: !!policyId,
    refetchInterval: 2000, // 실시간 남은 수량 — 2초 폴링
  });

  const policy = policyQ.data;
  const status = statusQ.data;
  const remaining = status?.remainingQuantity;
  const total = status?.totalQuantity ?? policy?.totalQuantity;
  const soldOut = remaining === 0;

  const flow = useIssueFlow({
    policyId,
    userId,
    policy,
    statusRemaining: remaining,
  });

  // 오픈 카운트다운
  const openMs = policy?.openAt ? new Date(policy.openAt).getTime() : null;
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!openMs || openMs <= Date.now()) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [openMs]);
  const beforeOpen = openMs != null && openMs > now;
  const closeMs = policy?.closeAt ? new Date(policy.closeAt).getTime() : null;
  const ended = closeMs != null && closeMs < now;

  if (policyQ.isLoading) return <LoadingBlock label="캠페인 정보를 불러오는 중" />;
  if (policyQ.isError) return <ErrorBlock error={policyQ.error} onRetry={policyQ.refetch} />;

  return (
    <div className="space-y-6">
      {/* 캠페인 배너 */}
      <div className="rounded-card bg-surface px-5 py-4">
        <p className="text-[12px] font-bold text-mint">우레카 런칭 기념</p>
        <p className="mt-1 text-[17px] font-extrabold leading-snug text-ink">{policy?.title}</p>
        <p className="mt-1 text-[13px] text-sub">
          지금 받으면 결제할 때 바로 할인 · 선착순 {comma(total)}명 한정
        </p>
      </div>

      {/* 절취선 쿠폰 카드 (시그니처) */}
      <CouponCard
        policy={policy}
        remaining={remaining}
        status={soldOut ? 'SOLD_OUT' : 'OPEN'}
      />

      {/* 실시간 남은 수량 */}
      <Card className="p-5">
        <div className="flex items-baseline justify-between">
          <span className="text-[13px] font-bold text-sub">실시간 남은 수량</span>
          {statusQ.isFetching && <span className="text-[11px] text-sub">갱신 중</span>}
        </div>
        <p className="mt-2 nums">
          <span
            className={`text-[32px] font-extrabold ${soldOut ? 'text-danger' : 'text-ink'}`}
          >
            {comma(remaining)}
          </span>
          <span className="text-[15px] font-bold text-sub"> / {comma(total)}장 남음</span>
        </p>
        <div className="mt-3">
          <ProgressBar
            value={(total ?? 0) - (remaining ?? 0)}
            max={total ?? 0}
            tone={soldOut ? 'danger' : 'mint'}
          />
        </div>
        <p className="mt-2 text-[12px] text-sub nums">
          {comma((total ?? 0) - (remaining ?? 0))}장 발급 완료 · 발급률 {status?.issueRate ?? 0}%
        </p>
      </Card>

      {/* 오픈 예약 카운트다운 */}
      {beforeOpen && (
        <Card className="p-5 text-center">
          <p className="text-[13px] font-bold text-sub">오픈까지</p>
          <p className="mt-1 text-[36px] font-extrabold nums tracking-tight text-ink">
            {fmtCountdown(openMs - now)}
          </p>
          <p className="mt-1 text-[12px] text-sub">
            오픈 전 요청은 대기 상태로 안내돼요 (FR-10)
          </p>
        </Card>
      )}

      {/* 발급 버튼 상태 머신 */}
      <IssueButton flow={flow} beforeOpen={beforeOpen} soldOut={soldOut} ended={ended} />

      {/* 동시 접속 시뮬레이션 — 위 '발급받기'는 나 1명, 이건 수백 명이 동시에 */}
      <LoadSimulator policyId={policyId} compact />
      <p className="text-[12px] text-sub -mt-2">
        재고가 0으로 빨려 들어가는 과정은{' '}
        <Link to="/admin/dashboard" className="font-bold text-mint">관제 대시보드</Link>에서 그래프로 보여요.
      </p>

      <EvidenceNote>
        이 화면은 <b>선착순 발급 시연</b>이다. 버튼을 누르면 <code>POST /api/queue/join</code>으로
        대기열(Redis ZSET)에 등록되고, 입장(ADMITTED)하면 <code>X-Active-Token</code>과 함께{' '}
        <code>POST /api/coupon-policies/{policyId}/issue</code>를 호출한다. 서버는 Redis Lua로
        재고·중복을 원자 판별한 뒤 <b>202 Accepted</b>만 즉시 반환하고, 실제 DB 반영은 Kafka
        Consumer가 비동기로 처리한다. 그래서 버튼은 "발급 확인 중 → 발급 확정 중 → 완료"로 나뉜다.
      </EvidenceNote>
    </div>
  );
}

function IssueButton({ flow, beforeOpen, soldOut, ended }) {
  const { phase, rank, slow, start, receiptId } = flow;

  if (ended && phase !== 'done' && phase !== 'already') {
    return <Button variant="ghost" disabled>종료된 이벤트예요</Button>;
  }

  if (phase === 'done') {
    return (
      <div className="space-y-2">
        <Link to="/my-coupons">
          <Button variant="mint">발급 완료! 쿠폰함으로 이동</Button>
        </Link>
        {receiptId && (
          <p className="text-center text-[12px] text-sub nums">접수번호 {receiptId}</p>
        )}
      </div>
    );
  }

  if (phase === 'already') {
    return <Button variant="ghost" disabled>이미 받은 쿠폰이에요 (1인 1매)</Button>;
  }

  if (soldOut || phase === 'sold-out') {
    return <Button variant="ghost" disabled>쿠폰이 모두 소진되었어요</Button>;
  }

  if (beforeOpen || phase === 'not-open') {
    return <Button disabled>오픈 후 발급할 수 있어요</Button>;
  }

  if (phase === 'boot') {
    return <Button disabled loading>확인 중</Button>;
  }

  if (phase === 'queue') {
    return (
      <Button disabled loading>
        {rank != null && rank > 0 ? `대기 중 · 내 앞 ${comma(rank)}명` : '대기열 진입 중'}
      </Button>
    );
  }

  if (phase === 'issuing') {
    return <Button disabled loading>발급 확인 중…</Button>;
  }

  if (phase === 'confirm') {
    return (
      <div className="space-y-2">
        <Button disabled loading>발급 확정 중…</Button>
        {slow ? (
          <p className="text-center text-[12px] text-sub">
            접수는 완료됐어요. Consumer가 DB에 반영하는 중이니 잠시 후{' '}
            <Link to="/my-coupons" className="font-bold text-mint">쿠폰함</Link>에서 확인해주세요.
          </p>
        ) : (
          <div className="flex justify-center">
            <Pill tone="mint">서버 202 Accepted 수신 — 상태 조회 폴링 중</Pill>
          </div>
        )}
      </div>
    );
  }

  return (
    <Button onClick={start}>발급받기</Button>
  );
}
