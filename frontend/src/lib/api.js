// 백엔드 공통 에러 응답: { status, message, errors: string[]|null, timestamp, errorCode }
export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `요청 실패 (HTTP ${status})`);
    this.name = 'ApiError';
    this.status = status;
    this.body = body ?? null;
    this.errors = body?.errors ?? null;
    // 실패 원인 분기는 message 문자열이 아니라 이 코드로 한다 — message는 사람이 읽는
    // 문장이라 문구가 바뀔 수 있지만 errorCode는 고정된 계약이다(ErrorResponse 주석 참고).
    this.errorCode = body?.errorCode ?? null;
  }
}

const BASE = import.meta.env.VITE_API_BASE ?? '';

export async function api(path, { method = 'GET', body, headers, signal } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal,
  });

  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    throw new ApiError(res.status, typeof data === 'object' ? data : { message: data });
  }
  return { data, status: res.status, headers: res.headers };
}

// 응답 바디만 필요할 때
export async function apiJson(path, opts) {
  const { data } = await api(path, opts);
  return data;
}

// 브라우저 crypto 기반 Idempotency-Key
export function newIdempotencyKey() {
  if (crypto?.randomUUID) return crypto.randomUUID();
  return 'idem-' + Date.now() + '-' + Math.random().toString(16).slice(2);
}
