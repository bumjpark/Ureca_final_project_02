import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Card, PageHeader } from '../../components/ui.jsx';

/**
 * 부하테스트 안내 패널. 웹에서 k6를 직접 트리거하는 API는 없다(도커 CLI로만 실행) — 실행 방법
 * 안내 + 결과 확인 동선만 제공한다. `policyId`가 주어지면(정책 작업 공간) 안내 명령어의
 * POLICY_ID를 실제 값으로 채워서 그대로 복사해 실행할 수 있게 한다.
 *
 * PowerShell/bash 문법이 서로 안 섞이게 셸별로 명령을 따로 준다 — `C=(...)`/`"${C[@]}"`는 bash
 * 전용 배열 문법이라 PowerShell에 그대로 붙여넣으면 파싱 에러가 난다.
 */
export function LoadTestPanel({ policyId = null }) {
  const [shell, setShell] = useState('powershell');

  const upCommand = 'docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build';
  const runBase = 'docker compose -f docker-compose.yml -f docker-compose.loadtest.yml --profile loadtest run --rm k6 run /scripts/burst-20k.js';

  const commands = {
    powershell: policyId
      ? `${upCommand}\n$env:POLICY_ID = "${policyId}"\n${runBase}`
      : `${upCommand}\n${runBase}`,
    bash: policyId
      ? `${upCommand}\nPOLICY_ID=${policyId} ${runBase}`
      : `${upCommand}\n${runBase}`,
  };

  return (
    <div className="flex flex-col gap-5 max-w-[640px]">
      {!policyId && (
        <PageHeader title="부하테스트" sub="웹에서 직접 실행하는 기능은 없어요 — 아래 CLI 명령으로 실행합니다" />
      )}

      <Card>
        <div className="flex items-center justify-between mb-2">
          <div className="text-sm font-semibold text-ink">전부 도커로 실행 (권장)</div>
          <div className="flex gap-1 text-[11px]">
            {[
              ['powershell', 'PowerShell'],
              ['bash', 'bash / Git Bash'],
            ].map(([key, label]) => (
              <button
                key={key}
                onClick={() => setShell(key)}
                className={`px-2 py-1 rounded ${shell === key ? 'bg-ink text-white' : 'text-sub hover:text-ink'}`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        <pre className="bg-ink text-line text-xs rounded-btn p-3 overflow-x-auto whitespace-pre-wrap">
          {commands[shell]}
        </pre>
        {shell === 'bash' && (
          <div className="text-[11px] text-sub mt-2">
            Git Bash에서는 <code className="font-mono">/scripts/...</code> 같은 경로를 자기 마음대로 윈도우
            경로로 바꿔버릴 수 있어요 — k6가 "파일을 못 찾겠다"고 하면 두 번째 줄 맨 앞에{' '}
            <code className="font-mono">MSYS_NO_PATHCONV=1</code>을 붙여서 다시 실행해보세요.
          </div>
        )}
      </Card>

      <Card>
        <div className="text-sm font-semibold text-ink mb-2">옵션</div>
        <div className="text-xs text-sub leading-relaxed">
          POLICY_ID(대상 정책{policyId ? ` — 지금 위 명령에 #${policyId}로 채워져 있어요` : ', 생략 시 자동 생성'}) ·
          STOCK(재고) · <b>PEAK(최대 VU, 기본 500 — "20k"로 돌리려면 반드시 지정)</b> ·
          RAMP/HOLD(램프업/유지 시간) · MODE(vus | arrival) ·
          MAX_USER_ID(생략하면 실제 시딩된 유저 수를 자동으로 물어봐서 맞춰요 — 직접 지정할 땐
          시딩된 유저 수보다 크게 잡지 마세요, FK 위반으로 대량 실패해요) · QUEUE_LIMIT(대기열 처리 속도)
          <br />
          {shell === 'powershell' ? (
            <>PowerShell에서는 위처럼 실행 전에 <code className="font-mono">$env:이름 = "값"</code>으로 하나씩 설정하세요.</>
          ) : (
            <>bash에서는 <code className="font-mono">STOCK=5000 PEAK=200 ...</code>처럼 명령 앞에 여러 개 나열할 수 있어요.</>
          )}
        </div>
      </Card>

      <div className="text-sm text-sub">
        실행 중 대기열이 줄어드는지는 {policyId ? '대기열' : '위 정책의 작업 공간 → 대기열'} 탭에서, 초과 발급 0건
        확인은{' '}
        <Link
          to={policyId ? `/admin/${policyId}?tab=verification` : '/admin/verification'}
          className="text-ink underline underline-offset-2"
        >
          정합성 검증 {policyId ? '탭' : '리포트'}
        </Link>
        에서 확인하세요.
      </div>
    </div>
  );
}

export default function AdminLoadTestPage() {
  return <LoadTestPanel />;
}
