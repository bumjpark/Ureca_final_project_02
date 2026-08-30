import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { useSession } from './lib/session.jsx';
import { LoadingBlock } from './components/ui.jsx';

import RoleSelectPage from './pages/RoleSelectPage.jsx';
import UserSelectPage from './pages/UserSelectPage.jsx';
import EventListPage from './pages/EventListPage.jsx';
import EventDetailPage from './pages/EventDetailPage.jsx';
import QueueWaitPage from './pages/QueueWaitPage.jsx';
import IssueResultPage from './pages/IssueResultPage.jsx';
import MyCouponsPage from './pages/MyCouponsPage.jsx';
import CouponUsePage from './pages/CouponUsePage.jsx';
import CouponHistoryPage from './pages/CouponHistoryPage.jsx';

const AdminPolicyListPage = lazy(() => import('./pages/admin/AdminPolicyListPage.jsx'));
const AdminPolicyFormPage = lazy(() => import('./pages/admin/AdminPolicyFormPage.jsx'));
const AdminPolicyWorkspacePage = lazy(() => import('./pages/admin/AdminPolicyWorkspacePage.jsx'));
const AdminQueueControlPage = lazy(() => import('./pages/admin/AdminQueueControlPage.jsx'));
const AdminVerificationPage = lazy(() => import('./pages/admin/AdminVerificationPage.jsx'));
const AdminVerificationReportDetailPage = lazy(() => import('./pages/admin/AdminVerificationReportDetailPage.jsx'));
const AdminReconciliationPage = lazy(() => import('./pages/admin/AdminReconciliationPage.jsx'));
const AdminLoadTestPage = lazy(() => import('./pages/admin/AdminLoadTestPage.jsx'));
const AdminMockNotificationPage = lazy(() => import('./pages/admin/AdminMockNotificationPage.jsx'));

// 정책 단위 작업(대기열/재처리/부하테스트)은 정책 작업 공간(/admin/:policyId) 안의 탭으로
// 들어가서 최상위 탭에서는 뺐다 — 예전처럼 매번 정책을 다시 고를 필요가 없다. 정합성 검증은
// "등록된 모든 정책을 한 번에" 돌리는 게 그 자체로 독립된 용도라 최상위 탭에 남겨둔다
// (정책별로만 보고 싶으면 각 작업 공간의 정합성 검증 탭을 쓰면 됨). 대기열/재처리/부하테스트의
// 전역 화면도 라우트 자체는 남아있고, 각 탭의 안내 링크로만 연결된다.
const ADMIN_NAV = [
  { to: '/admin', label: '정책 관리' },
  { to: '/admin/verification', label: '전체 정합성 검증' },
  { to: '/admin/mock-notifications', label: 'Mock 알림' },
];

function TopBar() {
  const { role, userId, reset } = useSession();
  const navigate = useNavigate();

  return (
    <header className="border-b border-line bg-white">
      <div className="mx-auto max-w-[1100px] px-6 h-12 flex items-center justify-between">
        <span className="font-bold text-[15px] text-ink">쿠폰 발급 시스템</span>
        <div className="flex items-center gap-4 text-xs text-sub">
          {role === 'user' && userId && <span>사용자 · id {userId}</span>}
          {role === 'admin' && <span>관리자</span>}
          {role && (
            <button
              className="text-sub hover:text-ink underline underline-offset-2"
              onClick={() => {
                reset();
                navigate('/');
              }}
            >
              역할 변경
            </button>
          )}
        </div>
      </div>
    </header>
  );
}

function AdminSubNav({ current }) {
  return (
    <div className="mx-auto max-w-[1100px] px-6 pt-4">
      <div className="flex gap-1 border-b border-line">
        {ADMIN_NAV.map((n) => (
          <a
            key={n.to}
            href={n.to}
            onClick={(e) => {
              e.preventDefault();
              current.navigate(n.to);
            }}
            className={`text-[13px] px-4 py-2.5 border-b-2 -mb-px ${
              current.path === n.to
                ? 'text-ink font-semibold border-ink'
                : 'text-sub border-transparent hover:text-ink'
            }`}
          >
            {n.label}
          </a>
        ))}
      </div>
    </div>
  );
}

