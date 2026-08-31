import { useNavigate } from 'react-router-dom';
import { useSession } from '../lib/session.jsx';

// 진입 화면. 화면 정중앙에 떠 있는 카드 안에서 좌우 2분할
// (왼쪽=브랜드 패널 / 오른쪽=역할 선택). 상단바는 App에서 이 경로에만 숨긴다.

function UserIcon(props) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" {...props}>
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 20c0-3.5 3-6 7-6s7 2.5 7 6" />
    </svg>
  );
}

function AdminIcon(props) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="M12 3l7 3v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3z" />
      <path d="M9 12l2 2 4-4" />
    </svg>
  );
}

function Chevron(props) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="M9 6l6 6-6 6" />
    </svg>
  );
}

function RoleRow({ icon: Icon, title, desc, onClick }) {
  return (
    <button
      onClick={onClick}
      className="group flex w-full items-center gap-5 rounded-card border border-line bg-white p-7 text-left
                 transition-colors hover:border-ink hover:bg-surface"
    >
      <span
        className="flex h-14 w-14 shrink-0 items-center justify-center rounded-btn bg-surface text-ink
                   transition-colors group-hover:bg-ink group-hover:text-white"
      >
        <Icon className="h-7 w-7" />
      </span>
      <span className="flex-1">
        <span className="block text-[19px] font-bold text-ink">{title}</span>
        <span className="mt-1 block text-[14px] text-sub">{desc}</span>
      </span>
      <Chevron className="h-5 w-5 shrink-0 text-sub transition-transform group-hover:translate-x-0.5 group-hover:text-ink" />
    </button>
  );
}

export default function RoleSelectPage() {
  const { setRole } = useSession();
  const navigate = useNavigate();

  const pick = (role) => {
    setRole(role);
    navigate(role === 'user' ? '/select-user' : '/admin');
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-surface px-6 py-10">
      <div className="flex w-full max-w-[1440px] overflow-hidden rounded-card border border-line bg-white shadow-card">
        {/* 브랜드 패널 */}
        <aside className="relative hidden min-h-[640px] w-[44%] flex-col justify-between overflow-hidden bg-ink p-16 text-white lg:flex">
          <div
            className="pointer-events-none absolute -right-24 -top-24 h-72 w-72 rounded-full bg-mint/25 blur-3xl"
            aria-hidden
          />
          <div
            className="pointer-events-none absolute -bottom-20 -left-16 h-56 w-56 rounded-full bg-mint/10 blur-3xl"
            aria-hidden
          />

          <div className="relative flex items-center gap-2.5">
            <span className="h-3 w-3 rounded-[4px] bg-mint" />
            <span className="text-[15px] font-bold tracking-[-0.01em]">쿠폰 발급 시스템</span>
          </div>

          <div className="relative">
            <h1 className="text-[50px] font-bold leading-[1.12] tracking-[-0.02em]">
              선착순 쿠폰,
              <br />
              밀리지 않게.
            </h1>
            <p className="mt-5 max-w-[340px] text-[16px] leading-relaxed text-white/55">
              대기열 입장부터 발급, 정합성 검증까지 한 화면에서 관리합니다.
            </p>
          </div>

          <div className="relative flex items-center gap-2 text-[12px] font-semibold tracking-[0.04em] text-white/40">
            <span>실시간 대기열</span>
            <span className="text-white/20">·</span>
            <span>정합성 검증</span>
            <span className="text-white/20">·</span>
            <span>부하 테스트</span>
          </div>
        </aside>

        {/* 역할 선택 패널 */}
        <div className="flex flex-1 items-center justify-center p-10 sm:p-20">
          <div className="w-full max-w-[540px]">
            <div className="mb-4 flex items-center gap-2.5 lg:hidden">
              <span className="h-3 w-3 rounded-[4px] bg-mint" />
              <span className="text-[15px] font-bold text-ink">쿠폰 발급 시스템</span>
            </div>

            <div className="text-[13px] font-bold uppercase tracking-[0.14em] text-mint">시작하기</div>
            <h2 className="mt-3 text-[30px] font-bold tracking-[-0.01em] text-ink">역할을 선택하세요</h2>
            <p className="mt-3 text-[15px] text-sub">로그인 없이 역할만 고르면 바로 이동합니다.</p>

            <div className="mt-9 flex flex-col gap-4">
              <RoleRow
                icon={UserIcon}
                title="사용자로 시작하기"
                desc="쿠폰을 조회하고 발급받아요"
                onClick={() => pick('user')}
              />
              <RoleRow
                icon={AdminIcon}
                title="관리자로 시작하기"
                desc="쿠폰 정책과 발급 현황을 관리해요"
                onClick={() => pick('admin')}
              />
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}
