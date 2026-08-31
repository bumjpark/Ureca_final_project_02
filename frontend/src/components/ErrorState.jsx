import { Button } from './ui.jsx';

// 와이어프레임 ErrorState.dc.html — join/issue 실패를 case별로 매핑해 재사용한다.
const CASES = {
  SOLD_OUT: {
    title: '쿠폰이 모두 소진되었습니다',
    desc: '아쉽지만 이번 이벤트의 쿠폰이 모두 발급 완료되었어요',
  },
  DUPLICATE: {
    title: '이미 발급받은 쿠폰이에요',
    desc: '내 쿠폰함에서 확인해주세요',
  },
  QUEUE_EXPIRED: {
    title: '대기 시간이 초과되었어요',
    desc: '처음부터 다시 시도해주세요',
  },
  NOT_OPEN: {
    title: '아직 발급이 시작되지 않았어요',
    desc: '오픈 시각 이후에 다시 시도해주세요',
  },
  QUEUE_FULL: {
    title: '대기열이 가득 찼어요',
    desc: '잠시 후 다시 시도해주세요',
  },
  NETWORK: {
    title: '일시적인 오류가 발생했어요',
    desc: '잠시 후 다시 시도해주세요',
  },
  GENERIC: {
    title: '문제가 발생했어요',
    desc: '잠시 후 다시 시도해주세요',
  },
};

export default function ErrorState({ code = 'GENERIC', message, actionLabel = '목록으로 돌아가기', onAction }) {
  const c = CASES[code] ?? CASES.GENERIC;
  return (
    <div className="flex flex-col items-center justify-center gap-5 flex-grow text-center py-16">
      <svg width="56" height="56" viewBox="0 0 24 24" fill="none" stroke="var(--color-sub)" strokeWidth="1.5">
        <circle cx="12" cy="12" r="9.25" />
        <line x1="12" y1="7.5" x2="12" y2="13" />
        <circle cx="12" cy="16.5" r="0.9" fill="var(--color-sub)" stroke="none" />
      </svg>
      <div>
        <div className="text-lg font-bold text-ink mb-1.5">{c.title}</div>
        <div className="text-sm text-sub leading-relaxed">{message ?? c.desc}</div>
      </div>
      {onAction && (
        <Button onClick={onAction} className="px-7">
          {actionLabel}
        </Button>
      )}
    </div>
  );
}
