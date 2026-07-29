'use client';

interface GapItem {
  id: string;
  query: string;
  sceneTag?: string;
  count: number;
  lastSeen?: string;
  status: string;
  note?: string;
}

interface Props {
  gaps: GapItem[];
  onGapClick?: (id: string) => void;
  onResolve?: (id: string) => void;
  onIgnore?: (id: string) => void;
}

/** 知识缺口列表 —— 用户问了但分身在回答不了的，按出现次数降序 */
export function KnowledgeGapPanel({ gaps, onGapClick, onResolve, onIgnore }: Props) {
  if (!gaps || gaps.length === 0) {
    return (
      <div className="rounded-[12px] bg-surface-2 p-5 shadow-sm">
        <h3 className="mb-3 text-sm font-semibold">🔴 待处理知识缺口</h3>
        <div className="flex items-center justify-center h-[180px]">
          <p className="text-sm text-muted-foreground">暂无待处理的知识缺口 🎉</p>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-[12px] bg-surface-2 p-5 shadow-sm">
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
                {g.note && g.note.startsWith('type=') && (
                  <MatchBadge note={g.note} />
                )}
              </p>
            </div>
            <div className="flex items-center gap-1.5 ml-2 flex-shrink-0">
              <button onClick={() => onGapClick?.(g.id)}
                className="text-xs bg-primary/10 text-primary rounded-lg px-3 py-1.5 hover:bg-primary/20 transition-colors">
                补充颗粒
              </button>
              {onResolve && (
                <button onClick={(e) => { e.stopPropagation(); onResolve(g.id); }}
                  className="text-xs text-green-600 hover:bg-green-50 rounded-lg px-2.5 py-1.5 transition-colors font-medium"
                  title="标记已解决">
                  已解决
                </button>
              )}
              {onIgnore && (
                <button onClick={(e) => { e.stopPropagation(); onIgnore(g.id); }}
                  className="text-xs text-muted-foreground hover:bg-surface rounded-lg px-2.5 py-1.5 transition-colors"
                  title="忽略此缺口">
                  忽略
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/** 匹配质量标记 — 解析 note 中的结构化元数据并渲染为颜色标签 */
function MatchBadge({ note }: { note: string }) {
  const parsed: Record<string, string> = {};
  note.split(/\s+/).forEach(part => {
    const eq = part.indexOf('=');
    if (eq > 0) parsed[part.substring(0, eq)] = part.substring(eq + 1);
  });

  const sim = parseFloat(parsed.bestSim || '0');
  const threshold = parseFloat(parsed.threshold || '0.30');

  // 颜色：相似度越低越红，越接近阈值越黄
  const ratio = Math.min(sim / threshold, 1);
  const color = ratio < 0.5 ? '#DC2626' : '#D97706';
  const bg = ratio < 0.5 ? '#FEF2F2' : '#FFFBEB';

  return (
    <span className="inline-flex items-center gap-1 ml-1.5 text-[11px] px-1.5 py-0.5 rounded-full font-medium"
      style={{ background: bg, color }}>
      {(parsed.type === 'all_low_similarity') ? '🟡 RAG低匹配' : ''}
      {' · '}最佳 {Math.round(sim * 100)}%
    </span>
  );
}
