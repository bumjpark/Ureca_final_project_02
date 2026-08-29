import { useState } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getCouponDetail, changeCouponStatus } from '../lib/endpoints.js';
import { useSession } from '../lib/session.jsx';
import { Badge, Button, FieldRow, InlineError, LoadingBlock } from '../components/ui.jsx';
import { discountLabel, fmtDateTime } from '../lib/format.js';

const BADGE = { ISSUED: 'issued', USED: 'used', EXPIRED: 'expired' };
const LABEL = { ISSUED: '발급됨', USED: '사용완료', EXPIRED: '만료' };

export default function CouponUsePage() {
  const { couponIssueId } = useParams();
  const { userId } = useSession();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [reason, setReason] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const q = useQuery({
    queryKey: ['coupon-detail', couponIssueId],
    queryFn: () => getCouponDetail(couponIssueId, userId),
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['coupon-detail', couponIssueId] });

  const use = async () => {
    setBusy(true);
    setError(null);
    try {
      await changeCouponStatus(couponIssueId, { userId, status: 'USED' });
      refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const cancelUse = async () => {
    setBusy(true);
    setError(null);
    try {
      await changeCouponStatus(couponIssueId, { userId, status: 'ISSUED', reason });
      setReason('');
      refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  if (q.isLoading) return <LoadingBlock />;
  if (q.isError) return <div className="text-sm text-zinc-400 py-16 text-center">쿠폰을 찾을 수 없어요</div>;

  const c = q.data;
  const status = c.displayStatus;

  return (
    <div className="flex flex-col gap-5">
      <Link to="/my-coupons" className="text-[13px] text-zinc-500">
        &lt; 내 쿠폰함
      </Link>

      <div>
        <Badge tone={BADGE[status]}>{LABEL[status]}</Badge>
        <div className="text-xl font-bold text-zinc-900 mt-2.5">{c.title}</div>
      </div>

      <div className="flex gap-4">
        <div className="flex-[1.2] border border-zinc-300 rounded-lg px-4 self-start">
          <FieldRow label="할인 내용" value={discountLabel(c.couponType, c.discountValue)} />
          <FieldRow label="발급 일시" value={fmtDateTime(c.issuedAt)} />
          <FieldRow label="쿠폰 ID" value={<span className="font-mono">#{c.couponIssueId}</span>} />
        </div>

        <div className="flex-1 flex flex-col gap-3.5">
          <Button className="py-4" disabled={!c.usable || status !== 'ISSUED' || busy} onClick={use}>
            사용하기
          </Button>

          <Link to={`/my-coupons/${couponIssueId}/history`} className="text-xs text-zinc-500 underline underline-offset-2">
            이력 보기
          </Link>

          <InlineError message={error} />

          <div className={`border border-zinc-200 rounded-md p-3.5 ${status !== 'USED' ? 'opacity-50' : ''}`}>
            <div className="text-xs text-zinc-500 mb-2">사용취소 사유</div>
            <textarea
              rows={2}
              placeholder="사유를 입력해주세요"
              disabled={status !== 'USED' || busy}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              className="w-full box-border border border-zinc-300 rounded-md p-2.5 text-[13px] resize-none"
            />
            <Button
              variant="outline"
              className="w-full mt-2.5"
              disabled={status !== 'USED' || busy}
              onClick={cancelUse}
            >
              사용취소
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
