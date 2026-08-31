import { useEffect, useState } from 'react';
import { getBurstState, startBurst, stopBurst, subscribeBurst } from '../lib/loadgen.js';
import { comma } from '../lib/format.js';
import { Button, Card, Pill } from './ui.jsx';

/* 동시 발급 시뮬레이터. loadgen 싱글턴을 제어·구독한다.
 * 싱글턴이라 이 컴포넌트가 여러 화면에 있어도, 화면을 이동해도 부하는 계속 돈다. */
export default function LoadSimulator({ policyId, compact = false }) {
  const [workers, setWorkers] = useState(compact ? 300 : 500);
  const [ramp, setRamp] = useState(20);
  const [state, setState] = useState(getBurstState());

  useEffect(() => subscribeBurst(setState), []);

  // 실행 중엔 stats 를 주기적으로 갱신
  useEffect(() => {
    if (!state.running) return;
    const id = setInterval(() => setState(getBurstState()), 400);
    return () => clearInterval(id);
  }, [state.running]);

  const running = state.running;
  const runningOther = running && state.policyId !== Number(policyId);
  const s = state.stats;
  const avgRps = s && s.elapsedMs > 0 ? Math.round((s.issued / s.elapsedMs) * 1000) : 0;

  return (
    <Card className="p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-[15px] font-bold text-ink">동시 발급 시뮬레이터</h2>
        <Pill tone={running ? 'mint' : 'plain'}>
          {running ? `실행 중 · 정책 #${state.policyId}` : '대기'}
        </Pill>
      </div>
      <p className="text-[12px] text-sub mt-1">
        가상 유저 {comma(workers)}명이 {ramp}초에 걸쳐 몰려들어 <code>join → status 폴링 → issue</code>를 반복한다.
        브라우저 기반이라 진짜 20,000 동시요청은 <code>loadtest/burst-20k.js</code>(k6)로.
      </p>

      {!running && (
        <div className={`mt-4 grid ${compact ? 'grid-cols-1 sm:grid-cols-2' : 'sm:grid-cols-2'} gap-4`}>
          <label className="text-[12px] font-bold text-sub">
            가상 유저 수 <span className="text-ink nums">{comma(workers)}</span>
            <input
              type="range"
              min={50}
              max={1000}
              step={50}
              value={workers}
              onChange={(e) => setWorkers(Number(e.target.value))}
              className="w-full mt-1 accent-[color:var(--color-mint)]"
            />
          </label>
          <label className="text-[12px] font-bold text-sub">
            램프업 <span className="text-ink nums">{ramp}s</span>
            <input
              type="range"
              min={0}
              max={60}
              step={5}
              value={ramp}
              onChange={(e) => setRamp(Number(e.target.value))}
              className="w-full mt-1 accent-[color:var(--color-mint)]"
            />
          </label>
        </div>
      )}

      {running && s && (
        <div className="mt-4 grid grid-cols-3 sm:grid-cols-6 gap-3">
          <Stat label="발급 성공" value={s.issued} tone="mint" />
          <Stat label="중복 차단" value={s.dup} />
          <Stat label="소진 거절" value={s.soldout} />
          <Stat label="대기열 포화" value={s.queueFull} />
          <Stat label="초당 제한" value={s.rateLimited} />
          <Stat label="진행 중" value={s.inFlight} />
        </div>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-3">
        {running ? (
          <Button variant="danger" className="w-auto px-6 h-11" onClick={stopBurst}>
            부하 중지
          </Button>
        ) : (
          <Button
            className="w-auto px-6 h-11"
            onClick={() => startBurst({ policyId, workers, rampMs: ramp * 1000 })}
            disabled={!policyId}
          >
            부하 시작
          </Button>
        )}
        {running && s && (
          <span className="text-[12px] text-sub nums">
            누적 발급 {comma(s.issued)} · 평균 {comma(avgRps)}건/s · {(s.elapsedMs / 1000).toFixed(0)}s 경과
          </span>
        )}
      </div>

      {runningOther && (
        <p className="mt-2 text-[12px] text-danger font-semibold">
          지금 정책 #{state.policyId} 에 부하가 돌고 있어요. 이 화면의 관제 대상(#{policyId})과 달라요.
        </p>
      )}
    </Card>
  );
}

function Stat({ label, value, tone = 'plain' }) {
  const color = tone === 'mint' ? 'text-mint' : 'text-ink';
  return (
    <div className="rounded-btn bg-surface p-2.5">
      <p className="text-[11px] font-bold text-sub">{label}</p>
      <p className={`mt-0.5 text-[18px] font-extrabold nums ${color}`}>{comma(value)}</p>
    </div>
  );
}
