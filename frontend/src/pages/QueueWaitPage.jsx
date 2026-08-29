import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { getQueueStatus, issueCoupon } from '../lib/endpoints.js';
import { useSession } from '../lib/session.jsx';
import ErrorState from '../components/ErrorState.jsx';
import { LoadingBlock } from '../components/ui.jsx';
import { comma } from '../lib/format.js';
import { ApiError } from '../lib/api.js';

// 와이어프레임 Queue.dc.html — 대기 순번 + 예상 대기시간을 2단 컬럼으로 보여준다.
// join 응답이 이미 ADMITTED였으면 폴링 없이 바로 발급을 시도한다.
export default function QueueWaitPage() {
  const { policyId } = useParams();
  const { userId } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const initialJoin = location.state?.join;

  const [status, setStatus] = useState(initialJoin ?? null);
  const [errorCode, setErrorCode] = useState(null);
  const issuedRef = useRef(false);

  const doIssue = async (activeToken) => {
    if (issuedRef.current) return;
    issuedRef.current = true;
    try {
      const res = await issueCoupon(Number(policyId), userId, activeToken);
      navigate(`/events/${policyId}/result`, { state: { result: res } });
    } catch {
      setErrorCode('GENERIC');
    }
  };

  useEffect(() => {
    if (initialJoin?.status === 'ADMITTED' && initialJoin.activeToken) {
      doIssue(initialJoin.activeToken);
      return;
    }

    let cancelled = false;
    let timer;

    const poll = async () => {
      try {
        const s = await getQueueStatus(Number(policyId), userId);
        if (cancelled) return;
        setStatus(s);
        if (s.status === 'ADMITTED') {
          doIssue(s.activeToken);
          return;
        }
        if (s.status === 'SOLD_OUT') {
          setErrorCode('SOLD_OUT');
          return;
        }
        if (s.status === 'EXPIRED') {
          setErrorCode('QUEUE_EXPIRED');
          return;
        }
        const wait = Math.max(1, s.retryAfterSeconds || 1) * 1000;
        timer = setTimeout(poll, wait);
      } catch (e) {
        if (cancelled) return;
        // QueueService.getQueueStatus()는 대기열 미등록(404)/이미 발급 완료(409)를
        // 예외로 던진다 — 이 둘은 재시도해도 절대 안 풀리는데, 예전엔 전부 "네트워크
        // 오류"로 취급해 2초마다 무한 재시도하며 화면이 "대기 중"에 멈춰 있었다.
        if (e instanceof ApiError && e.status === 404) {
          setErrorCode('QUEUE_EXPIRED');
          return;
        }
        if (e instanceof ApiError && e.status === 409) {
          setErrorCode('DUPLICATE');
          return;
        }
        timer = setTimeout(poll, 2000);
      }
    };

    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyId, userId]);

  if (errorCode) return <ErrorState code={errorCode} onAction={() => navigate('/events')} />;
  if (!status) return <LoadingBlock label="대기열 진입 중..." />;

  return (
    <div className="flex flex-col items-center justify-center gap-6 text-center py-10">
      <div className="text-sm text-zinc-600">현재 접속량이 많아 순서대로 안내드리고 있어요</div>

      <div className="flex gap-4 w-[560px]">
        <div className="flex-1 border border-zinc-300 rounded-lg p-7">
          <div className="text-xs text-zinc-400 mb-2">나의 대기 순번</div>
          <div className="text-4xl font-bold text-zinc-900">
            {comma(status.rank)} <span className="text-base text-zinc-400 font-normal">번</span>
          </div>
        </div>
        <div className="flex-1 border border-zinc-300 rounded-lg p-5 flex flex-col justify-center gap-4 text-left">
          <div className="flex justify-between items-baseline">
            <span className="text-[11px] text-zinc-400">예상 대기시간</span>
            <span className="text-[15px] font-semibold text-zinc-900">
              약 {Math.ceil((status.estimatedWaitSeconds || 0) / 60)}분
            </span>
          </div>
          <div className="flex justify-between items-baseline">
            <span className="text-[11px] text-zinc-400">앞에 대기중</span>
            <span className="text-[15px] font-semibold text-zinc-900">{comma(status.rank)}명</span>
          </div>
        </div>
      </div>

      <div className="text-xs text-zinc-500">
        <span className="inline-block w-1.5 h-1.5 rounded-full bg-zinc-900 mr-1.5 align-middle" />
        자동으로 순번을 확인하고 있어요
      </div>

      <div className="border border-dashed border-zinc-300 rounded-md px-4 py-3 text-[11px] text-zinc-400 leading-relaxed w-[560px] text-left">
        <b className="text-zinc-600">주의</b> 새로고침하거나 뒤로가기를 하면 대기 순번이 초기화될 수 있습니다. 이 화면을 그대로 유지해주세요.
      </div>
    </div>
  );
}
