import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getAdminQueueStatus, updateQueueLimit } from '../../lib/endpoints.js';
import { useAdminPolicies } from '../../lib/hooks.js';
import { Button, InlineError, PageHeader } from '../../components/ui.jsx';
import PolicyPicker from '../../components/admin/PolicyPicker.jsx';
import { comma } from '../../lib/format.js';

/**
 * 대기열 처리 속도 제어 패널. `policyId`가 주어지면(정책 작업 공간에서 쓰는 경우) 그 정책에
 * 고정되고, 대기 인원을 2초마다 폴링해 보여준다 — 부하테스트 중 "대기열이 실제로 줄어드는지"를
 * 눈으로 확인하기 위한 용도. `policyId`가 없으면(독립 라우트 `/admin/queue`) 글로벌 기본값
 * 조정이 기본이고, 특정 정책을 고를 수도 있다.
 */
export function QueueControlPanel({ policyId = null, allowGlobalToggle = true }) {
  const listQ = useAdminPolicies();
  const [localPolicyId, setLocalPolicyId] = useState('');
  const [global, setGlobal] = useState(allowGlobalToggle);
  const [limit, setLimit] = useState(300);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const effectivePolicyId = policyId ?? (global ? null : (localPolicyId ? Number(localPolicyId) : null));

  const statusQ = useQuery({
    queryKey: ['admin-queue-status', effectivePolicyId],
    queryFn: () => getAdminQueueStatus(effectivePolicyId),
    enabled: effectivePolicyId != null,
    refetchInterval: 2000,
  });

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    setResult(null);
    setBusy(true);
    try {
      const res = await updateQueueLimit({ policyId: effectivePolicyId, limit: Number(limit) });
      setResult(res);
      statusQ.refetch();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-5 max-w-[480px]">
      {!policyId && (
        <PageHeader title="대기열 처리 속도 제어" sub="초당 대기열 통과 인원(admission-rate)을 실시간으로 조정합니다" />
      )}

      {effectivePolicyId != null && (
        <div className="border border-line rounded-md p-4 flex items-center justify-between">
          <div>
            <div className="text-[11px] text-sub mb-1">현재 대기 인원</div>
            <div className="text-2xl font-bold text-ink">
              {statusQ.data ? comma(statusQ.data.waitingCount) : '-'}
              <span className="text-xs font-normal text-sub ml-1">명</span>
            </div>
          </div>
          <div className="text-right text-xs text-sub">
            <div>적용 중인 처리 속도</div>
            <div className="text-ink font-semibold">
              {statusQ.data ? `${comma(statusQ.data.currentLimit)}건/s` : '-'}
              {statusQ.data?.usingDefaultLimit && <span className="text-sub font-normal"> (글로벌 기본값)</span>}
            </div>
          </div>
        </div>
      )}

      <form onSubmit={submit} className="flex flex-col gap-4">
        {allowGlobalToggle && (
          <label className="flex items-center gap-2 text-sm text-ink">
            <input type="checkbox" checked={global} onChange={(e) => setGlobal(e.target.checked)} />
            글로벌 기본값으로 설정 (특정 정책만 바꾸려면 해제)
          </label>
        )}

        {allowGlobalToggle && !global && (
          <PolicyPicker value={localPolicyId} onChange={setLocalPolicyId} policies={listQ.data?.content} />
        )}

        <label className="flex flex-col gap-1.5 text-sm text-ink">
          초당 통과 인원 (1 ~ 50,000)
          <input
            type="number"
            min="1"
            max="50000"
            value={limit}
            onChange={(e) => setLimit(e.target.value)}
            className="border border-line rounded-md px-3 py-2.5 text-sm"
          />
        </label>

        <InlineError message={error} />
        {result && (
          <div className="text-xs text-sub border border-line rounded-md p-3">
            적용 완료: {result.policyId ? `정책 #${result.policyId}` : '글로벌'} → {result.limit}건/s
          </div>
        )}

        <Button type="submit" disabled={busy || (allowGlobalToggle && !global && !localPolicyId)} className="w-full">
          적용
        </Button>
      </form>
    </div>
  );
}

export default function AdminQueueControlPage() {
  return <QueueControlPanel />;
}
