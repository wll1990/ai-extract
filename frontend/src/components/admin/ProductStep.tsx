'use client';

import React, { useState } from 'react';
import { getToken } from '@/lib/storage';
import { API_BASE, apiClient } from '@/lib/api/client';
import { getAuditDashboard, type AuditDashboard } from '@/lib/api/audit';

// JSON array ↔ plain text helpers
function jsonToText(v: string) { try { const arr = JSON.parse(v); return Array.isArray(arr) ? arr.join('、') : v; } catch { return v; } }
function textToJson(v: string) { const arr = v.split(/[,，、]+/).map(s => s.trim()).filter(Boolean); return JSON.stringify(arr); }

interface Props {
  skillId: string;
  data: AuditDashboard;
  productSubStep: 'profile' | 'verify' | 'publish';
  setProductSubStep: (s: 'profile' | 'verify' | 'publish') => void;
  publishing: boolean;
  publishChecks: Array<{ label: string; pass: boolean }>;
  allPassed: boolean;
  handlePublish: () => void;
  handleDiscard: () => void;
  showDemoModal: boolean;
  setShowDemoModal: (v: boolean) => void;
  onPreviewReport: (skillId: string) => void;
  onDownloadReport: (skillId: string, format: 'html' | 'ppt') => Promise<void>;
  onDataRefresh: (d: AuditDashboard) => void;
}

const SUB_STEPS = [
  { key: 'profile' as const, label: '完善画像', icon: '👤' },
  { key: 'verify' as const, label: '验证测试', icon: '🧪' },
  { key: 'publish' as const, label: '发布上线', icon: '🚀' },
];

