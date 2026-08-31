import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ApiError } from '../lib/api.js';
import { useDemo } from '../lib/demo.jsx';
import { createPolicy, deletePolicy, listPolicies, updatePolicy } from '../lib/endpoints.js';
import { comma, fmtDateTime } from '../lib/format.js';
import {
  Button,
  Card,
  EvidenceNote,
  ErrorBlock,
  LoadingBlock,
  Pagination,
  Pill,
  useToast,
} from '../components/ui.jsx';

const PAGE_SIZE = 10;

const EMPTY = {
  title: '',
  couponType: 'FIXED',
  discountValue: 5000,
  totalQuantity: 10000,
  openAt: plusMinutes(5),
  closeAt: '',
};

export default function PoliciesPage() {
  const qc = useQueryClient();
  const toast = useToast();
  const { setPolicyId } = useDemo();
  const [form, setForm] = useState(EMPTY);
  const [editingId, setEditingId] = useState(null);
  const [errors, setErrors] = useState(null);
  const [page, setPage] = useState(0);

  const listQ = useQuery({
    queryKey: ['policies', 'admin-list'],
    queryFn: () => listPolicies(0, 500),
  });

  // 최근 생성(id 큰) 순 정렬 후 10개씩 페이지
  const sorted = useMemo(
    () => [...(listQ.data?.content ?? [])].sort((a, b) => b.id - a.id),
    [listQ.data],
  );
  const pageCount = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE));
  useEffect(() => {
    if (page > pageCount - 1) setPage(pageCount - 1);
  }, [page, pageCount]);
  const visible = sorted.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);

  const upsert = useMutation({
    mutationFn: (payload) =>
      editingId ? updatePolicy(editingId, payload) : createPolicy(payload),
    onSuccess: (res) => {
      toast(editingId ? '정책을 수정했어요.' : `정책 #${res.id} 를 생성했어요.`, 'mint');
      qc.invalidateQueries({ queryKey: ['policies'] });
      if (!editingId && res?.id) setPolicyId(res.id);
      resetForm();
    },
    onError: (e) => {
      setErrors(e instanceof ApiError ? e.errors : null);
      toast(e.message || '저장에 실패했어요.', 'danger');
    },
  });

  const del = useMutation({
    mutationFn: (id) => deletePolicy(id),
    onSuccess: () => {
      toast('정책을 삭제(소프트)했어요.', 'plain');
      qc.invalidateQueries({ queryKey: ['policies'] });
    },
    onError: (e) => toast(e.message || '삭제에 실패했어요.', 'danger'),
  });

  const resetForm = () => {
    setForm(EMPTY);
    setEditingId(null);
    setErrors(null);
  };

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const submit = (e) => {
    e.preventDefault();
    setErrors(null);
    const payload = {
      title: form.title.trim(),
      couponType: form.couponType,
      discountValue: Number(form.discountValue),
      totalQuantity: Number(form.totalQuantity),
      openAt: toLocalDateTime(form.openAt),
      closeAt: form.closeAt ? toLocalDateTime(form.closeAt) : null,
    };
    const local = validate(payload);
    if (local.length) {
      setErrors(local);
      return;
    }
    upsert.mutate(payload);
  };

  const startEdit = (p) => {
    setEditingId(p.id);
    setErrors(null);
    setForm({
      title: p.title,
      couponType: p.couponType,
      discountValue: p.discountValue,
      totalQuantity: p.totalQuantity,
      openAt: toInputValue(p.openAt),
      closeAt: p.closeAt ? toInputValue(p.closeAt) : '',
    });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const rateHint = form.couponType === 'RATE';

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-[22px] font-extrabold text-ink">쿠폰 정책</h1>
        <p className="text-[13px] text-sub mt-1">
          시연에 쓸 캠페인을 만든다 · <code>POST/PATCH/DELETE /api/admin/coupon-policies</code>
        </p>
      </div>

      {/* 생성 / 수정 폼 */}
      <Card className="p-5">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-[15px] font-bold text-ink">
            {editingId ? `정책 #${editingId} 수정` : '새 정책 만들기'}
          </h2>
          {editingId && (
            <button onClick={resetForm} className="text-[13px] font-bold text-sub">
              새로 만들기로 전환
            </button>
          )}
        </div>

        <form onSubmit={submit} className="grid sm:grid-cols-2 gap-4">
          <Field label="제목" className="sm:col-span-2">
            <input
              required
              maxLength={100}
              value={form.title}
              onChange={set('title')}
              placeholder="예: 우레카 런칭 기념 5,000원 쿠폰"
              className={inputCls}
            />
          </Field>

          <Field label="할인 유형">
            <select value={form.couponType} onChange={set('couponType')} className={inputCls}>
              <option value="FIXED">정액 (원)</option>
              <option value="RATE">정률 (%)</option>
            </select>
          </Field>

          <Field label={rateHint ? '할인율 (1~100)' : '할인 금액 (원)'}>
            <input
              type="number"
              min={1}
              max={rateHint ? 100 : undefined}
              required
              value={form.discountValue}
              onChange={set('discountValue')}
              className={`${inputCls} nums`}
            />
          </Field>

          <Field label="총 수량 (재고)">
            <input
              type="number"
              min={1}
              required
              value={form.totalQuantity}
              onChange={set('totalQuantity')}
              className={`${inputCls} nums`}
            />
          </Field>

          <div />

          <Field label="오픈 시각 (미래여야 함)">
            <input
              type="datetime-local"
              required
              value={form.openAt}
              onChange={set('openAt')}
              className={`${inputCls} nums`}
            />
          </Field>

          <Field label="마감 시각 (선택)">
            <input
              type="datetime-local"
              value={form.closeAt}
              onChange={set('closeAt')}
              className={`${inputCls} nums`}
            />
          </Field>

          {errors?.length > 0 && (
            <ul className="sm:col-span-2 rounded-btn bg-danger-weak text-danger text-[12px] font-semibold p-3 space-y-1">
              {errors.map((msg, i) => (
                <li key={i}>· {msg}</li>
              ))}
            </ul>
          )}

          <div className="sm:col-span-2 flex gap-3">
            <Button type="submit" className="w-auto px-6 h-11" loading={upsert.isPending}>
              {editingId ? '수정 저장' : '정책 생성'}
            </Button>
            <div className="flex-1 self-center flex gap-3">
              <button
                type="button"
                onClick={() => setForm((f) => ({ ...f, openAt: plusMinutes(2) }))}
                className="text-[12px] font-bold text-mint"
              >
                곧 오픈 (+2분)
              </button>
              <span className="text-[11px] text-sub self-center">
                오픈 시각은 미래만 허용돼요 (즉시 오픈 불가)
              </span>
            </div>
          </div>
        </form>
      </Card>

      {/* 목록 */}
      {listQ.isLoading && <LoadingBlock label="정책 목록 로딩" />}
      {listQ.isError && <ErrorBlock error={listQ.error} onRetry={listQ.refetch} />}
      {listQ.isSuccess && (
        <Card className="overflow-hidden">
          <div className="px-5 pt-5 pb-3 flex items-baseline justify-between">
            <h2 className="text-[15px] font-bold text-ink">
              등록된 정책 <span className="text-sub nums">{listQ.data.totalElements}</span>
            </h2>
            <span className="text-[12px] text-sub nums">
              {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, sorted.length)} · 최근 생성순
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-[13px] min-w-[820px]">
              <thead>
                <tr className="text-sub border-y border-hairline bg-surface/60">
                  <th className="text-left font-bold px-5 py-2.5">#</th>
                  <th className="text-left font-bold px-3 py-2.5">제목</th>
                  <th className="text-left font-bold px-3 py-2.5">할인</th>
                  <th className="text-right font-bold px-3 py-2.5">재고</th>
                  <th className="text-left font-bold px-3 py-2.5">오픈</th>
                  <th className="text-left font-bold px-3 py-2.5">마감</th>
                  <th className="text-left font-bold px-3 py-2.5">상태</th>
                  <th className="text-right font-bold px-5 py-2.5">액션</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((p) => {
                  const st = policyState(p);
                  return (
                    <tr key={p.id} className="border-b border-hairline last:border-0">
                      <td className="px-5 py-3 nums text-sub">{p.id}</td>
                      <td className="px-3 py-3 font-semibold text-ink">{p.title}</td>
                      <td className="px-3 py-3 nums">
                        {p.couponType === 'RATE' ? `${p.discountValue}%` : `${comma(p.discountValue)}원`}
                      </td>
                      <td className="px-3 py-3 text-right nums">{comma(p.totalQuantity)}</td>
                      <td className="px-3 py-3 nums text-sub">{fmtDateTime(p.openAt)}</td>
                      <td className="px-3 py-3 nums text-sub">{p.closeAt ? fmtDateTime(p.closeAt) : '—'}</td>
                      <td className="px-3 py-3">
                        <Pill tone={st.tone}>{st.label}</Pill>
                      </td>
                      <td className="px-5 py-3">
                        <div className="flex items-center justify-end gap-3 whitespace-nowrap">
                          <Link to="/admin/dashboard" className="text-[12px] font-bold text-sub hover:text-ink">
                            대시보드
                          </Link>
                          {st.key === 'scheduled' && (
                            <button onClick={() => startEdit(p)} className="text-[12px] font-bold text-mint">
                              수정
                            </button>
                          )}
                          <button
                            onClick={() => {
                              if (confirm(`정책 #${p.id} "${p.title}" 을 삭제할까요?`)) del.mutate(p.id);
                            }}
                            className="text-[12px] font-bold text-danger"
                          >
                            삭제
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination page={page} pageCount={pageCount} onChange={setPage} />
        </Card>
      )}

      <EvidenceNote>
        정책은 삼각 정합성(Redis / Kafka / DB)의 <b>절대 기준값</b>이다 (<code>total_quantity</code> 등).
        수정은 <b>오픈 시각 이전</b>에만 가능하고, 삭제는 Redis 키 삭제가 선행되는 소프트 삭제다.
        <code>openAt</code>은 미래여야 하고, 정률 쿠폰의 할인율은 1~100으로 서버가 교차검증한다.
      </EvidenceNote>
    </div>
  );
}

const inputCls =
  'w-full h-10 rounded-btn border border-line bg-white px-3 text-[14px] outline-none focus:border-mint';

function Field({ label, children, className = '' }) {
  return (
    <label className={`flex flex-col gap-1.5 ${className}`}>
      <span className="text-[12px] font-bold text-sub">{label}</span>
      {children}
    </label>
  );
}

// ── datetime-local ↔ LocalDateTime ────────────────────────────
function pad(n) {
  return String(n).padStart(2, '0');
}
function plusMinutes(m) {
  const d = new Date(Date.now() + m * 60_000);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function toInputValue(iso) {
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
// datetime-local "2026-08-28T13:00" → 서버가 파싱하는 LocalDateTime 문자열 (초 포함)
function toLocalDateTime(v) {
  return v.length === 16 ? `${v}:00` : v;
}

function validate(p) {
  const errs = [];
  if (!p.title) errs.push('제목을 입력해주세요.');
  if (!(p.discountValue > 0)) errs.push('할인 값은 1 이상이어야 합니다.');
  if (p.couponType === 'RATE' && p.discountValue > 100) errs.push('정률 쿠폰의 할인율은 100을 넘을 수 없습니다.');
  if (!(p.totalQuantity > 0)) errs.push('총 수량은 1 이상이어야 합니다.');
  if (!p.openAt) errs.push('오픈 시각을 입력해주세요.');
  else if (new Date(p.openAt).getTime() <= Date.now()) errs.push('오픈 시각은 현재보다 미래여야 합니다 (@Future).');
  if (p.closeAt && new Date(p.closeAt).getTime() <= new Date(p.openAt).getTime())
    errs.push('마감 시각은 오픈 시각보다 뒤여야 합니다.');
  return errs;
}

function policyState(p) {
  if (p.deletedAt) return { key: 'deleted', label: '삭제됨', tone: 'plain' };
  const now = Date.now();
  const open = new Date(p.openAt).getTime();
  const close = p.closeAt ? new Date(p.closeAt).getTime() : null;
  if (now < open) return { key: 'scheduled', label: '오픈 예정', tone: 'plain' };
  if (close && now > close) return { key: 'ended', label: '종료', tone: 'plain' };
  return { key: 'open', label: '진행 중', tone: 'mint' };
}
