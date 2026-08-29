// 발급 현황 페이지의 실시간 발급 추이 그래프. 별도 차트 라이브러리 없이 SVG로 직접 그린다
// (프로젝트 규모상 라인 하나짜리 그래프에 의존성을 추가할 필요는 없다고 판단).
const WIDTH = 560;
const HEIGHT = 160;
const PAD_LEFT = 34;
const PAD_RIGHT = 10;
const PAD_TOP = 12;
const PAD_BOTTOM = 22;

// "yyyy-MM-dd HH:mm:ss" → "HH:mm:ss"
function bucketLabel(bucket) {
  return bucket.slice(11, 19);
}

export default function IssuanceChart({ points }) {
  if (!points || points.length === 0) {
    return (
      <div className="h-40 flex items-center justify-center text-xs text-zinc-400">
        표시할 데이터가 없어요
      </div>
    );
  }

  const max = Math.max(1, ...points.map((p) => p.count));
  const innerW = WIDTH - PAD_LEFT - PAD_RIGHT;
  const innerH = HEIGHT - PAD_TOP - PAD_BOTTOM;
  const stepX = points.length > 1 ? innerW / (points.length - 1) : 0;

  const coords = points.map((p, i) => ({
    x: PAD_LEFT + i * stepX,
    y: PAD_TOP + innerH - (p.count / max) * innerH,
    ...p,
  }));

  const linePath = coords.map((c, i) => `${i === 0 ? 'M' : 'L'} ${c.x.toFixed(1)} ${c.y.toFixed(1)}`).join(' ');
  const areaPath = `${linePath} L ${coords[coords.length - 1].x.toFixed(1)} ${(PAD_TOP + innerH).toFixed(1)} `
    + `L ${coords[0].x.toFixed(1)} ${(PAD_TOP + innerH).toFixed(1)} Z`;

  // x축 라벨은 너무 빽빽해지지 않게 최대 6개까지만 골라서 보여준다
  const labelEvery = Math.max(1, Math.ceil(coords.length / 6));

  return (
    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="w-full h-40" preserveAspectRatio="none">
      {/* y축 그리드(0 / 중간 / 최대) */}
      {[0, 0.5, 1].map((r) => {
        const y = PAD_TOP + innerH - r * innerH;
        return (
          <g key={r}>
            <line x1={PAD_LEFT} y1={y} x2={WIDTH - PAD_RIGHT} y2={y} stroke="#e4e4e7" strokeWidth="1" />
            <text x={PAD_LEFT - 6} y={y + 3} textAnchor="end" fontSize="9" fill="#a1a1aa">
              {Math.round(max * r)}
            </text>
          </g>
        );
      })}

      <path d={areaPath} fill="#e4e4e7" opacity="0.5" />
      <path d={linePath} fill="none" stroke="#18181b" strokeWidth="1.6" />

      {coords.map((c, i) => (
        <circle key={c.bucket} cx={c.x} cy={c.y} r={i === coords.length - 1 ? 2.6 : 1.6} fill="#18181b" />
      ))}

      {coords.map((c, i) =>
        i % labelEvery === 0 ? (
          <text key={c.bucket} x={c.x} y={HEIGHT - 4} textAnchor="middle" fontSize="9" fill="#a1a1aa">
            {bucketLabel(c.bucket)}
          </text>
        ) : null,
      )}
    </svg>
  );
}