export default function ProductStep({
  skillId, data, productSubStep, setProductSubStep,
  publishing, publishChecks, allPassed, handlePublish, handleDiscard,
  showDemoModal, setShowDemoModal,
  onPreviewReport, onDownloadReport, onDataRefresh,
}: Props) {
  const [savingInline, setSavingInline] = useState(false);
  const [inlineEdit, setInlineEdit] = useState<Record<string, string>>(() => {
    // init from data
    const p = data.profile;
    return {
      ownerName: data.skill.ownerName || '',
      ownerTitle: data.skill.ownerTitle || '',
      department: data.skill.department || '',
      seniority: data.skill.seniority || '',
      personality: p?.personality || '',
      speakingStyle: p?.speakingStyle || '',
      background: p?.background || '',
      commonPhrases: p?.commonPhrases || '',
      knowledgeDomains: jsonToText(p?.knowledgeDomains || ''),
      communicationPreferences: jsonToText(p?.communicationPreferences || ''),
      weaknessNotes: p?.weaknessNotes || '',
    };
  });

  const demoEvals = data.evaluations.filter((e: any) => e.mode === 'auto_demo');
  const verified = demoEvals.length > 0;
  const latestEval = verified ? demoEvals[0] : null;

  const handleSaveInline = async () => {
    setSavingInline(true);
    try {
      await apiClient(`/admin/skills/${skillId}/supplement`, {
        method: 'PUT',
        body: JSON.stringify({
          displayName: inlineEdit.ownerName,
          ownerName: inlineEdit.ownerName,
          ownerTitle: inlineEdit.ownerTitle,
          department: inlineEdit.department,
          seniority: inlineEdit.seniority,
        }),
      });
      await fetch(`${API_BASE}/admin/skills/${skillId}/profile`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken()}` },
        body: JSON.stringify({
          personality: inlineEdit.personality || '',
          speakingStyle: inlineEdit.speakingStyle || '',
          background: inlineEdit.background || '',
          commonPhrases: inlineEdit.commonPhrases || '',
          knowledgeDomains: textToJson(inlineEdit.knowledgeDomains || ''),
          communicationPreferences: textToJson(inlineEdit.communicationPreferences || ''),
          weaknessNotes: inlineEdit.weaknessNotes || '',
        }),
      });
      const d = await getAuditDashboard(skillId);
      onDataRefresh(d);
    } catch (e) { console.error('save failed', e); }
    setSavingInline(false);
  };

  return (
    <div className="space-y-6">
      <div className="mb-4 p-4 bg-green-50 rounded-lg text-sm text-green-700">
        🚀 将技能转化为可交付的产品：画像完善、实战验证、报告输出、发布上线
      </div>

      <div className="flex items-center gap-0 bg-surface-2 rounded-lg border border-border p-3">
        {SUB_STEPS.map((s, i) => (
          <React.Fragment key={s.key}>
            <button onClick={() => setProductSubStep(s.key)}
              className={`flex items-center gap-2 px-3 py-2 rounded-lg transition flex-1 text-sm ${
                productSubStep === s.key ? 'bg-primary-light ring-1 ring-primary' : 'hover:bg-primary-light'
              }`}>
              <span className="text-base">{s.icon}</span>
              <span className={`font-medium ${productSubStep === s.key ? 'text-primary' : 'text-foreground'}`}>{s.label}</span>
            </button>
            {i < SUB_STEPS.length - 1 && <span className="text-gray-300 shrink-0 mx-1">→</span>}
          </React.Fragment>
        ))}
      </div>

      {productSubStep === 'profile' && (
        <div className="bg-surface-2 border rounded-xl p-6 space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold">👤 分身信息</h3>
              <p className="text-[11px] text-muted-foreground-2 mt-0.5">
                <span className="text-red-400">*</span> 性格特征、从业背景、擅长领域为发布必填项
              </p>
            </div>
            <button onClick={handleSaveInline} disabled={savingInline}
              className="px-4 py-1.5 bg-primary text-white rounded-lg text-sm disabled:opacity-50">
              {savingInline ? '保存中...' : '保存'}
            </button>
          </div>

          <div className="flex items-start gap-4">
            <div className="w-12 h-12 bg-gradient-to-br from-blue-400 to-purple-500 rounded-full flex items-center justify-center text-white text-xl shrink-0">👤</div>
            <div className="flex-1 grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs text-muted-foreground-2">姓名</label>
                <input value={inlineEdit.ownerName || ''} onChange={e => setInlineEdit(prev => ({...prev, ownerName: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="张销冠" />
              </div>
              <div>
                <label className="text-xs text-muted-foreground-2">职位</label>
                <input value={inlineEdit.ownerTitle || ''} onChange={e => setInlineEdit(prev => ({...prev, ownerTitle: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="金融科技销售总监" />
              </div>
              <div>
                <label className="text-xs text-muted-foreground-2">部门</label>
                <input value={inlineEdit.department || ''} onChange={e => setInlineEdit(prev => ({...prev, department: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="金融事业部" />
              </div>
              <div>
                <label className="text-xs text-muted-foreground-2">从业年限</label>
                <input value={inlineEdit.seniority || ''} onChange={e => setInlineEdit(prev => ({...prev, seniority: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="10年" />
              </div>
            </div>
          </div>

          <div className="border-t pt-4">
            <h4 className="text-xs font-semibold text-muted-foreground mb-3">🧠 性格画像</h4>
            <div className="grid grid-cols-2 gap-3">
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground"><span className="text-red-400 mr-0.5">*</span>性格特征（一句话概括，发布必填）
                  {inlineEdit.personality && <span className="text-green-400 ml-1">✓</span>}
                </label>
                <textarea value={inlineEdit.personality || ''} onChange={e => setInlineEdit(prev => ({...prev, personality: e.target.value}))}
                  className={`w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary h-14 resize-none ${!inlineEdit.personality ? 'border-amber-300 bg-amber-50/30' : ''}`}
                  placeholder="例如：先挖痛点再给方案，从不主动报价——让客户自己算账" />
              </div>
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground-2">说话风格</label>
                <input value={inlineEdit.speakingStyle || ''} onChange={e => setInlineEdit(prev => ({...prev, speakingStyle: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="例如：简洁直接、数据驱动、先问再答" />
              </div>
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground"><span className="text-red-400 mr-0.5">*</span>从业背景（发布必填）
                  {inlineEdit.background && <span className="text-green-400 ml-1">✓</span>}
                </label>
                <textarea value={inlineEdit.background || ''} onChange={e => setInlineEdit(prev => ({...prev, background: e.target.value}))}
                  className={`w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary h-14 resize-none ${!inlineEdit.background ? 'border-amber-300 bg-amber-50/30' : ''}`}
                  placeholder="例如：10年金融科技B2B销售经验，从一线做到区域总监，服务过工行、招行等大客户" />
              </div>
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground-2">口头禅/常用语</label>
                <input value={inlineEdit.commonPhrases || ''} onChange={e => setInlineEdit(prev => ({...prev, commonPhrases: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="例如：我跟你讲个真实案例、这个问题我以前碰到过" />
              </div>
            </div>
          </div>

          <div className="border-t pt-4">
            <h4 className="text-xs font-semibold text-muted-foreground mb-3">📐 能力边界</h4>
            <div className="grid grid-cols-2 gap-3">
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground"><span className="text-red-400 mr-0.5">*</span>擅长领域（逗号分隔，发布必填）
                  {inlineEdit.knowledgeDomains && <span className="text-green-400 ml-1">✓</span>}
                </label>
                <input value={inlineEdit.knowledgeDomains || ''} onChange={e => setInlineEdit(prev => ({...prev, knowledgeDomains: e.target.value}))}
                  className={`w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary ${!inlineEdit.knowledgeDomains ? 'border-amber-300 bg-amber-50/30' : ''}`}
                  placeholder="例如：金融科技B2B销售、大客户谈判、银行招投标、SaaS定价" />
              </div>
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground-2">沟通偏好</label>
                <input value={inlineEdit.communicationPreferences || ''} onChange={e => setInlineEdit(prev => ({...prev, communicationPreferences: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary" placeholder="例如：先听后说、数据驱动、故事化表达" />
              </div>
              <div className="col-span-2">
                <label className="text-xs text-muted-foreground-2">薄弱点/注意事项</label>
                <textarea value={inlineEdit.weaknessNotes || ''} onChange={e => setInlineEdit(prev => ({...prev, weaknessNotes: e.target.value}))}
                  className="w-full border rounded px-2 py-1 text-sm mt-0.5 focus:outline-none focus:border-primary h-14 resize-none"
                  placeholder="对纯技术型客户有时过于强势，需要提醒先建立信任" />
              </div>
            </div>
          </div>
        </div>
      )}

      {productSubStep === 'verify' && (
        <div className="bg-surface-2 border rounded-xl p-5">
          <div className="flex items-start justify-between">
            <div>
              <h3 className="text-sm font-semibold mb-1">🧪 发布前验证</h3>
              <p className="text-xs text-muted-foreground-2">在发布前测试AI分身的实际表现：对练、问答或AI自动模拟</p>
            </div>
            <button onClick={() => setShowDemoModal(true)}
              className="px-5 py-2.5 bg-primary text-white rounded-lg text-sm font-medium hover:bg-primary-hover">
              🚀 开始验证
            </button>
          </div>
          {demoEvals.length > 0 && (
            <div className="mt-3 flex items-center gap-2 text-xs text-green-600">
              <span>✅</span>
              <span>已完成 {demoEvals.length} 次验证{latestEval?.score != null && ` · 最高${latestEval.score}分`}</span>
            </div>
          )}
        </div>
      )}

      {productSubStep === 'publish' && (
        <div className="space-y-6">
          <div className="bg-surface-2 border rounded-xl p-5">
            <h3 className="text-sm font-semibold mb-3">📄 报告输出</h3>
            {verified ? (
              <>
                <p className="text-xs text-green-600 mb-3">
                  ✅ 验证已完成（{demoEvals.length}次·最高{latestEval?.score || '-'}分）
                  {data.skillsSummary.activeGrains > 0 && ` · 基于${data.skillsSummary.activeGrains}条活跃颗粒生成最终报告`}
                </p>
                <div className="flex flex-wrap gap-2">
{(() => {
                    const g = data.skillsSummary.activeGrains || 0;
                    const sc = data.skillsSummary.sceneTags?.length || 0;
                    const ready = g >= 10 && sc >= 3;
                    return (
                      <button onClick={() => onPreviewReport(skillId)}
                        className={`px-4 py-2 rounded-lg text-sm transition-colors ${
                          ready ? 'border border-primary text-primary hover:bg-primary-light' : 'border border-gray-200 text-gray-400 cursor-not-allowed'
                        }`}
                        title={ready ? '预览报告' : `报告未就绪：颗粒 ${g}/10 · 场景 ${sc}/3`}>
                        👁 预览报告 {!ready && <span className="ml-1 text-xs">(颗粒{g}/10 · 场景{sc}/3)</span>}
                      </button>
                    );
                  })()}
                  <button onClick={() => onDownloadReport(skillId, 'html')} className="px-4 py-2 bg-gold text-white rounded-lg text-sm">📄 HTML</button>
                  <button onClick={() => onDownloadReport(skillId, 'ppt')} className="px-4 py-2 bg-orange text-white rounded-lg text-sm">📊 PPT</button>
                </div>
              </>
            ) : (
              <p className="text-xs text-muted-foreground-2">验证通过后，系统基于最新颗粒生成最终报告。请先完成 4b 验证测试。</p>
            )}
          </div>

          <div className="bg-surface-2 border rounded-xl p-5">
            <h3 className="text-sm font-semibold mb-4">发布条件</h3>
            <div className="space-y-2 mb-6">
              {publishChecks.map(c => (
                <div key={c.label} className="flex items-center gap-2 text-sm">
                  <span>{c.pass ? '✅' : '❌'}</span>
                  <span className={c.pass ? 'text-foreground' : 'text-red-500'}>{c.label}</span>
                </div>
              ))}
            </div>
            <div className="flex gap-3">
              <button disabled={!allPassed || publishing}
                onClick={handlePublish}
                className={`px-6 py-2 rounded-lg text-white text-sm ${allPassed ? 'bg-success hover:bg-success' : 'bg-gray-300 cursor-not-allowed'}`}>
                {publishing ? '发布中...' : '🚀 发布分身'}
              </button>
              <button onClick={handleDiscard}
                className="px-6 py-2 border border-red-300 text-red-600 rounded-lg text-sm hover:bg-red-50">
                🗑 废弃
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
