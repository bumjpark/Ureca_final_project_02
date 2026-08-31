const BLOCK_SIZE = 10;

// 백엔드 PageResponse(page/size/totalElements/totalPages)를 그대로 받아 페이지 이동만 담당한다.
// «(맨 앞) ‹(이전 10페이지) 1~10 번호 버튼 ›(다음 10페이지) »(맨 뒤) 구성.
export default function Pagination({ page, totalPages, totalElements, onChange }) {
  if (totalPages <= 1) return null;

  const blockStart = Math.floor(page / BLOCK_SIZE) * BLOCK_SIZE;
  const blockEnd = Math.min(blockStart + BLOCK_SIZE, totalPages);
  const pageNumbers = Array.from({ length: blockEnd - blockStart }, (_, i) => blockStart + i);

  const go = (p) => onChange(Math.max(0, Math.min(totalPages - 1, p)));

  return (
    <div className="flex items-center justify-between text-xs text-sub mt-1">
      <span>
        전체 {totalElements?.toLocaleString('ko-KR')}건 · {page + 1} / {totalPages} 페이지
      </span>
      <div className="flex items-center gap-1">
        <NavButton disabled={page <= 0} onClick={() => go(0)} title="맨 앞 페이지">
          «
        </NavButton>
        <NavButton disabled={blockStart <= 0} onClick={() => go(blockStart - BLOCK_SIZE)} title="이전 10페이지">
          ‹
        </NavButton>

        {pageNumbers.map((p) => (
          <button
            key={p}
            onClick={() => go(p)}
            className={`min-w-[28px] px-2 py-1.5 rounded-md border ${
              p === page
                ? 'bg-ink text-white border-ink font-semibold'
                : 'border-line hover:bg-surface'
            }`}
          >
            {p + 1}
          </button>
        ))}

        <NavButton disabled={blockEnd >= totalPages} onClick={() => go(blockStart + BLOCK_SIZE)} title="다음 10페이지">
          ›
        </NavButton>
        <NavButton disabled={page >= totalPages - 1} onClick={() => go(totalPages - 1)} title="맨 뒤 페이지">
          »
        </NavButton>
      </div>
    </div>
  );
}

function NavButton({ disabled, onClick, title, children }) {
  return (
    <button
      disabled={disabled}
      onClick={onClick}
      title={title}
      className="px-2.5 py-1.5 border border-line rounded-md disabled:opacity-30 hover:bg-surface"
    >
      {children}
    </button>
  );
}
