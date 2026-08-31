// 발급 현황 페이지의 실시간 발급 추이 그래프. 별도 차트 라이브러리 없이 SVG로 직접 그린다
// (프로젝트 규모상 라인 하나짜리 그래프에 의존성을 추가할 필요는 없다고 판단).
//
// 색은 index.css의 @theme 토큰(var(--color-*))만 참조한다 — dev 브랜치 대시보드의
// AreaChart 룩(민트 라인 + 옅은 그라데이션 면 + 상한 점선)을 라이브러리 없이 옮긴 것.
//
// 폭은 ResizeObserver로 컨테이너를 실측해서 그린다. 예전에는 고정 viewBox +
// preserveAspectRatio="none"으로 늘려 채웠는데, 그러면 좌표뿐 아니라 축 라벨 텍스트와
// 원형 마커까지 가로로 늘어난다. 좁은 컬럼에 있을 땐 티가 안 났지만 전체 폭 카드로
// 옮기면서 왜곡이 그대로 드러나 실측 방식으로 바꿨다.
import { useEffect, useRef, useState } from 'react';

const HEIGHT = 160;
const PAD_LEFT = 38;
const PAD_RIGHT = 12;
const PAD_TOP = 12;
const PAD_BOTTOM = 22;
const MIN_WIDTH = 240;

// "yyyy-MM-dd HH:mm:ss" → "HH:mm:ss"
function bucketLabel(bucket) {
  return bucket.slice(11, 19);
}

function useMeasuredWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => {
      setWidth(Math.round(entry.contentRect.width));
    });
    ro.observe(el);
    setWidth(Math.round(el.getBoundingClientRect().width));
    return () => ro.disconnect();
  }, []);

  return [ref, width];
}

export default function IssuanceChart({ points, capacity }) {
  const [ref, measured] = useMeasuredWidth();

  // 폭을 재기 전(첫 페인트)에도 높이를 차지해야 레이아웃이 튀지 않는다.
  const width = Math.max(MIN_WIDTH, measured || MIN_WIDTH);
  const hasPoints = points && points.length > 0;

  const peak = hasPoints ? Math.max(1, ...points.map((p) => p.count)) : 1;
  // capacity(초당 상한 등)가 주어지면 그 선까지 눈금에 넣어 "한계 대비 지금"이 보이게 한다.
  const max = capacity ? Math.max(peak, capacity) : peak;
  const innerW = width - PAD_LEFT - PAD_RIGHT;
  const innerH = HEIGHT - PAD_TOP - PAD_BOTTOM;
  const baseY = PAD_TOP + innerH;
  const stepX = hasPoints && points.length > 1 ? innerW / (points.length - 1) : 0;

  const coords = hasPoints
    ? points.map((p, i) => ({
        x: PAD_LEFT + i * stepX,
        y: PAD_TOP + innerH - (p.count / max) * innerH,
        ...p,
      }))
    : [];

  const linePath = coords
    .map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x.toFixed(1)} ${c.y.toFixed(1)}`)
    .join(' ');
  const areaPath = coords.length
    ? `${linePath} L ${coords[coords.length - 1].x.toFixed(1)} ${baseY.toFixed(1)} ` +
      `L ${coords[0].x.toFixed(1)} ${baseY.toFixed(1)} Z`
    : '';

  // x축 라벨은 너무 빽빽해지지 않게 폭에 맞춰 개수를 정한다(라벨 하나에 약 70px).
  const labelSlots = Math.max(2, Math.floor(innerW / 70));
  const labelEvery = Math.max(1, Math.ceil(coords.length / labelSlots));
  const last = coords[coords.length - 1];
  const capY = capacity && capacity <= max ? PAD_TOP + innerH - (capacity / max) * innerH : null;

  return (
    <div ref={ref} className="w-full">
      <svg width={width} height={HEIGHT} viewBox={`0 0 ${width} ${HEIGHT}`} className="block">
        <defs>
          <linearGradient id="issuance-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--color-mint)" stopOpacity="0.18" />
            <stop offset="100%" stopColor="var(--color-mint)" stopOpacity="0" />
          </linearGradient>
        </defs>

        {/* y축 그리드(0 / 중간 / 최대) — 가로선만, 세로선 없음 */}
        {[0, 0.5, 1].map((r) => {
          const y = PAD_TOP + innerH - r * innerH;
          return (
            <g key={r}>
              <line x1={PAD_LEFT} y1={y} x2={width - PAD_RIGHT} y2={y} stroke="var(--color-line)" strokeWidth="1" />
              <text
                x={PAD_LEFT - 6}
                y={y + 3}
                textAnchor="end"
                fontSize="10"
                fill="var(--color-sub)"
                style={{ fontVariantNumeric: 'tabular-nums' }}
              >
                {Math.round(max * r)}
              </text>
            </g>
          );
        })}

        {/* 상한선(있을 때만) — dev 대시보드의 ReferenceLine과 같은 역할 */}
        {capY != null && (
          <g>
            <line
              x1={PAD_LEFT}
              y1={capY}
              x2={width - PAD_RIGHT}
              y2={capY}
              stroke="var(--color-danger)"
              strokeWidth="1"
              strokeDasharray="4 4"
            />
            <text
              x={width - PAD_RIGHT}
              y={capY - 4}
              textAnchor="end"
              fontSize="10"
              fontWeight="700"
              fill="var(--color-danger)"
            >
              상한 {capacity}
            </text>
          </g>
        )}

        {hasPoints && (
          <>
            <path d={areaPath} fill="url(#issuance-fill)" />
            <path
              d={linePath}
              fill="none"
              stroke="var(--color-mint)"
              strokeWidth="2"
              strokeLinejoin="round"
              strokeLinecap="round"
            />
            {/* 마지막 지점만 강조 — "지금 여기" */}
            <circle cx={last.x} cy={last.y} r="5" fill="var(--color-mint)" opacity="0.18" />
            <circle cx={last.x} cy={last.y} r="2.8" fill="var(--color-mint)" stroke="#fff" strokeWidth="1.4" />

            {coords.map((c, i) =>
              i % labelEvery === 0 ? (
                <text
                  key={c.bucket}
                  x={c.x}
                  y={HEIGHT - 4}
                  textAnchor="middle"
                  fontSize="10"
                  fill="var(--color-sub)"
                  style={{ fontVariantNumeric: 'tabular-nums' }}
                >
                  {bucketLabel(c.bucket)}
                </text>
              ) : null,
            )}
          </>
        )}

        {!hasPoints && (
          <text x={width / 2} y={HEIGHT / 2} textAnchor="middle" fontSize="13" fill="var(--color-sub)">
            표시할 데이터가 없어요
          </text>
        )}
      </svg>
    </div>
  );
}
