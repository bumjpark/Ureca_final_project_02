import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../lib/api.js';
import { getMyCoupons, getQueueStatus, issueCoupon, joinQueue } from '../lib/endpoints.js';
import { useToast } from '../components/ui.jsx';

/* 발급 버튼 상태 머신 — 백엔드의 202 Accepted 비동기 흐름을 그대로 UX로.
 *
 * boot      : 초기 판정 중
 * not-open  : 오픈 예약 상태 (now < openAt)
 * idle      : 발급 가능
 * queue     : 대기열 등록/폴링 중 (rank 노출)
 * issuing   : POST /issue 진행 (연타 방지, "발급 확인 중")
 * confirm   : 202 수신 후 Consumer의 DB 확정 폴링
 * done      : 쿠폰함에 확정 반영됨
 * already   : 이미 발급받음 (1인 1매)
 * sold-out  : 재고 소진
 */

const CONFIRM_INTERVAL = 1500;
const CONFIRM_MAX_TRIES = 20;

export function useIssueFlow({ policyId, userId, policy, statusRemaining }) {
  const toast = useToast();
  const [phase, setPhase] = useState('boot');
  const [rank, setRank] = useState(null);
  const [receiptId, setReceiptId] = useState(null);
  const [slow, setSlow] = useState(false);

  const timers = useRef([]);
  const running = useRef(false);
  const tokenRetried = useRef(false);
  const clearTimers = () => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  };
  const later = (fn, ms) => {
    const id = setTimeout(fn, ms);
    timers.current.push(id);
    return id;
  };

  useEffect(() => () => clearTimers(), []);

  // ── 초기 판정 ─────────────────────────────────────────────
  useEffect(() => {
    let alive = true;
    if (!policyId || !userId) return;
    setPhase('boot');
    setSlow(false);
    (async () => {
      try {
        const mine = await getMyCoupons(userId, { couponPolicyId: policyId, size: 1 });
        if (!alive) return;
        if ((mine.coupons?.length ?? 0) > 0) {
          setPhase('already');
          return;
        }
      } catch {
        /* 쿠폰함 조회 실패는 무시하고 계속 */
      }
      if (!alive) return;
      const openAt = policy?.openAt ? new Date(policy.openAt).getTime() : null;
      if (openAt && openAt > Date.now()) {
        setPhase('not-open');
        return;
      }
      if (statusRemaining === 0) {
        setPhase('sold-out');
        return;
      }
      setPhase('idle');
    })();
    return () => {
      alive = false;
    };
    // policy/status 가 준비되면 한 번만 판정. 이후 전이는 사용자 액션으로.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyId, userId, policy?.openAt]);

  // 오픈 시각 도달 시 not-open → idle 자동 해제
  useEffect(() => {
    if (phase !== 'not-open' || !policy?.openAt) return;
    const ms = new Date(policy.openAt).getTime() - Date.now();
    if (ms <= 0) {
      setPhase('idle');
      return;
    }
    const id = later(() => setPhase(statusRemaining === 0 ? 'sold-out' : 'idle'), ms + 200);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, policy?.openAt]);

  const handleApiError = useCallback(
    (e, { onDup, onSoldOut, onNotOpen } = {}) => {
      if (!(e instanceof ApiError)) {
        toast('네트워크 오류예요. 잠시 후 다시 시도해주세요.', 'danger');
        return;
      }
      if (e.status === 409) {
        toast('이미 받은 쿠폰이에요.', 'plain');
        onDup?.();
        return;
      }
      if (e.status === 400 && /소진|품절|sold/i.test(e.message)) {
        onSoldOut?.();
        return;
      }
      if (e.status === 400 && /오픈|open/i.test(e.message)) {
        onNotOpen?.();
        return;
      }
      if (e.status === 429) {
        toast('요청이 너무 잦아요. 잠시 후 다시 눌러주세요.', 'plain');
        return;
      }
      if (e.status === 503) {
        toast('대기열이 가득 찼어요. 잠시 후 다시 시도해주세요.', 'danger');
        return;
      }
      toast(e.message || '발급에 실패했어요.', 'danger');
    },
    [toast],
  );

  // ── 발급 확정 폴링 ────────────────────────────────────────
  const pollConfirm = useCallback(
    (tries = 0) => {
      later(async () => {
        try {
          const mine = await getMyCoupons(userId, { couponPolicyId: policyId, size: 1 });
          if ((mine.coupons?.length ?? 0) > 0) {
            setPhase('done');
            running.current = false;
            return;
          }
        } catch {
          /* 계속 시도 */
        }
        if (tries + 1 >= CONFIRM_MAX_TRIES) {
          setSlow(true); // 반영 지연 — 쿠폰은 접수됨, Consumer 따라잡는 중
          running.current = false;
          return;
        }
        pollConfirm(tries + 1);
      }, CONFIRM_INTERVAL);
    },
    [policyId, userId],
  );

  // ── 실제 발급 요청 ────────────────────────────────────────
  const doIssue = useCallback(
    async (activeToken) => {
      setPhase('issuing');
      try {
        const res = await issueCoupon(policyId, userId, activeToken);
        setReceiptId(res.receiptId ?? null);
        setPhase('confirm');
        pollConfirm(0);
      } catch (e) {
        if (e instanceof ApiError && e.status === 403 && !tokenRetried.current) {
          // 토큰 만료/불일치 — 대기열 한 번 더
          tokenRetried.current = true;
          start();
          return;
        }
        running.current = false;
        handleApiError(e, {
          onDup: () => setPhase('already'),
          onSoldOut: () => setPhase('sold-out'),
          onNotOpen: () => setPhase('not-open'),
        });
        if (phase === 'issuing') setPhase('idle');
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [policyId, userId, pollConfirm, handleApiError],
  );

  // ── 대기열 상태 폴링 ──────────────────────────────────────
  const pollQueue = useCallback(
    (retryAfterSec) => {
      const ms = Math.max(700, (retryAfterSec || 1) * 1000);
      later(async () => {
        try {
          const st = await getQueueStatus(policyId, userId);
          if (st.status === 'ADMITTED') {
            doIssue(st.activeToken);
            return;
          }
          if (st.status === 'SOLD_OUT') {
            setPhase('sold-out');
            running.current = false;
            return;
          }
          if (st.status === 'EXPIRED') {
            toast('대기 시간이 만료됐어요. 다시 시도해주세요.', 'plain');
            setPhase('idle');
            running.current = false;
            return;
          }
          setRank(st.rank ?? null);
          pollQueue(st.retryAfterSeconds);
        } catch (e) {
          if (e instanceof ApiError && e.status === 409) {
            setPhase('already');
            running.current = false;
            return;
          }
          if (e instanceof ApiError && e.status === 404) {
            // 대기열 등록이 아직 안 잡힘 — 재등록
            start();
            return;
          }
          pollQueue(2);
        }
      }, ms);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [policyId, userId, doIssue, toast],
  );

  // ── 시작 ─────────────────────────────────────────────────
  const start = useCallback(async () => {
    if (running.current) return;
    running.current = true;
    setSlow(false);
    setPhase('queue');
    setRank(null);
    try {
      const res = await joinQueue(policyId, userId);
      if (res.status === 'ADMITTED') {
        doIssue(res.activeToken);
        return;
      }
      setRank(res.rank ?? null);
      pollQueue(1);
    } catch (e) {
      running.current = false;
      handleApiError(e, {
        onDup: () => setPhase('already'),
        onSoldOut: () => setPhase('sold-out'),
        onNotOpen: () => setPhase('not-open'),
      });
      if (phase === 'queue') setPhase('idle');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policyId, userId, doIssue, pollQueue, handleApiError]);

  return { phase, rank, receiptId, slow, start };
}
