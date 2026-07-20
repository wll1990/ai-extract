'use client';

import React, { useState, useCallback } from 'react';
import type { GrainGroup, GrainInfo } from '@/lib/api/expert';

/** ReviewGrainsModal Props */
export interface ReviewGrainsModalProps {
  open: boolean;
  expertName: string;
  grainGroups: GrainGroup[];
  onClose: () => void;
  onConfirm: (approvedIds: string[], rejectedIds: string[]) => void;
  onEditGrain: (grainId: string) => void;
  onDeleteGrain: (grainId: string) => void;
}

const CATEGORY_LABELS: Record<string, string> = {
  judgment_intuition: '判断直觉',
  mental_model: '心智模型',
  failure_lesson: '失败经验',
  validation_method: '验证方法',
  metaphor_framework: '隐喻框架',
  rhythm_sense: '节奏感知',
  typing_method: '分类方法',
  expert_profile: '萃取师档案',
};

const PRIORITY_CONFIG: Record<number, { label: string; color: string; bg: string }> = {
  5: { label: '高', color: '#D97706', bg: '#FEF3C7' },
  4: { label: '中高', color: '#D97706', bg: '#FEF3C7' },
  3: { label: '中', color: '#4E5969', bg: '#F2F3F5' },
  2: { label: '中低', color: '#4E5969', bg: '#F2F3F5' },
  1: { label: '低', color: '#86909C', bg: '#F2F3F5' },
};

/**
 * 审核萃取法则弹窗
 */
export const ReviewGrainsModal: React.FC<ReviewGrainsModalProps> = ({
  open, expertName, grainGroups, onClose, onConfirm, onEditGrain, onDeleteGrain,
}) => {
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const toggleSelect = useCallback((id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }, []);

  const selectAll = useCallback(() => {
    const all = grainGroups.flatMap(g => g.grains.map(gr => gr.id));
    setSelectedIds(new Set(all));
  }, [grainGroups]);

  const deselectAll = useCallback(() => setSelectedIds(new Set()), []);

  const handleBatchApprove = useCallback(() => {
    onConfirm(Array.from(selectedIds), []);
    onClose();
  }, [selectedIds, onConfirm, onClose]);

  const totalCount = grainGroups.reduce((sum, g) => sum + g.grains.length, 0);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-[800px] max-h-[80vh] overflow-y-auto rounded-2xl bg-surface-2 shadow-xl">
        <div className="sticky top-0 z-10 rounded-t-2xl bg-surface-2 px-6 pt-6 pb-3 border-b border-border">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-xl font-bold text-foreground">审核萃取法则 · {expertName}</h2>
            <span className="text-sm text-muted-foreground">共{totalCount}条法则 · {grainGroups.length}个类别</span>
          </div>
          <div className="flex gap-2">
            <button onClick={selectAll} className="rounded-lg bg-primary-light px-3 py-1 text-xs text-primary">全选</button>
            <button onClick={deselectAll} className="rounded-lg bg-primary-light px-3 py-1 text-xs text-muted-foreground">取消全选</button>
            <button onClick={handleBatchApprove} disabled={selectedIds.size === 0}
              className="rounded-lg bg-success px-3 py-1 text-xs text-white disabled:opacity-40">批量通过</button>
          </div>
        </div>

        <div className="p-6 space-y-6">
          {grainGroups.map(group => (
            <div key={group.category}>
              <h3 className="rounded bg-surface px-3 py-2 text-sm font-semibold text-foreground">
                {CATEGORY_LABELS[group.category] || group.category}
              </h3>
              <div className="mt-2 space-y-2">
                {group.grains.map(grain => {
                  const priCfg = PRIORITY_CONFIG[grain.priority] || PRIORITY_CONFIG[1];
                  return (
                    <div key={grain.id} className={`flex items-start gap-3 rounded-lg border p-3 transition-colors ${selectedIds.has(grain.id) ? 'border-success bg-success-bg' : 'border-border'}`}>
                      <input type="checkbox" checked={selectedIds.has(grain.id)} onChange={() => toggleSelect(grain.id)}
                        className="mt-1 h-4 w-4 accent-navy" />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm text-foreground line-clamp-2">{grain.knowledgeContent}</p>
                        {grain.applicationRule && <p className="mt-1 text-xs text-muted-foreground">{grain.applicationRule}</p>}
                        <div className="mt-2 flex flex-wrap gap-1">
                          <span className="rounded-full px-2 py-0.5 text-[10px]" style={{ background: priCfg.bg, color: priCfg.color }}>
                            优先级{priCfg.label}
                          </span>
                          <span className={`rounded-full px-2 py-0.5 text-[10px] ${grain.consensusType === 'consensus' ? 'bg-success-bg text-success' : 'bg-warning-bg text-warning'}`}>
                            {grain.consensusType === 'consensus' ? '多材料验证' : '单一来源'}
                          </span>
                        </div>
                      </div>
                      <div className="flex flex-shrink-0 gap-1">
                        <button onClick={() => onEditGrain(grain.id)} className="rounded p-1 text-muted-foreground-2 hover:bg-primary-light" title="编辑">✏️</button>
                        <button onClick={() => onDeleteGrain(grain.id)} className="rounded p-1 text-danger hover:bg-danger-bg" title="删除">🗑️</button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>

        <div className="sticky bottom-0 rounded-b-2xl border-t border-border bg-surface-2 px-6 py-4 flex justify-end gap-3">
          <button onClick={onClose} className="rounded-lg px-4 py-2 text-sm text-muted-foreground">返回修改</button>
          <button onClick={handleBatchApprove} disabled={selectedIds.size === 0}
            className="rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white disabled:opacity-40">
            确认入库，生成Skill
          </button>
        </div>
      </div>
    </div>
  );
};
