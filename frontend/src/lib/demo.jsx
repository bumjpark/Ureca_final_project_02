import { createContext, useContext, useEffect, useState } from 'react';

/* 로그인 기능이 없는 시스템(FR-1)이라, 시연에 쓸 userId / policyId 를
   화면 상단에서 직접 고른다. localStorage 에 저장해 새로고침해도 유지. */

const DemoContext = createContext(null);

const KEY = 'myureca.demo';

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return JSON.parse(raw);
  } catch {
    /* noop */
  }
  return { userId: 1, policyId: 1 };
}

export function DemoProvider({ children }) {
  const [state, setState] = useState(load);

  useEffect(() => {
    try {
      localStorage.setItem(KEY, JSON.stringify(state));
    } catch {
      /* noop */
    }
  }, [state]);

  const value = {
    userId: state.userId,
    policyId: state.policyId,
    setUserId: (userId) => setState((s) => ({ ...s, userId: Number(userId) || 0 })),
    setPolicyId: (policyId) => setState((s) => ({ ...s, policyId: Number(policyId) || 0 })),
  };

  return <DemoContext.Provider value={value}>{children}</DemoContext.Provider>;
}

export function useDemo() {
  const ctx = useContext(DemoContext);
  if (!ctx) throw new Error('useDemo must be used within DemoProvider');
  return ctx;
}
