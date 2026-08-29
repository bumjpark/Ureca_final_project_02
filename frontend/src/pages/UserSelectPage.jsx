import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../lib/session.jsx';
import { searchUsers } from '../lib/endpoints.js';
import { Button, DataTable, LoadingBlock, PageHeader } from '../components/ui.jsx';
import Pagination from '../components/Pagination.jsx';

// 검색어를 입력하면 이름/이메일 LIKE 검색, 비워두면 PK순 기본 목록을 그대로 보여준다 —
// 둘 다 서버에서 페이지네이션된 목록으로 내려와서 화면은 검색어 유무만 신경 쓰면 된다.
export default function UserSelectPage() {
  const { setUserId } = useSession();
  const navigate = useNavigate();
  const [input, setInput] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState(null);

  // 300ms 디바운스 — 키 입력마다 100만 건 테이블을 때리지 않도록
  useEffect(() => {
    const id = setTimeout(() => setQuery(input.trim()), 300);
    return () => clearTimeout(id);
  }, [input]);

  useEffect(() => {
    setPage(0);
  }, [query]);

  const q = useQuery({
    queryKey: ['user-search', query, page],
    queryFn: () => searchUsers(query, page, 10),
  });

  const results = q.data?.content ?? [];

  const submit = () => {
    if (!selected) return;
    setUserId(selected);
    navigate('/events');
  };

  return (
    <main className="mx-auto w-full max-w-[640px] px-6 py-8">
      <button className="text-[13px] text-zinc-500 mb-4" onClick={() => navigate('/')}>
        &lt; 역할 선택
      </button>

      <PageHeader title="사용자 선택" sub="목록에서 고르거나, 이름/이메일로 검색해서 찾아주세요" />

      <div className="flex flex-col gap-4 mt-6">
        <input
          autoFocus
          value={input}
          onChange={(e) => {
            setInput(e.target.value);
            setSelected(null);
          }}
          placeholder="이름 또는 이메일로 검색 (비워두면 전체 목록)"
          className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm"
        />

        {q.isLoading && <LoadingBlock />}

        {!q.isLoading && (
          <DataTable
            rowKey={(r) => r.userId}
            empty="일치하는 회원이 없어요"
            columns={[
              {
                key: 'pick',
                label: '',
                width: 36,
                render: (r) => (
                  <input
                    type="radio"
                    name="user"
                    checked={selected === r.userId}
                    onChange={() => setSelected(r.userId)}
                  />
                ),
              },
              { key: 'name', label: '이름' },
              { key: 'email', label: '이메일' },
              { key: 'userId', label: '회원 ID' },
            ]}
            rows={results}
          />
        )}

        <Pagination
          page={q.data?.page ?? 0}
          totalPages={q.data?.totalPages ?? 0}
          totalElements={q.data?.totalElements ?? 0}
          onChange={setPage}
        />

        <Button disabled={!selected} onClick={submit} className="w-full py-4">
          이 사용자로 시작하기
        </Button>
      </div>
    </main>
  );
}
