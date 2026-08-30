import { ApiError } from './api.js';
import { getQueueStatus, issueCoupon, joinQueue } from './endpoints.js';

/* 브라우저 기반 동시 발급 시뮬레이터 (모듈 싱글턴).
 *
 * "발급받기" 한 번이 아니라, 수백 명의 가상 유저가 동시에
 * queue/join → status 폴링 → issue 전체 흐름을 반복하게 만들어
 * 재고가 소진되는 과정을 관제 대시보드에서 실시간으로 보이게 한다.
 *
 * 싱글턴이라 /event 에서 시작하고 /admin/dashboard 로 이동해도 계속 돈다.
 *
 * 브라우저 커넥션·Vite 프록시 한계상 진짜 20,000 동시요청은 못 낸다
 * (그건 loadtest/burst-20k.js = k6). 여기서는 수백 워커로 충분히
 * 그래프가 솟고 재고가 0으로 빨려 들어가는 장면을 만든다. */

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const randomUserId = () => 1 + Math.floor(Math.random() * 1_000_000);

let current = null; // { policyId, workers, stop(), stats, running }
const listeners = new Set();

function notify() {
  const snap = getBurstState();
  listeners.forEach((fn) => fn(snap));
}

export function subscribeBurst(fn) {
  listeners.add(fn);
  fn(getBurstState());
  return () => listeners.delete(fn);
}

export function getBurstState() {
  if (!current) return { running: false, policyId: null, workers: 0, stats: null };
  return {
    running: current.running,
    policyId: current.policyId,
    workers: current.workers,
    stats: { ...current.stats, elapsedMs: Date.now() - current.stats.startedAt },
  };
}

export function stopBurst() {
  if (current) {
    current.running = false;
    current = null;
    notify();
  }
}

export function startBurst({ policyId, workers = 400, rampMs = 20_000 }) {
  if (current) return; // 이미 실행 중
  if (!policyId) return;

  const stats = {
    issued: 0,
    dup: 0,
    soldout: 0,
    queueFull: 0,
    rateLimited: 0,
    error: 0,
    inFlight: 0,
    attempts: 0,
    startedAt: Date.now(),
  };

  const ctl = { policyId: Number(policyId), workers, stats, running: true };
  current = ctl;

  function classify(e) {
    if (e instanceof ApiError) {
      if (e.status === 409) return void stats.dup++;
      if (e.status === 429) return void stats.rateLimited++;
      if (e.status === 503) return void stats.queueFull++;
      if (e.status === 400) return void stats.soldout++;
    }
    stats.error++;
  }

  async function oneUser() {
    const userId = randomUserId();
    stats.attempts++;

    let res;
    try {
      res = await joinQueue(ctl.policyId, userId);
    } catch (e) {
      classify(e);
      return;
    }

    let token = res.activeToken || null;
    let tries = 0;
    while (!token && ctl.running && tries < 40) {
      tries++;
      try {
        const st = await getQueueStatus(ctl.policyId, userId);
        if (st.status === 'ADMITTED') {
          token = st.activeToken;
          break;
        }
        if (st.status === 'SOLD_OUT') {
          stats.soldout++;
          return;
        }
        if (st.status === 'EXPIRED') return;
        await sleep(Math.max(300, (Number(st.retryAfterSeconds) || 1) * 1000));
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) {
          stats.dup++;
          return;
        }
        await sleep(800);
      }
    }
    if (!token) return;

    try {
      await issueCoupon(ctl.policyId, userId, token);
      stats.issued++;
    } catch (e) {
      classify(e);
    }
  }

  async function worker() {
    while (ctl.running) {
      stats.inFlight++;
      try {
        await oneUser();
      } finally {
        stats.inFlight--;
      }
      if (!ctl.running) break;
      await sleep(40);
    }
  }

  // 램프업: 워커를 rampMs 동안 고르게 투입 → 트래픽이 점진적으로 몰린다
  (async () => {
    const gap = workers > 0 ? rampMs / workers : 0;
    for (let i = 0; i < workers && ctl.running; i++) {
      worker();
      if (gap) await sleep(gap);
    }
  })();

  notify();
}
