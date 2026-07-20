'use client';

interface GrainItem {
  id: string;
  description: string;
  helpful: number;
  unhelpful: number;
  qualityScore?: number;
}

interface Props {
  grains: GrainItem[];
  type: 'best' | 'worst';
  onGrainClick?: (id: string) => void;
}

/** 👍/👎 颗粒排行榜 —— 管理员快速定位高质量/低质量颗粒 */
export function GrainRankTable({ grains, type, onGrainClick }: Props) {
  if (!grains || grains.length === 0) return <EmptyPlaceholder label={type === 'best' ? '暂无好评颗粒' : '暂无差评颗粒'} />;

  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
      <h3 className="mb-3 text-sm font-semibold">
        {type === 'best' ? '⭐ 最佳颗粒（👍最多）' : '⚠️ 待优化颗粒（👎最多）'}
      </h3>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-muted-foreground">
            <th className="text-left py-2 font-medium">颗粒描述</th>
            <th className="text-right py-2 font-medium w-14">👍</th>
            <th className="text-right py-2 font-medium w-14">👎</th>
          </tr>
        </thead>
        <tbody>
          {grains.slice(0, 5).map((g, i) => (
            <tr key={g.id}
              className="border-b border-border/50 hover:bg-primary-light/50 cursor-pointer transition-colors"
              onClick={() => onGrainClick?.(g.id)}>
              <td className="py-2.5 truncate max-w-[200px]">
                <span className="text-muted-foreground mr-2">{i + 1}.</span>
                {g.description}
              </td>
              <td className="text-right text-green-600 font-medium">{g.helpful}</td>
              <td className="text-right text-red-400">{g.unhelpful}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EmptyPlaceholder({ label }: { label: string }) {
  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm flex items-center justify-center h-[120px]">
      <p className="text-sm text-muted-foreground">{label}</p>
    </div>
  );
}
