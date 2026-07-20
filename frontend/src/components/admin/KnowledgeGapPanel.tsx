'use client';

interface GapItem {
  id: string;
  query: string;
  sceneTag?: string;
  count: number;
  lastSeen?: string;
  status: string;
}

interface Props {
  gaps: GapItem[];
  onGapClick?: (id: string) => void;
}

/** 知识缺口列表 —— 用户问了但分身在回答不了的，按出现次数降序 */
export function KnowledgeGapPanel({ gaps, onGapClick }: Props) {
  if (!gaps || gaps.length === 0) {
    return (
      <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
        <h3 className="mb-3 text-sm font-semibold">🔴 待处理知识缺口</h3>
        <p className="text-sm text-muted-foreground py-4 text-center">暂无待处理的知识缺口 🎉</p>
      </div>
    );
  }

  return (
    <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold">🔴 待处理知识缺口（{gaps.length}个）</h3>
      </div>
      <div className="space-y-2">
        {gaps.map(g => (
          <div key={g.id}
            className="flex items-center justify-between rounded-lg border border-border/50 p-3 hover:bg-primary-light/30 transition-colors">
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium truncate">&ldquo;{g.query}&rdquo;</p>
              <p className="text-xs text-muted-foreground mt-0.5">
                出现 {g.count} 次{g.sceneTag ? ` · 场景: ${g.sceneTag}` : ''}{g.lastSeen ? ` · 最近: ${g.lastSeen.substring(0, 10)}` : ''}
              </p>
            </div>
            <button onClick={() => onGapClick?.(g.id)}
              className="ml-3 flex-shrink-0 text-xs bg-primary/10 text-primary rounded-lg px-3 py-1.5 hover:bg-primary/20 transition-colors">
              补充颗粒
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
