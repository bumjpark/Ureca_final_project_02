import { useNavigate } from 'react-router-dom';
import { useSession } from '../lib/session.jsx';

export default function RoleSelectPage() {
  const { setRole } = useSession();
  const navigate = useNavigate();

  const pick = (role) => {
    setRole(role);
    navigate(role === 'user' ? '/select-user' : '/admin');
  };

  return (
    <main className="mx-auto w-full max-w-[1100px] px-6 py-8">
      <div className="flex flex-col items-center justify-center gap-8 py-20">
        <div className="text-center">
          <div className="text-2xl font-bold text-zinc-900">쿠폰 발급 시스템</div>
          <div className="text-sm text-zinc-400 mt-2">어떤 화면으로 시작할까요?</div>
        </div>

        <div className="flex gap-5">
          <button
            onClick={() => pick('user')}
            className="w-80 text-left border border-zinc-300 rounded-lg p-7 flex flex-col gap-2 hover:border-zinc-900 transition-colors"
          >
            <div className="text-[17px] font-bold text-zinc-900">사용자로 시작하기</div>
            <div className="text-[13px] text-zinc-500">쿠폰을 조회하고 발급받아요</div>
          </button>
          <button
            onClick={() => pick('admin')}
            className="w-80 text-left border border-zinc-300 rounded-lg p-7 flex flex-col gap-2 hover:border-zinc-900 transition-colors"
          >
            <div className="text-[17px] font-bold text-zinc-900">관리자로 시작하기</div>
            <div className="text-[13px] text-zinc-500">쿠폰 정책과 발급 현황을 관리해요</div>
          </button>
        </div>

        <div className="text-xs text-zinc-400">별도 로그인 없이 역할만 선택하면 바로 이동합니다</div>
      </div>
    </main>
  );
}
