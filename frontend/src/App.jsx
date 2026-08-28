import { Suspense, lazy } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { LoadingBlock, ToastProvider } from './components/ui.jsx';
import DemoBar from './components/DemoBar.jsx';
import EventPage from './pages/EventPage.jsx';
import MyCouponsPage from './pages/MyCouponsPage.jsx';

// 차트(recharts) 무게가 큰 관제 화면은 지연 로드
const DashboardPage = lazy(() => import('./pages/DashboardPage.jsx'));
const VerificationPage = lazy(() => import('./pages/VerificationPage.jsx'));
const PoliciesPage = lazy(() => import('./pages/PoliciesPage.jsx'));

const NAV = [
  { to: '/event', label: '쿠폰 발급', kind: 'user' },
  { to: '/my-coupons', label: '내 쿠폰함', kind: 'user' },
  { to: '/admin/policies', label: '쿠폰 정책', kind: 'admin' },
  { to: '/admin/dashboard', label: '관제 대시보드', kind: 'admin' },
  { to: '/admin/verification', label: '정합성 리포트', kind: 'admin' },
];

function TopNav() {
  return (
    <header className="sticky top-0 z-30 bg-white border-b border-hairline">
      <div className="mx-auto max-w-[1200px] px-5 h-14 flex items-center gap-6">
        <span className="font-extrabold text-[17px] tracking-tight">
          my<span className="text-mint">ureca</span>
        </span>
        <nav className="flex items-center gap-1 overflow-x-auto">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              className={({ isActive }) =>
                `whitespace-nowrap px-3 h-9 inline-flex items-center rounded-lg text-[13px] font-bold transition-colors ${
                  isActive ? 'bg-ink text-white' : 'text-sub hover:text-ink hover:bg-surface'
                }`
              }
            >
              {n.label}
            </NavLink>
          ))}
        </nav>
      </div>
    </header>
  );
}

function UserShell({ children }) {
  return <main className="mx-auto w-full max-w-[480px] px-5 pb-24 pt-6">{children}</main>;
}

function AdminShell({ children }) {
  return <main className="mx-auto w-full max-w-[1200px] px-5 pb-20 pt-6">{children}</main>;
}

export default function App() {
  return (
    <ToastProvider>
      <TopNav />
      <DemoBar />
      <Routes>
        <Route path="/" element={<Navigate to="/event" replace />} />
        <Route path="/event" element={<UserShell><EventPage /></UserShell>} />
        <Route path="/my-coupons" element={<UserShell><MyCouponsPage /></UserShell>} />
        <Route
          path="/admin/policies"
          element={
            <AdminShell>
              <Suspense fallback={<LoadingBlock label="정책 화면 로딩" />}>
                <PoliciesPage />
              </Suspense>
            </AdminShell>
          }
        />
        <Route
          path="/admin/dashboard"
          element={
            <AdminShell>
              <Suspense fallback={<LoadingBlock label="대시보드 로딩" />}>
                <DashboardPage />
              </Suspense>
            </AdminShell>
          }
        />
        <Route
          path="/admin/verification"
          element={
            <AdminShell>
              <Suspense fallback={<LoadingBlock label="리포트 로딩" />}>
                <VerificationPage />
              </Suspense>
            </AdminShell>
          }
        />
        <Route path="*" element={<UserShell><p className="text-sub">없는 화면이에요.</p></UserShell>} />
      </Routes>
    </ToastProvider>
  );
}
