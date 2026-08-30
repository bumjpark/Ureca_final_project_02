// 검증 불일치 유형별 "이게 무슨 뜻이고, 왜 생기고, 뭘 해야 하는가"를 한 곳에 모아둔다.
// 화면은 이 표를 읽어서 판단 결과를 그린다 — 유형이 늘면 여기만 고치면 된다.
//
// 근거: VerificationAsyncTrigger.performVerification() / MismatchReportWriter.write()

export const SEVERITY = {
  critical: { label: '심각', tone: 'bad', rank: 3 },
  warning: { label: '주의', tone: 'soon', rank: 2 },
  info: { label: '참고', tone: 'plain', rank: 1 },
};

export const DISCREPANCY_INFO = {
  OVERSOLD: {
    label: '초과 발급',
    severity: 'critical',
    scope: 'policy',
    what: 'DB에 확정된 발급 건수가 정책의 총 발행 수량보다 많다.',
    why:
      '재고 차감(Redis Lua의 DECR)과 발급 확정 사이의 원자성이 깨졌을 때만 나온다. ' +
      'Lua가 단일 스레드로 원자 실행되는 정상 경로에서는 발생할 수 없는 값이다.',
    action: '즉시 해당 정책의 발급을 중단하고 초과분 처리 방침을 정해야 한다. NFR-1(초과 발급 0건) 위반이다.',
    autoQueued: false,
  },
  STOCK_LEAK: {
    label: '재고 누수',
    severity: 'critical',
    scope: 'policy',
    what: 'Redis 재고는 줄었는데 그만큼 발급이 확정되지 않았다 — 재고가 증발했다.',
    why: '발급 Lua는 성공해 재고를 깎았지만 이후 DB 확정까지 도달하지 못한 건이 누적된 경우다.',
    action:
      'Redis 재구성(재고 재계산)으로 실제 발급 건수 기준으로 재고를 맞춰야 한다. ' +
      '그대로 두면 팔 수 있는 재고를 못 판다.',
    autoQueued: false,
  },
  REDIS_ONLY: {
    label: 'Redis에만 있음',
    severity: 'critical',
    scope: 'user',
    what: 'Redis 발급 완료(issued) 목록에는 있는데 DB coupon_issue에는 대응 행이 없다.',
    why: '발급은 성공 처리됐지만 Kafka 발행 또는 소비가 실패해 DB 반영이 누락됐다.',
    action:
      '유저는 발급받았다고 알고 있는데 DB에는 없는 상태라 사용자 영향이 직접적이다. ' +
      '재처리 결과를 반드시 확인해야 한다.',
    autoQueued: true,
  },
  DB_ONLY: {
    label: 'DB에만 있음',
    severity: 'warning',
    scope: 'user',
    what: 'DB에는 정상 발급으로 확정됐는데 Redis 발급 완료(issued) 목록에는 없다.',
    why:
      'DB 커밋 후 Redis 상태를 issued로 옮기는 후처리(CouponIssuedEventProcessor의 커밋 후 콜백)가 ' +
      '끝나지 못한 경우다. 예약(reserved) 상태에 그대로 남아있는 경우가 많다.',
    action:
      '유저 입장에서는 정상 발급이라 당장의 피해는 없다. 다만 Redis 기준 중복 차단이 느슨해지므로 ' +
      'Redis 재구성으로 issued 목록을 다시 만들어주는 것이 좋다.',
    autoQueued: false,
  },
  RESERVED_STALE: {
    label: '미아 예약',
    severity: 'critical',
    scope: 'user',
    what: '재고는 깎였는데 임계 시간이 지나도록 발급이 확정되지 않고 Redis 예약(reserved) 상태에 남아있다.',
    why: '발급 Lua는 성공했으나 그 뒤 이벤트가 유실돼 아무도 확정해주지 않았다. 재고 누수의 개별 원인이기도 하다.',
    action: '재고를 점유한 채 아무에게도 안 간 상태다. 재처리로 확정하거나 예약을 풀어 재고를 되돌려야 한다.',
    autoQueued: true,
  },
  HISTORY_MISMATCH: {
    label: '이력 상태 불일치',
    severity: 'warning',
    scope: 'user',
    what: 'coupon_history의 최신 상태와 coupon_issue.status가 서로 다르다.',
    why: '상태 전이(발급 → 사용 → 만료) 처리 중 두 테이블 가운데 한쪽만 반영된 경우다.',
    action: '어느 쪽이 사실인지 확인해 맞춰야 한다. 쿠폰 사용 처리에 직접 영향을 준다.',
    autoQueued: false,
  },
  MISSING_HISTORY: {
    label: '이력 누락',
    severity: 'warning',
    scope: 'user',
    what: '상태가 바뀐 흔적(ISSUED가 아니거나 사용 시각이 있음)은 있는데 coupon_history에 해당 행이 없다.',
    why: '상태 전이는 일어났는데 이력 기록만 빠졌다.',
    action: '현재 상태 자체는 유효하므로 급하지는 않으나, 감사 추적이 끊긴 상태라 이력을 보정해두는 편이 좋다.',
    autoQueued: false,
  },
  EXPECTED_NOT_ISSUED: {
    label: '순번 안인데 못 받음',
    severity: 'info',
    scope: 'user',
    what: '대기열 도착 순번(queue_rank) 상위 N명 안에 들었는데 실제로는 발급받지 못했다.',
    why:
      '재고 소진 경계에서 같은 입장 배치에 함께 들어간 사람들끼리 /issue 요청이 Redis에 닿는 순서로 ' +
      '승부가 갈린 것이다. 네트워크 도착 순서가 대기 순번과 정확히 일치하지는 않기 때문에 생긴다.',
    action:
      '발급 개수와 중복 차단은 영향을 받지 않는다(총 발급 수는 그대로다). 순서를 더 엄격히 하려면 ' +
      '재고 소진 근처에서 입장 배치 크기를 좁혀야 하는데, 그만큼 처리 속도를 포기하는 트레이드오프가 있다.',
    autoQueued: false,
  },
  ISSUED_NOT_EXPECTED: {
    label: '순번 밖인데 받음',
    severity: 'info',
    scope: 'user',
    what: '대기열 도착 순번 상위 N명 밖인데 실제로는 발급받았다.',
    why: '위 "순번 안인데 못 받음"과 같은 원인의 반대편이다 — 밀려난 사람의 자리를 이 사람이 가져갔다.',
    action: '이 유형은 항상 "순번 안인데 못 받음"과 짝을 이룬다. 두 건수가 같다면 총 발급 수는 변하지 않은 것이다.',
    autoQueued: false,
  },
};

