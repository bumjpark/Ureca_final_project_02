import { useQuery } from '@tanstack/react-query';
import { useLocation } from 'react-router-dom';
import { useDemo } from '../lib/demo.jsx';
import { listPolicies } from '../lib/endpoints.js';
import PolicySelect from './PolicySelect.jsx';

/* 로그인이 없으므로(FR-1) 시연 대상을 여기서 고른다.
 * - 유저 화면(/event, /my-coupons): userId + 쿠폰 정책 (마스킹·1인 1매를 다른 유저로 눌러보기)
 * - 관제 화면(/admin/*): 쿠폰 정책만. 대시보드·검증은 정책 단위라 userId 개념이 없다. */
export default function DemoBar() {
  const { pathname } = useLocation();
  const isAdmin = pathname.startsWith('/admin');
  const { userId, policyId, setUserId, setPolicyId } = useDemo();

  const { data: policies } = useQuery({
    queryKey: ['policies', 'select'],
    queryFn: () => listPolicies(0, 500),
    staleTime: 10_000,
    refetchOnWindowFocus: true, // 다른 곳에서 정책을 만들거나 지웠을 때 탭 복귀 시 갱신
  });

  const list = policies?.content ?? [];

  return (
    <div className="bg-surface border-b border-line">
      <div className="mx-auto max-w-[1200px] px-5 py-2 flex flex-wrap items-center gap-x-5 gap-y-2 text-[12px]">
        <span className="font-bold text-sub">
          {isAdmin ? '관제 대상' : '시연 컨텍스트'}
        </span>

        {!isAdmin && (
          <label className="flex items-center gap-1.5">
            <span className="text-sub">userId</span>
            <input
              type="number"
              min={1}
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              className="w-24 h-7 rounded-md border border-line bg-white px-2 nums font-semibold"
            />
          </label>
        )}

        <div className="flex items-center gap-1.5">
          <span className="text-sub">쿠폰 정책</span>
          {list.length > 0 ? (
            <PolicySelect policies={list} value={policyId} onChange={setPolicyId} />
          ) : (
            <input
              type="number"
              min={1}
              value={policyId}
              onChange={(e) => setPolicyId(e.target.value)}
              className="w-20 h-7 rounded-md border border-line bg-white px-2 nums font-semibold"
            />
          )}
        </div>
      </div>
    </div>
  );
}
