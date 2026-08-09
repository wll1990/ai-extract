'use client';

import React from 'react';
import type { AuditDashboard } from '@/lib/api/audit';

interface GrainInfo {
  id?: string;
  sceneDescription?: string;
  qualityScore?: number;
  standardScript?: string;
  expertThought?: string;
  commonMistakes?: string;
}

interface Props {
  data: AuditDashboard;
  sceneTag: string;
  setSceneTag: (tag: string) => void;
  grainIdx: number;
  setGrainIdx: (idx: number) => void;
  onPracticeGrain: (grain: GrainInfo) => void;
}

function extractKeyQuote(text: string) {
  if (!text) return '';
  const m = text.match(/[「「](.+?)[」」]/);
  if (m) return m[1];
  const sentences = text.split(/[。；]/);
  return sentences[0]?.trim()?.substring(0, 80) || text.substring(0, 80);
}

function extractFirstSentence(text: string, max: number) {
  if (!text) return '';
  const s = text.split(/[。；]/)[0]?.trim() || '';
  return s.length > max ? s.substring(0, max) + '...' : s;
}

export default function SceneStep({ data, sceneTag, setSceneTag, grainIdx, setGrainIdx, onPracticeGrain }: Props) {
  const scenarioGrains = data.scenarioGrains || {};
  const tags = Object.keys(scenarioGrains).sort((a, b) => (scenarioGrains[b]?.length || 0) - (scenarioGrains[a]?.length || 0));
  const activeTag = sceneTag || tags[0] || '';
  const grains = scenarioGrains[activeTag] || [];
  const safeIdx = Math.min(grainIdx, grains.length - 1);
  const grain = grains[safeIdx] || null;

  return (
    <div>
      <div className="mb-4 p-4 bg-amber-50 rounded-lg text-sm text-amber-700">
        🎯 按业务场景展示「使用技能 vs 常见错误」对比，每条颗粒含质量评分
      </div>
      {tags.length === 0 ? (
        <p className="text-sm text-muted-foreground-2 text-center py-8">暂无场景颗粒数据</p>
      ) : (
        <div className="grid grid-cols-4 gap-6">
          <div className="col-span-1 border-r pr-4">
            <h3 className="text-xs font-semibold text-muted-foreground-2 uppercase mb-3">场景标签</h3>
            <div className="space-y-1 max-h-96 overflow-y-auto">
              {tags.map((tag: string) => (
                <button key={tag} onClick={() => { setSceneTag(tag); setGrainIdx(0); }}
                  className={`w-full text-left px-3 py-2 rounded-lg text-sm transition flex justify-between items-center ${
                    activeTag === tag ? 'bg-primary text-white' : 'hover:bg-primary-light text-muted-foreground'
                  }`}>
                  <span className="truncate">{tag}</span>
                  <span className={`text-xs ml-1 shrink-0 ${activeTag === tag ? 'text-blue-200' : 'text-muted-foreground-2'}`}>{scenarioGrains[tag]?.length || 0}</span>
                </button>
              ))}
            </div>
          </div>
          <div className="col-span-3">
            {grain && (
              <div className="space-y-4">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <p className="text-sm text-muted-foreground mb-1">📍 {activeTag}</p>
                    <p className="text-sm text-foreground">{grain.sceneDescription || '-'}</p>
                  </div>
                  {grain.qualityScore != null && (
                    <div className={`ml-4 px-3 py-1.5 rounded-full text-xs font-medium shrink-0 ${
                      grain.qualityScore >= 4 ? 'bg-green-100 text-green-700' :
                      grain.qualityScore >= 3 ? 'bg-amber-100 text-amber-700' : 'bg-red-100 text-red-700'
                    }`}>
                      ⭐ {grain.qualityScore?.toFixed(1)}
                    </div>
                  )}
                  {grain.id && (
                    <a href={`/admin/grains/${grain.id}/edit`} target="_blank" rel="noreferrer"
                      className="ml-2 px-3 py-1.5 border rounded-lg text-xs hover:bg-surface shrink-0 no-underline">
                      ✏️ 编辑
                    </a>
                  )}
                  {grain.id && (
                    <button onClick={() => { if (confirm('确定废弃这条颗粒？')) fetch(`/api/v1/admin/grains/${grain.id}/deprecate`, { method: 'POST' }).then(() => location.reload()); }}
                      className="ml-1 px-3 py-1.5 border border-red-200 rounded-lg text-xs text-red-600 hover:bg-red-50 shrink-0">
                      废弃
                    </button>
                  )}
                  <button onClick={() => onPracticeGrain(grain)}
                    className="ml-2 px-3 py-1.5 bg-primary text-white rounded-lg text-xs hover:bg-primary-hover shrink-0">
                    练这个场景 →
                  </button>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="border border-green-200 rounded-xl bg-gradient-to-b from-green-50 to-white">
                    <div className="flex items-center gap-2 px-4 pt-4 pb-2">
                      <span className="text-lg">✅</span>
                      <h4 className="text-sm font-semibold text-green-800">使用销冠技能</h4>
                    </div>
                    <blockquote className="mx-4 mb-2 px-3 py-2 bg-surface-2 border-l-2 border-green-400 rounded-r text-sm font-medium text-gray-800">
                      "{extractKeyQuote(grain.standardScript || '')}"
                    </blockquote>
                    <div className="px-4 pb-2">
                      <p className="text-xs text-green-700">💡 {extractFirstSentence(grain.expertThought || '', 80)}</p>
                    </div>
                    <details className="px-4 pb-3">
                      <summary className="text-xs text-muted-foreground-2 cursor-pointer hover:text-muted-foreground">展开完整话术</summary>
                      <p className="mt-1 text-xs text-muted-foreground leading-relaxed">{grain.standardScript}</p>
                    </details>
                  </div>
                  <div className="border border-red-200 rounded-xl bg-gradient-to-b from-red-50 to-white">
                    <div className="flex items-center gap-2 px-4 pt-4 pb-2">
                      <span className="text-lg">❌</span>
                      <h4 className="text-sm font-semibold text-red-700">常见的错误做法</h4>
                    </div>
                    <p className="mx-4 px-3 py-2 bg-red-50 rounded text-sm text-red-700 font-medium">
                      {extractFirstSentence(grain.commonMistakes || '未记录', 120)}
                    </p>
                    {(grain.commonMistakes || '').length > 120 && (
                      <details className="px-4 pb-3">
                        <summary className="text-xs text-muted-foreground-2 cursor-pointer hover:text-muted-foreground">展开详情</summary>
                        <p className="mt-1 text-xs text-muted-foreground leading-relaxed">{grain.commonMistakes}</p>
                      </details>
                    )}
                  </div>
                </div>
                {grains.length > 1 && (
                  <div className="flex items-center justify-between pt-2 border-t">
                    <button onClick={() => setGrainIdx(Math.max(0, grainIdx - 1))} disabled={grainIdx === 0}
                      className="px-3 py-1 text-xs border rounded disabled:opacity-30 hover:bg-surface">◀ 上一场景</button>
                    <span className="text-xs text-muted-foreground-2">{grainIdx + 1} / {grains.length}</span>
                    <button onClick={() => setGrainIdx(Math.min(grains.length - 1, grainIdx + 1))} disabled={grainIdx >= grains.length - 1}
                      className="px-3 py-1 text-xs border rounded disabled:opacity-30 hover:bg-surface">下一场景 ▶</button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