const FALLBACK = {
  label: '알 수 없는 유형',
  severity: 'warning',
  scope: 'user',
  what: '이 화면이 아직 설명을 갖고 있지 않은 불일치 유형이다.',
  why: '검증 배치에 새 검사가 추가됐는데 화면 쪽 설명이 따라가지 못했을 수 있다.',
  action: 'CSV를 내려받아 원본 행을 직접 확인해야 한다.',
  autoQueued: false,
};

/** "OVERSOLD(+3)" → { base: 'OVERSOLD', amount: 3 }, "DB_ONLY" → { base: 'DB_ONLY', amount: null } */
export function parseDiscrepancyType(raw) {
  const m = /^([A-Z_]+)\(\+(\d+)\)$/.exec(raw ?? '');
  if (m) return { base: m[1], amount: Number(m[2]) };
  return { base: raw ?? '', amount: null };
}

export function infoFor(base) {
  return DISCREPANCY_INFO[base] ?? FALLBACK;
}

/**
 * 불일치 행들을 유형별로 묶고, 전체를 놓고 한 줄짜리 판정을 내린다.
 * 화면이 "그래서 뭐가 문제인지"를 스스로 말할 수 있게 하는 것이 목적.
 */
export function analyzeMismatches(rows, report) {
  const groups = new Map();

  for (const row of rows) {
    const { base, amount } = parseDiscrepancyType(row.discrepancyType);
    if (!groups.has(base)) {
      groups.set(base, { base, info: infoFor(base), rows: [], userIds: [], amount: null });
    }
    const g = groups.get(base);
    g.rows.push(row);
    if (row.userId != null) g.userIds.push(row.userId);
    if (amount != null) g.amount = amount;
  }

  const list = [...groups.values()]
    .map((g) => ({
      ...g,
      // 정책 단위 요약 행은 행 수(1)가 아니라 괄호 안 수치가 실제 건수다.
      count: g.amount ?? g.rows.length,
      userIdMin: g.userIds.length ? Math.min(...g.userIds) : null,
      userIdMax: g.userIds.length ? Math.max(...g.userIds) : null,
    }))
    .sort(
      (a, b) => SEVERITY[b.info.severity].rank - SEVERITY[a.info.severity].rank || b.count - a.count,
    );

  const countOf = (base) => list.find((g) => g.base === base)?.count ?? 0;

  const criticalGroups = list.filter((g) => g.info.severity === 'critical');
  const warningGroups = list.filter((g) => g.info.severity === 'warning');

  // 선착순 역전은 항상 짝을 이룬다 — 짝이 맞으면 총 발급 수는 보존된 것이다.
  const expectedNotIssued = countOf('EXPECTED_NOT_ISSUED');
  const issuedNotExpected = countOf('ISSUED_NOT_EXPECTED');
  const fcfsPaired = expectedNotIssued > 0 && expectedNotIssued === issuedNotExpected;
  const fcfsOnly = list.length > 0 && list.every((g) => g.info.severity === 'info');

  const sum = (gs) => gs.reduce((acc, g) => acc + g.count, 0);

  let level = 'none';
  let headline = '불일치가 발견되지 않았어요';
  let detail = '개수·저장소·순서 검사를 모두 통과했습니다.';

  if (criticalGroups.length > 0) {
    level = 'critical';
    headline = `즉시 확인이 필요한 불일치 ${sum(criticalGroups)}건`;
    detail = criticalGroups.map((g) => `${g.info.label} ${g.count}건`).join(' · ');
  } else if (warningGroups.length > 0) {
    level = 'warning';
    headline = `저장소 간 반영이 어긋난 건 ${sum(warningGroups)}건`;
    detail =
      warningGroups.map((g) => `${g.info.label} ${g.count}건`).join(' · ') +
      ' — 발급 개수 자체는 어긋나지 않았습니다.';
  } else if (fcfsOnly) {
    level = 'info';
    headline = `발급 개수는 정상, 선착순 순서만 ${Math.max(expectedNotIssued, issuedNotExpected)}건 어긋났어요`;
    detail = fcfsPaired
      ? `밀려난 ${expectedNotIssued}명 자리에 뒤 순번 ${issuedNotExpected}명이 들어갔습니다. ` +
        '두 수가 정확히 같아 총 발급 수는 변하지 않았습니다.'
      : '밀려난 수와 새로 들어온 수가 달라 단순한 순서 교환이 아닙니다 — 개수 검사도 함께 확인하세요.';
  }

  return {
    level,
    headline,
    detail,
    groups: list,
    fcfsPaired,
    expectedNotIssued,
    issuedNotExpected,
    hasCritical: criticalGroups.length > 0,
    countIntact:
      report != null && report.totalIssued <= report.totalQuantity && (report.oversoldCount ?? 0) === 0,
  };
}
