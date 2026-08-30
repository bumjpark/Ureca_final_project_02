import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  listPolicies,
  sendMockKakao,
  sendMockKakaoBulk,
  listMockNotificationLogs,
  listMockNotificationBulkJobs,
} from '../../lib/endpoints.js';
import { Badge, Button, Card, DataTable, InlineError, PageHeader } from '../../components/ui.jsx';
import Pagination from '../../components/Pagination.jsx';
import { comma, fmtDateTime } from '../../lib/format.js';

export default function AdminMockNotificationPage() {
  const [single, setSingle] = useState({ userId: '', templateId: '', message: '' });
  const [simulateFailure, setSimulateFailure] = useState(false);
  const [singleError, setSingleError] = useState(null);
  const [singleBusy, setSingleBusy] = useState(false);

  const [bulk, setBulk] = useState({ policyId: '', templateId: '', message: '' });
  const [bulkError, setBulkError] = useState(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [bulkResult, setBulkResult] = useState(null);

  const [logPolicyFilter, setLogPolicyFilter] = useState('');
  const [page, setPage] = useState(0);

  const [jobPolicyFilter, setJobPolicyFilter] = useState('');
  const [jobPage, setJobPage] = useState(0);

  const policiesQ = useQuery({ queryKey: ['admin-policies-select'], queryFn: () => listPolicies(0, 50) });

  const logsQ = useQuery({
    queryKey: ['mock-notification-logs', logPolicyFilter, page],
    queryFn: () => listMockNotificationLogs({ policyId: logPolicyFilter ? Number(logPolicyFilter) : undefined, page, size: 15 }),
    refetchInterval: 3000, // 일괄 발송은 백그라운드로 계속 쌓이므로 자동 갱신
  });

  const jobsQ = useQuery({
    queryKey: ['mock-notification-bulk-jobs', jobPolicyFilter, jobPage],
    queryFn: () => listMockNotificationBulkJobs({ policyId: jobPolicyFilter ? Number(jobPolicyFilter) : undefined, page: jobPage, size: 10 }),
    // 진행 중인 작업이 하나라도 있으면 짧은 주기로 갱신 — 다 끝나면 자동으로 멈춘다.
    refetchInterval: (query) => (query.state.data?.content?.some((j) => j.status === 'IN_PROGRESS') ? 1000 : false),
  });

  const setSingleField = (key) => (e) => setSingle((f) => ({ ...f, [key]: e.target.value }));
  const setBulkField = (key) => (e) => setBulk((f) => ({ ...f, [key]: e.target.value }));

  const submitSingle = async (e) => {
    e.preventDefault();
    setSingleError(null);
    setSingleBusy(true);
    try {
      await sendMockKakao(
        { userId: Number(single.userId), templateId: single.templateId, message: single.message },
        simulateFailure,
      );
      logsQ.refetch();
    } catch (err) {
      setSingleError(err.message);
    } finally {
      setSingleBusy(false);
    }
  };

  const submitBulk = async (e) => {
    e.preventDefault();
    setBulkError(null);
    setBulkBusy(true);
    setBulkResult(null);
    try {
      const res = await sendMockKakaoBulk({
        policyId: Number(bulk.policyId),
        templateId: bulk.templateId,
        message: bulk.message,
      });
      setBulkResult(res);
      setLogPolicyFilter(String(res.policyId));
      setPage(0);
      setJobPolicyFilter(String(res.policyId));
      setJobPage(0);
      jobsQ.refetch();
    } catch (err) {
      setBulkError(err.message);
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Mock 카카오 알림톡 발송" sub="실제 외부 연동 없이 발송을 흉내 낸다(FR-5) — 결과는 아래 로그에 실제로 저장돼요" />

      <div className="grid grid-cols-2 gap-4">
        <Card>
          <div className="text-sm font-semibold text-ink mb-3">단건 발송</div>
          <form onSubmit={submitSingle} className="flex flex-col gap-3">
            <input
              required
              type="number"
              placeholder="userId"
              value={single.userId}
              onChange={setSingleField('userId')}
              className="border border-line rounded-md px-3 py-2.5 text-sm"
            />
            <input
              required
              placeholder="templateId"
              value={single.templateId}
              onChange={setSingleField('templateId')}
              className="border border-line rounded-md px-3 py-2.5 text-sm"
            />
            <textarea
              required
              rows={2}
              placeholder="message"
              value={single.message}
              onChange={setSingleField('message')}
              className="border border-line rounded-md px-3 py-2.5 text-sm resize-none"
            />
            <label className="flex items-center gap-2 text-xs text-sub">
              <input type="checkbox" checked={simulateFailure} onChange={(e) => setSimulateFailure(e.target.checked)} />
              강제 실패 시뮬레이션
            </label>
            <InlineError message={singleError} />
            <Button type="submit" disabled={singleBusy} className="w-fit">
              발송
            </Button>
          </form>
        </Card>

        <Card>
          <div className="text-sm font-semibold text-ink mb-1">정책별 일괄 발송</div>
          <div className="text-xs text-sub mb-3">그 정책으로 쿠폰을 발급받은 유저 전원에게 보내요</div>
          <form onSubmit={submitBulk} className="flex flex-col gap-3">
            <select
              required
              value={bulk.policyId}
              onChange={setBulkField('policyId')}
              className="border border-line rounded-md px-3 py-2.5 text-sm"
            >
              <option value="">정책 선택</option>
              {(policiesQ.data?.content ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  #{p.id} {p.title}
                </option>
              ))}
            </select>
            <input
              required
              placeholder="templateId"
              value={bulk.templateId}
              onChange={setBulkField('templateId')}
              className="border border-line rounded-md px-3 py-2.5 text-sm"
            />
            <textarea
              required
              rows={2}
              placeholder="message"
              value={bulk.message}
              onChange={setBulkField('message')}
              className="border border-line rounded-md px-3 py-2.5 text-sm resize-none"
            />
            <InlineError message={bulkError} />
            {bulkResult && (
              <div className="text-xs text-sub border border-line rounded-md p-2.5">
                정책 #{bulkResult.policyId} 수신자 {bulkResult.targetCount}명에게 발송을 접수했어요 — 아래{' '}
                <b className="text-ink">정책별 발송 현황</b>에서 진행 상황을 확인하세요
              </div>
            )}
            <Button type="submit" disabled={bulkBusy} className="w-fit">
              일괄 발송
            </Button>
          </form>
        </Card>
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm font-semibold text-ink">정책별 발송 현황</div>
            <div className="text-xs text-sub mt-0.5">정책별로 일괄 발송이 진행 중인지, 일부만 끝났는지 확인해요</div>
          </div>
          <select
            value={jobPolicyFilter}
            onChange={(e) => {
              setJobPolicyFilter(e.target.value);
              setJobPage(0);
            }}
            className="border border-line rounded-md px-3 py-2 text-[13px]"
          >
            <option value="">전체 정책</option>
            {(policiesQ.data?.content ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                #{p.id} {p.title}
              </option>
            ))}
          </select>
        </div>

        <DataTable
          rowKey={(r) => r.id}
          empty="일괄 발송 이력이 없어요"
          columns={[
            { key: 'policyId', label: '정책', render: (r) => `#${r.policyId}` },
            { key: 'templateId', label: 'templateId' },
            {
              key: 'status',
              label: '상태',
              render: (r) => <Badge tone={r.status === 'COMPLETED' ? 'ok' : 'soon'}>{r.status === 'COMPLETED' ? '완료' : '진행중'}</Badge>,
            },
            {
              key: 'progress',
              label: '진행률',
              render: (r) => {
                const done = r.sentCount + r.failedCount;
                const pct = r.targetCount > 0 ? Math.round((done / r.targetCount) * 100) : 100;
                return (
                  <div className="flex flex-col gap-1 min-w-[140px]">
                    <div className="flex justify-between text-[11px] text-sub">
                      <span>
                        {comma(done)} / {comma(r.targetCount)}
                      </span>
                      <span>
                        성공 {comma(r.sentCount)} · 실패 {comma(r.failedCount)}
                      </span>
                    </div>
                    <div className="h-1.5 bg-surface rounded-full overflow-hidden">
                      <div
                        className={`h-full ${r.failedCount > 0 ? 'bg-danger' : 'bg-mint'}`}
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                  </div>
                );
              },
            },
            { key: 'createdAt', label: '시작 일시', render: (r) => fmtDateTime(r.createdAt) },
            { key: 'completedAt', label: '완료 일시', render: (r) => (r.completedAt ? fmtDateTime(r.completedAt) : '-') },
          ]}
          rows={jobsQ.data?.content ?? []}
        />

        <Pagination
          page={jobsQ.data?.page ?? 0}
          totalPages={jobsQ.data?.totalPages ?? 0}
          totalElements={jobsQ.data?.totalElements ?? 0}
          onChange={setJobPage}
        />
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <div className="text-sm font-semibold text-ink">발송 이력</div>
          <select
            value={logPolicyFilter}
            onChange={(e) => {
              setLogPolicyFilter(e.target.value);
              setPage(0);
            }}
            className="border border-line rounded-md px-3 py-2 text-[13px]"
          >
            <option value="">전체</option>
            {(policiesQ.data?.content ?? []).map((p) => (
              <option key={p.id} value={p.id}>
                #{p.id} {p.title}
              </option>
            ))}
          </select>
        </div>

        <DataTable
          rowKey={(r) => r.id}
          empty="발송 기록이 없어요"
          columns={[
            { key: 'time', label: '발송 일시', render: (r) => fmtDateTime(r.createdAt) },
            { key: 'userId', label: 'userId' },
            { key: 'policyId', label: '정책', render: (r) => (r.couponPolicyId ? `#${r.couponPolicyId}` : '단건') },
            { key: 'templateId', label: 'templateId' },
            { key: 'messageId', label: 'messageId', render: (r) => <span className="font-mono text-xs">{r.messageId}</span> },
            { key: 'status', label: '상태', render: (r) => <Badge tone={r.status === 'SENT' ? 'ok' : 'bad'}>{r.status}</Badge> },
          ]}
          rows={logsQ.data?.content ?? []}
        />

        <Pagination
          page={logsQ.data?.page ?? 0}
          totalPages={logsQ.data?.totalPages ?? 0}
          totalElements={logsQ.data?.totalElements ?? 0}
          onChange={setPage}
        />
      </div>
    </div>
  );
}
