import { useQuery } from '@tanstack/react-query';
import { getHealth } from '../../lib/endpoints.js';

const LABEL = { mysql: 'MySQL', redis: 'Redis', kafka: 'Kafka' };
const ORDER = ['mysql', 'redis', 'kafka'];

// 부하테스트 도중 Redis/Kafka를 일부러 죽여보는 시나리오에서 "지금 뭐가 죽었는지"를 바로
// 보여주기 위한 상시 표시 바. getHealth(deep=true)는 이미 구현돼 있었는데 그동안 어느
// 화면에서도 쓰인 적이 없었다.
export default function InfraStatusBar() {
  const q = useQuery({
    queryKey: ['infra-health'],
    queryFn: () => getHealth(true),
    refetchInterval: 3000,
  });

  const components = q.data?.components ?? {};
  const keys = ORDER.filter((k) => components[k]).concat(
    Object.keys(components).filter((k) => !ORDER.includes(k)),
  );

  if (keys.length === 0) return null;

  return (
    <div className="flex items-center gap-2 text-[11px]">
      {keys.map((k) => {
        const c = components[k];
        const up = c.status === 'UP';
        return (
          <span
            key={k}
            title={c.detail ?? (up ? `${c.latencyMs}ms` : '')}
            className={`inline-flex items-center gap-1 px-2 py-1 rounded ${
              up ? 'bg-zinc-100 text-zinc-500' : 'bg-zinc-900 text-white'
            }`}
          >
            <span className={`w-1.5 h-1.5 rounded-full ${up ? 'bg-zinc-400' : 'bg-white'}`} />
            {LABEL[k] ?? k} {up ? 'UP' : 'DOWN'}
          </span>
        );
      })}
    </div>
  );
}
