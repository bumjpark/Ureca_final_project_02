import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useDemo } from '../lib/demo.jsx';
import { changeCouponStatus, getCouponHistory, getMyCoupons } from '../lib/endpoints.js';
import { newIdempotencyKey } from '../lib/api.js';
import { comma, fmtDateTime } from '../lib/format.js';
import { discountLabel } from '../components/CouponCard.jsx';
import {
  BottomSheet,
  Button,
  Card,
  EvidenceNote,
  ErrorBlock,
  LoadingBlock,
  Pill,
  StatusBadge,
  useToast,
} from '../components/ui.jsx';

const TABS = [
  { key: 'available', label: '사용 가능', match: (c) => c.displayStatus === 'ISSUED' },
  { key: 'used', label: '사용 완료', match: (c) => c.displayStatus === 'USED' },
  { key: 'expired', label: '만료·취소', match: (c) => c.displayStatus === 'EXPIRED' },
];

export default function MyCouponsPage() {
  const { userId } = useDemo();
  const [tab, setTab] = useState('available');
  const [selected, setSelected] = useState(null);

  const q = useQuery({
    queryKey: ['my-coupons', userId],
    queryFn: () => getMyCoupons(userId, { size: 100 }),
    enabled: !!userId,
  });

  const coupons = q.data?.coupons ?? [];
  const user = q.data?.user;
  const buckets = useMemo(() => {
    const m = { available: [], used: [], expired: [] };
    for (const c of coupons) {
      const t = TABS.find((x) => x.match(c));
      if (t) m[t.key].push(c);
    }
    return m;
  }, [coupons]);

  return (
    <div className="space-y-6">
      {/* 마스킹된 유저 정보 — 마스킹이 스펙임을 보여주는 화면 (FR-2 / NFR-5) */}
      <Card className="p-5">
        <p className="text-[13px] font-bold text-sub">내 계정</p>
        {q.isLoading ? (
          <p className="mt-2 text-[15px] text-sub">불러오는 중…</p>
        ) : (
          <>
            <p className="mt-2 text-[18px] font-extrabold text-ink nums">
              {user?.name ?? '-'}{' '}
              <span className="text-[14px] font-bold text-sub">({user?.email ?? '-'})</span>
            </p>
            <p className="mt-1 text-[12px] text-sub">
              이름·이메일은 서버 응답 단계에서 마스킹된다. 원본은 API로 나가지 않는다.
            </p>
          </>
        )}
      </Card>

      {/* 탭 */}
      <div className="flex gap-1 rounded-btn bg-surface p-1">
        {TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`flex-1 h-9 rounded-[9px] text-[13px] font-bold transition-colors ${
              tab === t.key ? 'bg-white text-ink shadow-card' : 'text-sub'
            }`}
          >
            {t.label}
            <span className="ml-1 nums">{buckets[t.key].length}</span>
          </button>
        ))}
      </div>

      {q.isLoading && <LoadingBlock label="쿠폰함을 불러오는 중" />}
      {q.isError && <ErrorBlock error={q.error} onRetry={q.refetch} />}

      {q.isSuccess && (
        <div className="space-y-3">
          {buckets[tab].length === 0 && (
            <Card className="p-8 text-center text-[14px] text-sub">해당하는 쿠폰이 없어요.</Card>
          )}
          {buckets[tab].map((c) => (
            <CouponRow key={c.couponIssueId} coupon={c} onClick={() => setSelected(c)} />
          ))}
        </div>
      )}

      <EvidenceNote>
        이 화면은 <b>상태 관리 + 마스킹</b>의 증거다. 상태 뱃지는 백엔드 실제 모델
        (<code>ISSUED / USED / EXPIRED</code>)을 그대로 쓴다. 쿠폰을 누르면{' '}
        <code>GET /api/coupons/&#123;id&#125;/history</code>의 상태 전이 이력을 타임라인으로 보여주고,
        사용/취소는 <code>Idempotency-Key</code> 헤더와 함께 <code>POST /api/coupons/&#123;id&#125;/use</code>
        를 호출한다. 같은 키로 다시 보내면 서버가 재실행 없이 <code>replayed</code> 응답만 돌려준다 (FR-13).
      </EvidenceNote>

      <CouponSheet
        coupon={selected}
        userId={userId}
        onClose={() => setSelected(null)}
      />
    </div>
  );
}

function CouponRow({ coupon, onClick }) {
  return (
    <button onClick={onClick} className="block w-full text-left">
      <Card className="p-4 flex items-center gap-4 active:bg-surface transition-colors">
        <div className="flex-1 min-w-0">
          <p className="text-[12px] font-bold text-sub truncate">{coupon.title}</p>
          <p className="mt-0.5 text-[20px] font-extrabold text-ink nums">{discountLabel(coupon)}</p>
          <p className="mt-1 text-[12px] text-sub nums">
            {coupon.expiresAt ? `${fmtDateTime(coupon.expiresAt)}까지` : '소진 시까지'}
          </p>
        </div>
        <StatusBadge status={coupon.displayStatus} />
      </Card>
    </button>
  );
}

