import { useEffect, useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getPolicy, joinQueue } from '../lib/endpoints.js';
import { Badge, Button, FieldRow, LoadingBlock } from '../components/ui.jsx';
import ErrorState from '../components/ErrorState.jsx';
import { useSession } from '../lib/session.jsx';
import { comma, fmtDateTime, discountLabel } from '../lib/format.js';
import { ApiError } from '../lib/api.js';

function useCountdown(target) {
  const [remainMs, setRemainMs] = useState(() => (target ? target.getTime() - Date.now() : null));
  useEffect(() => {
    if (!target) return;
    const id = setInterval(() => setRemainMs(target.getTime() - Date.now()), 1000);
    return () => clearInterval(id);
  }, [target]);
  return remainMs;
}

function fmtCountdown(ms) {
  if (ms == null) return '--:--:--';
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = String(Math.floor(total / 3600)).padStart(2, '0');
  const m = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
  const s = String(total % 60).padStart(2, '0');
  return `${h} : ${m} : ${s}`;
}

// join_queue.lua 결과를 사용자에게 보여줄 케이스로 매핑
function mapJoinError(e) {
  if (!(e instanceof ApiError)) return 'NETWORK';
  if (e.status === 409) return 'DUPLICATE';
  if (e.status === 503) return 'QUEUE_FULL';
  if (e.status === 400) {
    const msg = e.message || '';
    if (msg.includes('오픈')) return 'NOT_OPEN';
    return 'SOLD_OUT';
  }
  return 'GENERIC';
}

export default function EventDetailPage() {
  const { policyId } = useParams();
  const { userId } = useSession();
  const navigate = useNavigate();
  const [errorCode, setErrorCode] = useState(null);
  const [joining, setJoining] = useState(false);

  const q = useQuery({ queryKey: ['policy', policyId], queryFn: () => getPolicy(policyId) });
  const openAt = q.data ? new Date(q.data.openAt) : null;
  const remainMs = useCountdown(openAt);
  const isOpen = remainMs != null && remainMs <= 0;

  const handleJoin = async () => {
    setJoining(true);
    setErrorCode(null);
    try {
      const res = await joinQueue(Number(policyId), userId);
      navigate(`/events/${policyId}/queue`, { state: { join: res } });
    } catch (e) {
      setErrorCode(mapJoinError(e));
    } finally {
      setJoining(false);
    }
  };

  if (q.isLoading) return <LoadingBlock />;
  if (q.isError) return <ErrorState code="GENERIC" message="정책을 불러오지 못했어요." onAction={() => navigate('/events')} />;
  if (errorCode) return <ErrorState code={errorCode} onAction={() => navigate('/events')} />;

  const policy = q.data;
  const closed = policy.closeAt && new Date(policy.closeAt) < new Date();

  return (
    <div className="flex flex-col gap-5">
      <Link to="/events" className="text-[13px] text-zinc-500">
        &lt; 목록으로
      </Link>

      <div>
        <Badge tone={closed ? 'done' : isOpen ? 'live' : 'soon'}>
          {closed ? '마감' : isOpen ? '진행중' : '오픈 예정'}
        </Badge>
        <div className="text-[22px] font-bold text-zinc-900 mt-2.5">{policy.title}</div>
      </div>

      <div className="flex gap-4">
        <div className="flex-[1.2] border border-zinc-300 rounded-lg px-4">
          <FieldRow label="할인 내용" value={discountLabel(policy.couponType, policy.discountValue)} />
          <FieldRow label="총 발행 수량" value={`${comma(policy.totalQuantity)}장`} />
          <FieldRow label="오픈 일시" value={fmtDateTime(policy.openAt)} />
          <FieldRow label="마감 일시" value={policy.closeAt ? fmtDateTime(policy.closeAt) : '없음'} />
        </div>

        <div className="flex-1 flex flex-col gap-4">
          {!isOpen && !closed && (
            <div className="border border-dashed border-zinc-300 rounded-lg p-6 text-center">
              <div className="text-xs text-zinc-400 mb-2">오픈까지 남은 시간</div>
              <div className="text-3xl font-bold text-zinc-900 tracking-wide">{fmtCountdown(remainMs)}</div>
            </div>
          )}
          <Button className="py-4" disabled={!isOpen || closed || joining} onClick={handleJoin}>
            {closed ? '마감된 이벤트예요' : isOpen ? (joining ? '접수 중...' : '발급받기') : '발급받기 (오픈 전)'}
          </Button>
        </div>
      </div>
    </div>
  );
}
