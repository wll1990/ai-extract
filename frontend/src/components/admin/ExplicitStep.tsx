'use client';

import React from 'react';
import type { AuditDashboard } from '@/lib/api/audit';

interface Props {
  data: AuditDashboard;
}

export default function ExplicitStep({ data }: Props) {
  const materials = data.materials || [];
  const parsed = materials.filter(m => m.status === 'extracted' || m.analysisNotes?.includes('解析完成'));
  const needManual = materials.filter(m => m.analysisNotes?.includes('需人工'));
  const typeCount = materials.reduce((acc: Record<string, number>, m) => {
    const ext = m.fileName?.split('.').pop()?.toLowerCase() || 'unknown';
    acc[ext] = (acc[ext] || 0) + 1; return acc;
  }, {});

  return (
    <div>
      {/* 素材处理 Pipeline */}
      {(() => {
        const totalGrains = data.skillsSummary.totalGrains;
        const verified = data.extractionResult?.verifiedCount || 0;
        const hasPatterns = !!data.extractionResult?.patterns;
        const grainReady = totalGrains > 0 && data.skillsSummary.activeGrains > 0;
        const nodes = [
          { label: '解析', done: parsed.length > 0, sub: `${materials.length}份素材` },
          { label: '清洗', done: totalGrains > 0, sub: totalGrains > 0 ? `${totalGrains}条颗粒` : '等待中' },
          { label: '提取', done: verified > 0, sub: verified > 0 ? `通过${verified}条` : '等待中' },
          { label: '验证', done: hasPatterns, sub: hasPatterns ? '模式已发现' : '等待中' },
          { label: '入库', done: grainReady, sub: grainReady ? `${data.skillsSummary.activeGrains}条可用` : '等待中' },
        ];
        const doneCount = nodes.filter(n => n.done).length;
        return (
          <div className="mb-6 p-4 bg-gradient-to-r from-blue-50 to-green-50 rounded-xl border border-blue-100">
            <div className="flex items-center gap-2">
              {nodes.map((n, i) => (
                <React.Fragment key={n.label}>
                  <div className="flex items-center gap-1.5">
                    <span className={`w-5 h-5 rounded-full flex items-center justify-center text-xs ${n.done ? 'bg-green-400 text-white' : 'bg-gray-200 text-muted-foreground-2'}`}>
                      {n.done ? '✓' : '○'}
                    </span>
                    <span className={`text-xs font-medium ${n.done ? 'text-green-700' : 'text-muted-foreground-2'}`}>{n.label}</span>
                  </div>
                  {i < nodes.length - 1 && <span className={`flex-1 h-px min-w-5 ${n.done && nodes[i+1]?.done ? 'bg-green-300' : n.done ? 'bg-gradient-to-r from-green-300 to-gray-200' : 'bg-gray-200'}`} />}
                </React.Fragment>
              ))}
              <span className="text-xs text-muted-foreground-2 ml-2">{doneCount}/{nodes.length}</span>
            </div>
            <div className="flex items-center gap-2 mt-1.5">
              {nodes.map((n, i) => (
                <React.Fragment key={n.label}>
                  <span className="text-[10px] text-muted-foreground-2 w-[52px] text-center">{n.sub}</span>
                  {i < nodes.length - 1 && <span className="flex-1 min-w-5" />}
                </React.Fragment>
              ))}
            </div>
          </div>
        );
      })()}

      <div className="grid grid-cols-2 gap-4 mb-6">
        <div className="bg-surface-2 border rounded-xl p-4">
          <h3 className="text-sm font-semibold mb-3">素材概况 · {materials.length}份</h3>
          <div className="flex flex-wrap gap-1.5">
            {Object.entries(typeCount).map(([ext, count]) => (
              <span key={ext} className="px-2 py-0.5 bg-primary-light rounded text-xs text-muted-foreground">
                {ext.toUpperCase()} ×{count as number}
              </span>
            ))}
          </div>
        </div>
        <div className="bg-surface-2 border rounded-xl p-4">
          <h3 className="text-sm font-semibold mb-3">解析概况</h3>
          <div className="space-y-2 text-sm">
            <div className="flex justify-between"><span>📝 解析成功</span><b className="text-green-600">{parsed.length}份</b></div>
            <div className="flex justify-between"><span>🖼 需人工处理</span><b className="text-amber-600">{needManual.length}份</b></div>
            {materials.length > 0 && <div className="flex justify-between"><span>⏳ 待处理</span><b className="text-muted-foreground-2">{materials.length - parsed.length - needManual.length}份</b></div>}
          </div>
        </div>
      </div>

      <div className="bg-surface-2 border rounded-xl p-4">
        <h3 className="text-sm font-semibold mb-3">素材清单</h3>
        {materials.length === 0 ? (
          <p className="text-sm text-muted-foreground-2 text-center py-8">暂无素材，请上传销冠的对话记录、文档等材料</p>
        ) : (
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-muted-foreground-2 border-b">
              <th className="pb-2 font-medium">文件名</th>
              <th className="pb-2 font-medium w-16 text-center">格式</th>
              <th className="pb-2 font-medium w-20 text-center">大小</th>
              <th className="pb-2 font-medium w-20 text-center">状态</th>
              <th className="pb-2 font-medium w-20 text-right">解析字数</th>
            </tr></thead>
            <tbody>
              {materials.map(m => {
                const ext = m.fileName?.split('.').pop()?.toLowerCase() || '-';
                const isExtracted = m.status === 'extracted' || m.analysisNotes?.includes('解析完成');
                const isManual = m.analysisNotes?.includes('需人工');
                const wordCount = m.analysisNotes?.match(/长度:\s*(\d+)字/)?.[1] || m.analysisNotes?.match(/^(\d+)→/)?.[1];
                const cleanStats = m.analysisNotes?.match(/三层清洗:\s*(\d+)→(\d+)→(\d+)→(\d+)字\s*\|\s*分块:(\d+)\s*\|\s*去重后:(\d+)\s*\|\s*候选:(\d+)\s*\|\s*验证通过:(\d+)/);
                return (
                  <tr key={m.id} className="border-b last:border-0 group">
                    <td className="py-1.5 truncate max-w-[200px]">{m.fileName}</td>
                    <td className="py-1.5 text-center text-muted-foreground">{ext.toUpperCase()}</td>
                    <td className="py-1.5 text-center text-muted-foreground">{m.fileSize ? `${(m.fileSize / 1024).toFixed(0)}KB` : '-'}</td>
                    <td className="py-1.5 text-center">
                      <span className={`px-2 py-0.5 rounded-full text-xs ${
                        isExtracted ? 'bg-green-100 text-green-700' : isManual ? 'bg-amber-100 text-amber-700' : 'bg-primary-light text-muted-foreground'
                      }`}>
                        {isExtracted ? '✅ 已解析' : isManual ? '⚠️ 需人工' : '⏳ 待处理'}
                      </span>
                    </td>
                    <td className="py-1.5 text-right relative">
                      {cleanStats ? (
                        <span className="relative">
                          <span className="text-muted-foreground cursor-default border-b border-dotted border-border-strong">{wordCount}字</span>
                          <span className="absolute right-0 bottom-full mb-1 w-60 px-3 py-2 bg-gray-800 text-white text-[10px] rounded-lg opacity-0 group-hover:opacity-100 transition pointer-events-none z-20 leading-relaxed">
                            <div className="flex justify-between"><span>原始文本</span><span>{cleanStats[1]}字</span></div>
                            <div className="flex justify-between"><span>去噪后</span><span>{cleanStats[2]}字</span></div>
                            <div className="flex justify-between"><span>过滤后</span><span>{cleanStats[3]}字</span></div>
                            <div className="flex justify-between"><span>归一化</span><span>{cleanStats[4]}字</span></div>
                            <div className="border-t border-gray-600 mt-1 pt-1 flex justify-between"><span>分块</span><span>{cleanStats[5]}块</span></div>
                            <div className="flex justify-between"><span>去重后</span><span>{cleanStats[6]}块</span></div>
                            <div className="flex justify-between"><span>候选颗粒</span><span>{cleanStats[7]}条</span></div>
                            <div className="flex justify-between text-green-300"><span>验证通过</span><span>{cleanStats[8]}条</span></div>
                          </span>
                        </span>
                      ) : (
                        <span className="text-muted-foreground">{wordCount ? `${wordCount}字` : '-'}</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