/* ── 하단 시트: 상태 전이 타임라인 + 사용/취소 + 멱등성 시연 ──── */
function CouponSheet({ coupon, userId, onClose }) {
  const open = !!coupon;
  const toast = useToast();
  const qc = useQueryClient();

  // 이 시트에서 쓰는 Idempotency-Key 를 고정해 둔다.
  // "동일 요청 재전송" 버튼이 같은 키를 재사용 → 서버는 replayed=true 로만 응답.
  const [idemKey, setIdemKey] = useState(null);
  const [lastResult, setLastResult] = useState(null);

  const historyQ = useQuery({
    queryKey: ['coupon-history', coupon?.couponIssueId],
    queryFn: () => getCouponHistory(coupon.couponIssueId),
    enabled: open,
  });

  const mutation = useMutation({
    mutationFn: ({ status, key }) =>
      changeCouponStatus(coupon.couponIssueId, { userId, status, reason: null }, key),
    onSuccess: (res) => {
      setLastResult(res);
      if (res.replayed) {
        toast('이미 처리된 요청이에요. 이전 결과를 그대로 반환했어요.', 'plain');
      } else {
        toast(res.message || '처리했어요.', 'mint');
      }
      qc.invalidateQueries({ queryKey: ['my-coupons', userId] });
      qc.invalidateQueries({ queryKey: ['coupon-history', coupon.couponIssueId] });
    },
    onError: (e) => toast(e.message || '처리에 실패했어요.', 'danger'),
  });

  if (!open) return null;

  const canUse = coupon.displayStatus === 'ISSUED' && coupon.usable;
  const canCancel = coupon.status === 'USED';
  const targetStatus = canCancel ? 'ISSUED' : 'USED';
  const actionLabel = canCancel ? '사용 취소' : '쿠폰 사용';

  const send = (reuse) => {
    let key = idemKey;
    if (!reuse || !key) {
      key = newIdempotencyKey();
      setIdemKey(key);
    }
    mutation.mutate({ status: targetStatus, key });
  };

  const timeline = buildTimeline(coupon, historyQ.data);

  return (
    <BottomSheet open={open} onClose={onClose} title="쿠폰 상세 · 상태 이력">
      <div className="space-y-5">
        <div>
          <p className="text-[12px] font-bold text-sub">{coupon.title}</p>
          <p className="mt-1 text-[26px] font-extrabold text-ink nums">{discountLabel(coupon)}</p>
          <div className="mt-2 flex items-center gap-2">
            <StatusBadge status={coupon.displayStatus} />
            <span className="text-[12px] text-sub nums">접수번호 {coupon.receiptId}</span>
          </div>
        </div>

        {/* 상태 변경 이력 타임라인 */}
        <div>
          <p className="text-[13px] font-bold text-ink mb-3">상태 변경 이력</p>
          {historyQ.isLoading ? (
            <p className="text-[13px] text-sub">이력을 불러오는 중…</p>
          ) : (
            <ol className="relative border-l-2 border-line pl-4 space-y-4">
              {timeline.map((node, i) => (
                <li key={i} className="relative">
                  <span
                    className={`absolute -left-[22px] top-1 h-3 w-3 rounded-full border-2 border-white ${
                      node.done ? 'bg-mint' : 'bg-line'
                    }`}
                  />
                  <p className="text-[13px] font-bold text-ink">{node.label}</p>
                  <p className="text-[12px] text-sub nums">{node.at ? fmtDateTime(node.at) : '—'}</p>
                  {node.meta && <p className="text-[11px] text-sub mt-0.5">{node.meta}</p>}
                </li>
              ))}
            </ol>
          )}
        </div>

        {/* 사용 / 취소 */}
        {(canUse || canCancel) && (
          <div className="space-y-2">
            <Button
              variant={canCancel ? 'ghost' : 'primary'}
              loading={mutation.isPending}
              onClick={() => send(false)}
            >
              {actionLabel}
            </Button>

            {idemKey && (
              <div className="rounded-btn bg-surface p-3">
                <p className="text-[12px] font-bold text-ink">멱등성 시연</p>
                <p className="mt-1 text-[11px] text-sub break-all nums">
                  Idempotency-Key: {idemKey}
                </p>
                <button
                  onClick={() => send(true)}
                  disabled={mutation.isPending}
                  className="mt-2 text-[12px] font-bold text-mint disabled:text-sub"
                >
                  동일 요청 다시 보내기 →
                </button>
                {lastResult && (
                  <div className="mt-2">
                    <Pill tone={lastResult.replayed ? 'plain' : 'mint'}>
                      {lastResult.replayed
                        ? 'replayed = true · 재실행 없음'
                        : 'applied · 이번 요청으로 반영됨'}
                    </Pill>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </BottomSheet>
  );
}

function buildTimeline(coupon, history) {
  const rows = Array.isArray(history) ? [...history] : [];
  const nodes = [];

  // 1. 발급 요청 접수 (202 Accepted) — coupon_issue.issued_at 시점
  nodes.push({
    label: '발급 요청 접수 (202 Accepted)',
    at: coupon.issuedAt,
    done: true,
    meta: 'Redis Lua 원자 판별 후 Kafka 발행',
  });

  // 2~. coupon_history 의 상태 전이
  const labelFor = (prev, next) => {
    if (prev === 'NONE') return '발급 확정 (Consumer가 DB 반영)';
    if (prev === 'ISSUED' && next === 'USED') return '사용 처리';
    if (prev === 'USED' && next === 'ISSUED') return '사용 취소 (USED → ISSUED 복귀)';
    if (next === 'EXPIRED') return '만료 처리';
    return `${prev} → ${next}`;
  };

  for (const h of rows) {
    nodes.push({
      label: labelFor(h.prevStatus, h.newStatus),
      at: h.createdAt,
      done: true,
      meta: h.requestId ? `request_id ${short(h.requestId)}` : undefined,
    });
  }

  return nodes;
}

const short = (s) => (s && s.length > 18 ? `${s.slice(0, 8)}…${s.slice(-6)}` : s);
