import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Button } from '../components/ui.jsx';
import { getIssueStatus } from '../lib/endpoints.js';

// 와이어프레임 IssueResult.dc.html — 202 접수증을 보여주고, 발급이 확정될 때까지 폴링한다.
//
// 발급은 Kafka 비동기라 202 직후에는 DB에 아직 없다. 예전에는 상태 조회 API가 없어서
// "내 쿠폰함에서 직접 확인하세요"로 떠넘겼는데, 그러면 유저 입장에서 "곧 될 일"과
// "잘못돼서 영영 안 될 일"이 구분되지 않았다. GET /api/coupons/receipt/{receiptId}가
// 그 둘을 PENDING/FAILED로 구분해주므로 여기서 그대로 보여준다.

const POLL_INTERVAL_MS = 1000;
// 이 횟수를 넘으면 폴링을 멈춘다. 서버가 PENDING을 계속 준다는 건 컨슈머가 밀렸거나 멈췄다는
// 뜻이라, 무한 폴링으로 서버를 더 두드리는 대신 안내로 전환한다.
const MAX_POLLS = 20;

export default function IssueResultPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const result = location.state?.result;
  const receiptId = result?.receiptId;

  // null이면 아직 첫 응답 전. 그 외에는 서버가 준 { status, coupon, note }
  const [status, setStatus] = useState(null);
  const [pollsExhausted, setPollsExhausted] = useState(false);
  const timerRef = useRef(null);

  useEffect(() => {
    if (!receiptId) return undefined;

    let cancelled = false;
    let polls = 0;

    async function poll() {
      try {
        const data = await getIssueStatus(receiptId);
        if (cancelled) return;
        setStatus(data);
        // 확정(ISSUED)이나 실패(FAILED)면 더 볼 것이 없다 — PENDING일 때만 이어서 조회한다.
        if (data.status !== 'PENDING') return;
      } catch (e) {
        // 조회 자체가 실패한 건 발급 실패가 아니다(네트워크/일시 장애). 다음 틱에 다시 시도한다.
        if (cancelled) return;
      }
      polls += 1;
      if (polls >= MAX_POLLS) {
        setPollsExhausted(true);
        return;
      }
      timerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
    }

    poll();
    return () => {
      cancelled = true;
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [receiptId]);

  if (!result) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <div className="text-sm text-zinc-500">발급 접수 정보가 없어요. 내 쿠폰함에서 확인해주세요.</div>
        <Button onClick={() => navigate('/my-coupons')}>내 쿠폰함으로</Button>
      </div>
    );
  }

  const view = describe(status, pollsExhausted, result);

  return (
    <div className="flex flex-col items-center justify-center gap-5 text-center py-14">
      <div>
        <div className="text-lg font-bold text-zinc-900 mb-1.5">{view.title}</div>
        <div className="text-sm text-zinc-500">{view.subtitle}</div>
      </div>

      <div className="border border-zinc-300 rounded-lg p-5 w-[420px] text-left">
        <div className="text-[11px] text-zinc-400 mb-1">접수번호 (receipt_id)</div>
        <div className="text-[15px] font-semibold text-zinc-900 font-mono">{result.receiptId}</div>
        <div className="h-px bg-zinc-100 my-3.5" />
        <div className="text-[11px] text-zinc-400 mb-1">상태</div>
        <div className={`text-sm font-semibold ${view.tone}`}>{view.label}</div>
        {status?.coupon && (
          <>
            <div className="h-px bg-zinc-100 my-3.5" />
            <div className="text-[11px] text-zinc-400 mb-1">쿠폰</div>
            <div className="text-sm font-semibold text-zinc-900">{status.coupon.title}</div>
            <div className="text-[13px] text-zinc-500 mt-0.5">{status.coupon.discountLabel}</div>
          </>
        )}
      </div>

      <Button variant={status?.status === 'ISSUED' ? 'primary' : 'outline'} onClick={() => navigate('/my-coupons')}>
        내 쿠폰함에서 확인하기
      </Button>
    </div>
  );
}

// 서버가 준 상태를 화면 문구로 옮긴다. status가 아직 null(첫 응답 전)이면 202 응답의
// message를 그대로 쓴다 — 접수는 이미 확정된 사실이라 그 사이에도 보여줄 것이 있다.
function describe(status, pollsExhausted, result) {
  if (status?.status === 'ISSUED') {
    return {
      title: '쿠폰이 발급되었어요',
      subtitle: '내 쿠폰함에서 바로 사용할 수 있어요',
      label: '발급 완료 (ISSUED)',
      tone: 'text-emerald-600',
    };
  }
  if (status?.status === 'FAILED') {
    return {
      title: '발급 처리가 지연되고 있어요',
      subtitle: status.note ?? '재처리 대기 중이에요. 잠시 후 내 쿠폰함에서 다시 확인해주세요.',
      label: '재처리 중 (FAILED)',
      tone: 'text-amber-600',
    };
  }
  if (pollsExhausted) {
    return {
      title: '발급 확정이 평소보다 오래 걸리고 있어요',
      subtitle: '접수는 정상적으로 완료됐어요. 잠시 후 내 쿠폰함에서 확인해주세요.',
      label: '처리 지연 (PENDING)',
      tone: 'text-zinc-600',
    };
  }
  return {
    title: '발급 요청이 접수되었어요',
    subtitle: '발급이 확정되면 이 화면에 바로 표시돼요',
    label: status ? '처리 중 (PENDING)' : (result.message ?? '처리 중'),
    tone: 'text-zinc-600',
  };
}