function UserShell({ children }) {
  return <main className="mx-auto w-full max-w-[1100px] px-6 py-8">{children}</main>;
}

function AdminShell({ children, path }) {
  const navigate = useNavigate();
  return (
    <>
      <AdminSubNav current={{ path, navigate }} />
      <main className="mx-auto w-full max-w-[1100px] px-6 py-6">{children}</main>
    </>
  );
}

// 예전 링크(북마크 등) 호환용 — 현황 탭으로 흡수된 옛 라우트를 새 워크스페이스로 보낸다.
function LegacyStatusRedirect() {
  const { policyId } = useParams();
  return <Navigate to={`/admin/${policyId}?tab=status`} replace />;
}

function RequireRole({ role, children }) {
  const session = useSession();
  if (!session.role) return <Navigate to="/" replace />;
  if (role === 'user' && session.role === 'user' && !session.userId) {
    return <Navigate to="/select-user" replace />;
  }
  if (session.role !== role) return <Navigate to="/" replace />;
  return children;
}

export default function App() {
  return (
    <div className="min-h-screen">
      <TopBar />
      <Routes>
        <Route path="/" element={<RoleSelectPage />} />
        <Route path="/select-user" element={<UserSelectPage />} />

        <Route
          path="/events"
          element={
            <RequireRole role="user">
              <UserShell>
                <EventListPage />
              </UserShell>
            </RequireRole>
          }
        />
        <Route
          path="/events/:policyId"
          element={
            <RequireRole role="user">
              <UserShell>
                <EventDetailPage />
              </UserShell>
            </RequireRole>
          }
        />
        <Route
          path="/events/:policyId/queue"
          element={
            <RequireRole role="user">
              <UserShell>
                <QueueWaitPage />
              </UserShell>
            </RequireRole>
          }
        />
        <Route
          path="/events/:policyId/result"
          element={
            <RequireRole role="user">
              <UserShell>
                <IssueResultPage />
              </UserShell>
            </RequireRole>
          }
        />
        <Route
          path="/my-coupons"
          element={
            <RequireRole role="user">
              <UserShell>
                <MyCouponsPage />
              </UserShell>
            </RequireRole>
          }
        />
        <Route
          path="/my-coupons/:couponIssueId"
          element={
            <RequireRole role="user">
              <UserShell>
                <CouponUsePage />
              </UserShell>
            </RequireRole>
          }
        />
        <Route
          path="/my-coupons/:couponIssueId/history"
          element={
            <RequireRole role="user">
              <UserShell>
                <CouponHistoryPage />
              </UserShell>
            </RequireRole>
          }
        />

        <Route
          path="/admin/:policyId/status"
          element={
            <RequireRole role="admin">
              <LegacyStatusRedirect />
            </RequireRole>
          }
        />

        {[
          ['/admin', AdminPolicyListPage],
          ['/admin/new', AdminPolicyFormPage],
          ['/admin/:policyId/edit', AdminPolicyFormPage],
          ['/admin/:policyId', AdminPolicyWorkspacePage],
          ['/admin/queue', AdminQueueControlPage],
          ['/admin/verification', AdminVerificationPage],
          ['/admin/verification/reports/:reportId', AdminVerificationReportDetailPage],
          ['/admin/reconciliation', AdminReconciliationPage],
          ['/admin/load-test', AdminLoadTestPage],
          ['/admin/mock-notifications', AdminMockNotificationPage],
        ].map(([path, Comp]) => (
          <Route
            key={path}
            path={path}
            element={
              <RequireRole role="admin">
                <AdminShell path={path}>
                  <Suspense fallback={<LoadingBlock />}>
                    <Comp />
                  </Suspense>
                </AdminShell>
              </RequireRole>
            }
          />
        ))}

        <Route path="*" element={<UserShell><p className="text-sm text-sub">없는 화면이에요.</p></UserShell>} />
      </Routes>
    </div>
  );
}
