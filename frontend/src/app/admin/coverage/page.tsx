'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { getSceneCoverage } from '@/lib/api/admin';
import React, { useState, useEffect } from 'react';

interface SceneItem {
  name: string;
  reportCount: number;
  avgRating: number;
  coverage: string;
}

const COVERAGE_CONFIG: Record<string, { bar: string; bg: string; label: string }> = {
  sufficient: { bar: 'bg-success', bg: 'bg-success-bg', label: '充足' },
  moderate: { bar: 'bg-warning', bg: 'bg-warning-bg', label: '一般' },
  empty: { bar: 'bg-muted-foreground-2/30', bg: 'bg-surface', label: '空白' },
};

export default function CoveragePage() {
  const [scenes, setScenes] = useState<SceneItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getSceneCoverage()
      .then(d => { if (d.scenes) setScenes(d.scenes); })
      .catch(() => {}).finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner />;

  const sufficient = scenes.filter(s => s.coverage === 'sufficient').length;
  const moderate = scenes.filter(s => s.coverage === 'moderate').length;
  const empty = scenes.filter(s => s.coverage === 'empty').length;
  const maxCount = scenes.length > 0 ? Math.max(...scenes.map(s => s.reportCount), 1) : 1;

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <h1 className="text-2xl font-bold text-foreground mb-1">场景地图</h1>
        <p className="text-sm text-muted-foreground mb-6">
          {scenes.length} 个场景 · {sufficient} 充足 · {moderate} 一般 · {empty} 空白
        </p>

        {/* 汇总卡片 */}
        <div className="grid grid-cols-3 gap-4 mb-6">
          <div className="rounded-xl bg-success-bg border border-success/20 p-4 text-center">
            <p className="text-2xl font-bold text-success">{sufficient}</p>
            <p className="text-xs text-success mt-1">已覆盖（≥3条）</p>
          </div>
          <div className="rounded-xl bg-warning-bg border border-warning/20 p-4 text-center">
            <p className="text-2xl font-bold text-warning-text">{moderate}</p>
            <p className="text-xs text-warning-text mt-1">待充实（1-2条）</p>
          </div>
          <div className="rounded-xl bg-surface-2 border border-border p-4 text-center">
            <p className="text-2xl font-bold text-muted-foreground">{empty}</p>
            <p className="text-xs text-muted-foreground mt-1">空白（0条）</p>
          </div>
        </div>

        {/* 场景热力图 */}
        <div className="rounded-2xl bg-surface-2 border border-border p-6 shadow-sm">
          {scenes.length > 0 ? (
            <div className="space-y-3">
              {scenes.map(scene => {
                const cfg = COVERAGE_CONFIG[scene.coverage] || COVERAGE_CONFIG.empty;
                const pct = Math.max((scene.reportCount / maxCount) * 100, scene.reportCount > 0 ? 8 : 0);
                return (
                  <div key={scene.name} className="flex items-center gap-3">
                    <span className="w-24 text-sm text-muted-foreground flex-shrink-0 truncate">{scene.name}</span>
                    <div className="flex-1 h-6 bg-surface rounded-full overflow-hidden">
                      <div className={`h-full rounded-full ${cfg.bar}`}
                        style={{ width: `${pct}%`, minWidth: scene.reportCount > 0 ? '8px' : '0' }} />
                    </div>
                    <span className="w-16 text-sm text-right flex-shrink-0">
                      <span className="font-medium text-foreground">{scene.reportCount}</span>
                      <span className="text-muted-foreground-2 text-xs ml-1">条</span>
                    </span>
                    <span className={`text-xs rounded-full px-2 py-0.5 flex-shrink-0 ${cfg.bg}`}>
                      {cfg.label}
                    </span>
                  </div>
                );
              })}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground-2 text-center py-8">暂无场景数据</p>
          )}
        </div>
      </div>
    </div>
  );
}
