import { createContext, useContext, useEffect, useState } from 'react';

/* 로그인 기능이 없는 시스템(FR-1)이라, 최초 진입 시 역할(사용자/관리자)만 고르고
   사용자라면 이어서 userId를 직접 입력한다(유저 검색 API가 없어 목록에서 고를 수 없음 —
   Docs/Frontend-Screens-Spec.md E2 참고). localStorage에 저장해 새로고침해도 유지. */

const SessionContext = createContext(null);
const KEY = 'myureca.session';

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    /* noop */
  }
  return { role: null, userId: null };
}

export function SessionProvider({ children }) {
  const [state, setState] = useState(load);

  useEffect(() => {
    try {
      localStorage.setItem(KEY, JSON.stringify(state));
    } catch {
      /* noop */
    }
  }, [state]);

  const value = {
    role: state.role, // 'user' | 'admin' | null
    userId: state.userId,
    setRole: (role) => setState((s) => ({ ...s, role })),
    setUserId: (userId) => setState((s) => ({ ...s, userId: Number(userId) || null })),
    // 역할 선택 화면으로 되돌아간다 (역할/유저 초기화)
    reset: () => setState({ role: null, userId: null }),
  };

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const ctx = useContext(SessionContext);
  if (!ctx) throw new Error('useSession must be used within SessionProvider');
  return ctx;
}
