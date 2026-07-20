'use client';

import React from 'react';
import type { AuditDashboard } from '@/lib/api/audit';

interface Props {
  skillId: string;
  data: AuditDashboard;
  onPreviewReport: (skillId: string) => void;
  onDownloadReport: (skillId: string, format: 'html' | 'ppt') => Promise<void>;
}

export default function SkillStep({ skillId, data, onPreviewReport, onDownloadReport }: Props) {
  const s = data.skillsSummary;
  const dims = s.dimensionAvg || {};
  const grainCount = s.activeGrains || 0;
  const sceneCount = s.sceneTags?.length || 0;
  const reportReady = grainCount >= 10 && sceneCount >= 3;
  const qDist = s.qualityDistribution || {};
  const verified = data.extractionResult?.verifiedCount || s.activeGrains;
  const rejected = data.extractionResult?.rejectedCount || 0;
  const total = s.totalGrains;
  const passRate = total > 0 ? verified / total : 0;
  const avgScore = dims.specificity
    ? Math.round((dims.specificity + dims.reproducibility + dims.causality + dims.distinctiveness + dims.falsifiability) / 5 * 10) / 10
    : null;
  const patterns = data.extractionResult?.patterns;
  const dimLabels: Record<string, string> = { specificity: '特异性', reproducibility: '可复制性', causality: '因果性', distinctiveness: '差异性', falsifiability: '可证伪性' };
  const dimTips: Record<string, string> = { specificity: '话术是否具体可执行，而非正确废话', reproducibility: '新人能否学会，而非依赖个人天赋', causality: '行为→反应→结果链条是否清晰', distinctiveness: '是否反直觉，而非教科书标配', falsifiability: '是否说明了失效条件和边界' };
  const minDim = Object.entries(dims).sort((a, b) => a[1] - b[1])[0];
  const verdict = total === 0 ? null
    : passRate >= 0.8 && (avgScore ?? 0) >= 3.5 ? { emoji: '✅', color: 'green', text: '质量良好，可以进入下一步', desc: `通过率 ${Math.round(passRate*100)}% · 均分 ${avgScore} · ${s.sceneTags.length} 个场景覆盖` }
    : passRate < 0.5 ? { emoji: '⚠️', color: 'red', text: '建议补充更多素材后重新萃取', desc: `通过率仅 ${Math.round(passRate*100)}%，低质量颗粒过多` }
    : { emoji: '🔶', color: 'amber', text: '质量一般，建议复核后继续', desc: `通过率 ${Math.round(passRate*100)}%，${qDist.mid || 0} 条需人工复核` };

  return (
    <div>
      {/* 📄 萃取报告 */}
      {s.activeGrains > 0 && (
        <div className="mb-6 bg-surface-2 border rounded-xl p-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold">📄 萃取报告</h3>
              <p className="text-xs text-muted-foreground-2 mt-0.5">
                {s.activeGrains} 条活跃颗粒 · {s.totalGrains} 条总计 · {s.sceneTags?.length || 0} 个场景
              </p>
            </div>
            <div className="flex gap-2">
              <button onClick={() => onPreviewReport(skillId)}
                className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${
                  reportReady
                    ? 'border border-primary text-primary hover:bg-primary-light'
                    : 'border border-gray-200 text-gray-400 cursor-not-allowed'
                }`}
                title={reportReady ? '预览报告' : `颗粒 ${grainCount}/10 · 场景 ${sceneCount}/3`}>
                👁 预览 {!reportReady && <span className="ml-1 text-[10px]">({grainCount}/10)</span>}
              </button>
              <button onClick={() => onDownloadReport(skillId, 'html')} className="px-3 py-1.5 bg-gold text-white rounded-lg text-xs hover:bg-amber-600">📄 HTML</button>
              <button onClick={() => onDownloadReport(skillId, 'ppt')} className="px-3 py-1.5 bg-orange text-white rounded-lg text-xs hover:bg-orange-700">📊 PPT</button>
            </div>
          </div>
        </div>
      )}
      {/* AI 裁决条 */}
      {verdict && (
        <div className={`mb-6 px-5 py-4 rounded-xl border text-sm ${
          verdict.color === 'green' ? 'bg-green-50 border-green-200 text-green-800' :
          verdict.color === 'red' ? 'bg-red-50 border-red-200 text-red-800' :
          'bg-amber-50 border-amber-200 text-amber-800'
        }`}>
          <div className="flex items-center gap-2 mb-1">
            <span className="text-lg">{verdict.emoji}</span>
            <span className="font-semibold">AI 质量裁决</span>
            <span className="text-xs opacity-70 ml-auto">基于 {total} 条颗粒的对抗验证结果</span>
          </div>
          <p className="font-medium">{verdict.text}</p>
          <p className="text-xs mt-0.5 opacity-70">{verdict.desc}</p>
        </div>
      )}
      {/* 核心心法 */}
      {patterns && (
        <div className="mb-6 bg-gradient-to-br from-amber-50 to-yellow-50 border border-amber-200 rounded-xl p-5">
          <div className="flex items-center gap-2 mb-2">
            <span className="text-lg">🧠</span>
            <h3 className="text-base font-bold text-amber-800">核心心法：{patterns.methodologyName || ''}</h3>
            <span className="text-xs text-amber-400 bg-amber-100 px-2 py-0.5 rounded-full ml-auto">AI 从真实对话中提炼</span>
          </div>
          <p className="text-sm text-foreground mb-4 italic">"{patterns.oneliner || ''}"</p>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <p className="text-xs text-amber-600 font-medium mb-2">🟢 核心习惯</p>
              {(patterns.coreHabits || []).map((h: string) => (
                <div key={h} className="flex items-start gap-2 text-xs text-foreground mb-1.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-green-400 mt-1 shrink-0"></span>
                  <span>{h}</span>
                </div>
              ))}
            </div>
            <div>
              <p className="text-xs text-amber-600 font-medium mb-2">🟠 与普通销售的区别</p>
              {(patterns.differentiators || []).map((d: string) => (
                <div key={d} className="flex items-start gap-2 text-xs text-foreground mb-1.5">
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-400 mt-1 shrink-0"></span>
                  <span>{d}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
      {/* 4 指标卡 */}
      {total > 0 && (
        <div className="grid grid-cols-4 gap-3 mb-6">
          <div className="bg-surface-2 border rounded-xl p-3 text-center">
            <div className="text-xl font-bold text-green-600">{verified}</div>
            <div className="text-[10px] text-muted-foreground-2">验证通过</div>
            <div className="text-[10px] text-green-500">{Math.round(passRate * 100)}%</div>
          </div>
          <div className="bg-surface-2 border rounded-xl p-3 text-center">
            <div className="text-xl font-bold text-gold">{avgScore ?? '-'}</div>
            <div className="text-[10px] text-muted-foreground-2">均质量分 /5</div>
          </div>
          <div className="bg-surface-2 border rounded-xl p-3 text-center">
            <div className="text-xl font-bold text-blue-600">{s.sceneTags.length}</div>
            <div className="text-[10px] text-muted-foreground-2">场景覆盖</div>
          </div>
          <div className="bg-surface-2 border rounded-xl p-3 text-center">
            <div className="text-xl font-bold text-amber-600">{qDist.mid || 0}</div>
            <div className="text-[10px] text-muted-foreground-2">需复核 (3-4分)</div>
          </div>
        </div>
      )}
      {/* 详细分析（折叠） */}
      {total > 0 && (
        <details className="bg-surface-2 border rounded-xl p-4">
          <summary className="text-sm font-semibold cursor-pointer hover:text-primary select-none">
            📊 详细分析（五维评分 · 质量分布 · 场景覆盖 · 颗粒明细）
          </summary>
          <div className="mt-4 space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <h4 className="text-xs font-semibold text-muted-foreground-2 mb-2">质量分布</h4>
                <div className="space-y-1.5">
                  {[{label:'✅ 4-5分 直接可用',color:'bg-green-500',count:qDist.high||0},
                    {label:'⚠️ 3-4分 需复核',color:'bg-amber-400',count:qDist.mid||0},
                    {label:'❌ <3分 已剔除',color:'bg-red-400',count:qDist.low||0}].map(item => (
                    <div key={item.label} className="flex items-center gap-2 text-xs">
                      <span className="w-28 text-muted-foreground">{item.label}</span>
                      <div className="flex-1 h-3 bg-primary-light rounded-full overflow-hidden">
                        <div className={`h-full ${item.color} rounded-full`} style={{width:`${total>0?item.count/total*100:0}%`}}></div>
                      </div>
                      <span className="w-6 text-right text-muted-foreground">{item.count}条</span>
                    </div>
                  ))}
                </div>
              </div>
              <div>
                <h4 className="text-xs font-semibold text-muted-foreground-2 mb-2">五维评分</h4>
                {Object.keys(dims).length > 0 ? (
                  <div className="space-y-1.5">
                    {Object.entries(dims).map(([k, v]) => (
                      <div key={k} className="flex items-center gap-2 text-xs">
                        <span className={`w-14 flex items-center gap-0.5 ${minDim&&minDim[0]===k?'text-red-500 font-medium':'text-muted-foreground'}`}>
                          {dimLabels[k] || k}
                          <span className="relative group">
                            <span className="text-gray-300 cursor-default text-[9px]">ⓘ</span>
                            <span className="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 px-2 py-1 bg-gray-800 text-white text-[10px] rounded whitespace-nowrap opacity-0 group-hover:opacity-100 transition pointer-events-none z-10">{dimTips[k] || ''}</span>
                          </span>
                        </span>
                        <div className="flex-1 h-1.5 bg-primary-light rounded-full overflow-hidden">
                          <div className={`h-full rounded-full ${minDim&&minDim[0]===k?'bg-red-400':'bg-blue-400'}`} style={{width:`${(v as number)/5*100}%`}}></div>
                        </div>
                        <span className={`w-5 text-right ${minDim&&minDim[0]===k?'text-red-500 font-medium':'text-muted-foreground'}`}>{v}</span>
                      </div>
                    ))}
                    {minDim && <p className="text-[10px] text-red-400 mt-1">{dimLabels[minDim[0]]} 偏低 → 建议优化提取 prompt</p>}
                  </div>
                ) : <p className="text-xs text-muted-foreground-2">暂无评分数据</p>}
              </div>
            </div>
            <div>
              <h4 className="text-xs font-semibold text-muted-foreground-2 mb-2">场景覆盖 · 颗粒明细</h4>
              <div className="max-h-64 overflow-y-auto">
              <table className="w-full text-xs">
                <thead><tr className="text-left text-muted-foreground-2 border-b sticky top-0 bg-surface-2">
                  <th className="pb-1.5 font-medium">场景名</th>
                  <th className="pb-1.5 font-medium w-12 text-right">数量</th>
                  <th className="pb-1.5 font-medium w-12 text-right">占比</th>
                  <th className="pb-1.5 font-medium w-10 text-right">均分</th>
                  <th className="pb-1.5 font-medium pl-2">核心话术</th>
                </tr></thead>
                <tbody>
                  {(() => {
                    const allGrains = data.scenarioGrains || {};
                    return Object.entries(s.sceneCoverage).sort((a,b)=>b[1]-a[1]).map(([tag,count])=>{
                      const grains = allGrains[tag] || [];
                      const avgScore = grains.length > 0
                        ? (grains.reduce((s:number,g:any)=>s+(g.qualityScore||0),0)/grains.length).toFixed(1)
                        : '-';
                      const firstScript = grains[0]?.standardScript || '';
                      return (
                    <tr key={tag} className={`border-b last:border-0 ${count===1?'text-muted-foreground-2':''}`}>
                      <td className="py-1.5">{tag}{count===1?<span className="text-gray-300 ml-1">覆盖不足</span>:''}</td>
                      <td className="py-1.5 text-right">{count}</td>
                      <td className="py-1.5 text-right">{total>0?Math.round(count/total*100):0}%</td>
                      <td className="py-1.5 text-right">
                        {grains.length > 0 && <span className={`${Number(avgScore)>=4?'text-green-600':Number(avgScore)>=3?'text-amber-500':'text-red-400'}`}>{avgScore}</span>}
                      </td>
                      <td className="py-1.5 pl-2 text-muted-foreground truncate max-w-[200px]">{firstScript.substring(0,50)}{firstScript.length>50?'…':'-'}</td>
                    </tr>
                    );});
                  })()}
                </tbody>
              </table>
              </div>
            </div>
          </div>
        </details>
      )}

      {total === 0 && (
        <p className="text-sm text-muted-foreground-2 text-center py-10">暂无萃取数据，请先上传素材等待调度器处理</p>
      )}
    </div>
  );
}
