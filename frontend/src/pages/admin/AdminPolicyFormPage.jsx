import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { getPolicy, createPolicy, updatePolicy } from '../../lib/endpoints.js';
import { Button, InlineError, LoadingBlock, PageHeader } from '../../components/ui.jsx';

const EMPTY = { title: '', couponType: 'FIXED', discountValue: '', totalQuantity: '', openAt: '', closeAt: '' };

// LocalDateTime("2026-08-20T12:00:00") <-> <input type="datetime-local"> 값("2026-08-20T12:00") 변환
const toInputValue = (s) => (s ? s.slice(0, 16) : '');
const toServerValue = (s) => (s ? `${s}:00` : null);

export default function AdminPolicyFormPage() {
  const { policyId } = useParams();
  const editing = !!policyId;
  const navigate = useNavigate();
  const [form, setForm] = useState(EMPTY);
  const [loading, setLoading] = useState(editing);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!editing) return;
    getPolicy(policyId)
      .then((p) =>
        setForm({
          title: p.title,
          couponType: p.couponType,
          discountValue: p.discountValue,
          totalQuantity: p.totalQuantity,
          openAt: toInputValue(p.openAt),
          closeAt: toInputValue(p.closeAt),
        }),
      )
      .finally(() => setLoading(false));
  }, [editing, policyId]);

  const set = (key) => (e) => setForm((f) => ({ ...f, [key]: e.target.value }));

  const submit = async (e) => {
    e.preventDefault();
    setError(null);
    setSaving(true);
    const body = {
      title: form.title,
      couponType: form.couponType,
      discountValue: Number(form.discountValue),
      totalQuantity: Number(form.totalQuantity),
      openAt: toServerValue(form.openAt),
      closeAt: toServerValue(form.closeAt) || null,
    };
    try {
      if (editing) {
        await updatePolicy(policyId, body);
        navigate('/admin');
      } else {
        // 생성 직후에는 목록으로 보내지 않고 바로 이 정책의 작업 공간으로 이어서 —
        // "정책 생성 -> 부하테스트 -> 검증"이 화면 전환 없이 자연스럽게 이어지도록.
        const created = await createPolicy(body);
        navigate(`/admin/${created.id}`);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <LoadingBlock />;

  return (
    <div className="flex flex-col gap-5 max-w-[560px]">
      <PageHeader title={editing ? `정책 #${policyId} 수정` : '신규 정책 생성'} />

      <form onSubmit={submit} className="flex flex-col gap-4">
        <label className="flex flex-col gap-1.5 text-sm text-zinc-600">
          제목
          <input
            required
            maxLength={100}
            value={form.title}
            onChange={set('title')}
            className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm"
          />
        </label>

        <div className="flex gap-4">
          <label className="flex-1 flex flex-col gap-1.5 text-sm text-zinc-600">
            할인 유형
            <select value={form.couponType} onChange={set('couponType')} className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm">
              <option value="FIXED">FIXED (정액)</option>
              <option value="RATE">RATE (정률, 1~100)</option>
            </select>
          </label>
          <label className="flex-1 flex flex-col gap-1.5 text-sm text-zinc-600">
            할인 값
            <input
              required
              type="number"
              min="1"
              value={form.discountValue}
              onChange={set('discountValue')}
              className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm"
            />
          </label>
        </div>

        <label className="flex flex-col gap-1.5 text-sm text-zinc-600">
          총 발행 수량
          <input
            required
            type="number"
            min="1"
            value={form.totalQuantity}
            onChange={set('totalQuantity')}
            className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm"
          />
        </label>

        <div className="flex gap-4">
          <label className="flex-1 flex flex-col gap-1.5 text-sm text-zinc-600">
            오픈 일시
            <input
              required
              type="datetime-local"
              value={form.openAt}
              onChange={set('openAt')}
              className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm"
            />
          </label>
          <label className="flex-1 flex flex-col gap-1.5 text-sm text-zinc-600">
            마감 일시 (선택)
            <input
              type="datetime-local"
              value={form.closeAt}
              onChange={set('closeAt')}
              className="border border-zinc-300 rounded-md px-3 py-2.5 text-sm"
            />
          </label>
        </div>

        <InlineError message={error} />

        <div className="flex gap-3 mt-1">
          <Button type="submit" disabled={saving}>
            {editing ? '수정 저장' : '생성'}
          </Button>
          <Button type="button" variant="outline" onClick={() => navigate('/admin')}>
            취소
          </Button>
        </div>
      </form>
    </div>
  );
}
