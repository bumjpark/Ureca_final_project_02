import { useQuery } from '@tanstack/react-query';
import { listPolicies } from './endpoints.js';

// 정책 셀렉트 박스가 필요한 화면(대기열/정합성 검증/재처리/Mock 알림 등)마다 각자
// useQuery(['admin-policies-select'], ...)를 반복하던 걸 한 곳으로 모았다. 쿼리 키가
// 동일해서 React Query 캐시는 어차피 공유됐지만, 훅으로 묶어두면 다음에 옵션을 바꿀 때
// (staleTime 등) 한 곳만 고치면 된다.
export function useAdminPolicies() {
  return useQuery({ queryKey: ['admin-policies-select'], queryFn: () => listPolicies(0, 50) });
}
