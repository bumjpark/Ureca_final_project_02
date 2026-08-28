import { getCouponStatus } from './endpoints.js';

/* 관제 대시보드 시계열의 모듈 싱글턴 저장소.
 *
 * 컴포넌트 state 가 아니라 여기 두므로, 대시보드를 벗어났다가 돌아와도
 * "누적 발급 확정 / 초당 발급 확정" 그래프가 그대로 유지된다.
 * 폴링도 이 스토어가 소유하므로 화면을 이동해도 계속 축적된다
 * (재고가 소진되어 done 이 되면 폴링·축적을 멈추고 그래프를 고정).
 *
 * 리셋: 관제 대상 정책 변경 / 부하 시뮬레이터 "부하 시작". */

const MAX_POINTS = 400;
const POLL_MS = 1000;

let state = emptyState(null);
let timer = null;
const listeners = new Set();

function emptyState(policyId) {
  return {
    policyId,
    points: [], // { clock, issued, rps, t }
    done: false,
    issued: 0,
    total: 0,
    remaining: 0,
    issueRate: 0,
    updatedAt: null,
    _last: null, // { t, issued } — rps 계산용
  };
}

function snapshot() {
  const { _last, ...pub } = state;
  return pub;
}

function emit() {
  const s = snapshot();
  listeners.forEach((fn) => fn(s));
}

export function subscribeMetrics(fn) {
  listeners.add(fn);
  fn(snapshot());
  return () => listeners.delete(fn);
}

export function getMetrics() {
  return snapshot();
}

function addSample({ issued, total, remaining, issueRate }) {
  const at = Date.now();
  state.issued = issued;
  state.total = total;
  state.remaining = remaining;
  state.issueRate = issueRate;
  state.updatedAt = at;

  // 소진(발급 완료) 이후에는 KPI 는 계속 갱신하되 시계열은 그 지점에서 고정
  if (!state.done) {
    let rps = 0;
    if (state._last) {
      const dt = (at - state._last.t) / 1000;
      if (dt > 0) rps = Math.max(0, Math.round((issued - state._last.issued) / dt));
    }
    state._last = { t: at, issued };
    const clock = new Date(at).toLocaleTimeString('ko-KR', { hour12: false });
    state.points = [...state.points, { clock, issued, rps, t: at }].slice(-MAX_POINTS);
    if (total > 0 && issued >= total) {
      state.done = true;
      stopPolling();
    }
  }
  emit();
}

async function tick() {
  const pid = state.policyId;
  if (!pid) return;
  try {
    const s = await getCouponStatus(pid);
    if (state.policyId !== pid) return; // 도중에 정책이 바뀜
    addSample({
      issued: s.issuedQuantity ?? 0,
      total: s.totalQuantity ?? 0,
      remaining: s.remainingQuantity ?? 0,
      issueRate: s.issueRate ?? 0,
    });
  } catch {
    /* 일시 오류는 무시하고 다음 tick */
  }
}

function loop() {
  timer = setTimeout(async () => {
    await tick();
    if (timer) loop(); // stopPolling 이 timer 를 null 로 만들었으면 중단
  }, POLL_MS);
}

function stopPolling() {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
}

function startPolling() {
  if (timer || state.done || !state.policyId) return;
  tick(); // 즉시 1회
  loop();
}

/** 대시보드 마운트 시 호출. 정책이 바뀌었으면 리셋. */
export function startMetrics(policyId) {
  if (!policyId) return;
  if (state.policyId !== policyId) {
    stopPolling();
    state = emptyState(policyId);
    emit();
  }
  startPolling();
}

/** 부하 시작 등으로 "0에서 다시" 그리고 싶을 때. */
export function resetMetrics() {
  const pid = state.policyId;
  stopPolling();
  state = emptyState(pid);
  emit();
  startPolling();
}
