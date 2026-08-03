'use client';

import React from 'react';
import { useRouter } from 'next/navigation';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { Toast } from '@/components/ui/Toast';
import { copyToClipboard } from '@/lib/clipboard';
import { activateExpert, extractGrains, getExpertDetail, deleteExpert, deleteGrain, getCompositeDetail, retryExpert, uploadExpertMaterials, uploadDocumentFile, type CompositeInfo } from '@/lib/api/expert';
import { UploadExpertModal } from '@/components/modals/UploadExpertModal';
import { ReviewGrainsModal } from '@/components/modals/ReviewGrainsModal';
import type { GrainGroup } from '@/lib/api/expert';
import { useExperts } from './useExperts';

/** 状态标签配置 */
const STATUS_CONFIG: Record<string, { label: string; color: string; bg: string }> = {
  active: { label: '已激活', color: '#00B42A', bg: '#E6FFEA' },
  extracting: { label: '待审核', color: '#D97706', bg: '#FEF3C7' },
  analyzing: { label: '分析中...', color: '#165DFF', bg: '#F2F3F5' },
  pending: { label: '待分析', color: '#86909C', bg: '#F2F3F5' },
  failed: { label: '失败', color: '#DC2626', bg: '#FEF0F0' },
};

/** 来源文案 */
const SOURCE_LABELS: Record<string, string> = {
  interview: '💬 真人访谈',
  document: '📄 文件材料',
  hybrid: '🔄 混合',
};

/**
 * D3 萃取师经验库管理页
 */
export default function AdminExpertsPage() {
  const router = useRouter();
  const h = useExperts();

  if (h.loading) {
    return <div className="flex items-center justify-center py-20"><LoadingSpinner fullScreen={false} /></div>;
  }

  return (
    <div>
      <Toast message={h.toast} onDone={() => h.setToast(null)} />
      {/* 头部 */}
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold text-foreground">萃取师经验库管理</h1>
        <div className="flex gap-3">
          <button onClick={() => router.push('/admin/experts/interview')}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-white hover:bg-primary-hover">
            🧠 萃取师访谈
          </button>
          <button onClick={() => h.setShowUpload(true)} className="rounded-md border border-border px-4 py-2 text-sm text-muted-foreground hover:bg-primary-light">
            📤 上传新材料
          </button>
          <button onClick={async () => {
            const ok = await copyToClipboard(window.location.origin + '/interview/create?invite=expert');
            if (ok) h.showToast('邀请链接已复制！');
          }} className="rounded-lg border border-border-strong px-4 py-2 text-sm text-primary hover:bg-primary-light">
            🔗 邀请萃取师访谈
          </button>
        </div>
      </div>

      {/* 综合Skill管理 */}
      {h.composite && (
        <div className="mb-6 rounded-xl bg-surface-2 p-5 shadow-sm">
          <h2 className="text-lg font-semibold text-foreground">综合Skill管理</h2>
          <div className="mt-3 flex flex-wrap gap-4 text-sm">
            <span>版本：{h.composite.version}</span>
            <span>萃取师：{h.composite.expertCount}位</span>
            <span className="text-success">共识：{h.composite.consensusCount}条</span>
            <span className="text-primary">独家：{h.composite.singleCount}条</span>
            <span className="text-warning">矛盾：{h.composite.conflictCount}条</span>
          </div>
          <div className="mt-4 flex gap-3">
            <button onClick={async () => {
              try {
                const d = await getCompositeDetail();
                h.setPreviewData(d);
                h.setShowPreview(true);
              } catch(e) { alert('加载综合指令失败'); }
            }} className="text-sm text-primary hover:underline">查看综合指令</button>
            <button
              onClick={async () => {
                h.setCompositeLoading(true);
                try {
                  await h.regenerateComposite();
                  await h.loadData(h.pageRef.current);
                  h.showToast('综合Skill已重新生成');
                } catch(e) { h.showToast('重新生成失败'); }
                finally { h.setCompositeLoading(false); }
              }}
              disabled={h.compositeLoading}
              className="rounded-lg bg-primary px-4 py-2 text-sm text-white hover:bg-primary-hover disabled:opacity-50"
            >
              {h.compositeLoading ? '⏳ 重新生成中...' : '重新生成综合Skill'}
            </button>
          </div>
        </div>
      )}

      {/* 搜索筛选 */}
      <div className="mb-6 flex gap-3">
        <input type="text" value={h.keyword} onChange={(e) => h.setKeyword(e.target.value)} placeholder="搜索萃取师名称..."
          className="flex-1 rounded-lg border border-border-strong px-4 py-2 text-sm outline-none focus:border-foreground" />
        <select value={h.statusFilter} onChange={(e) => h.setStatusFilter(e.target.value)}
          className="rounded-lg border border-border-strong px-3 py-2 text-sm">
          <option value="">全部状态</option>
          <option value="active">已激活</option><option value="extracting">待审核</option>
          <option value="analyzing">分析中</option><option value="pending">未生成</option>
          <option value="failed">失败</option>
        </select>
        <select className="rounded-lg border border-border-strong px-3 py-2 text-sm">
          <option>全部行业</option><option>金融</option><option>快消</option>
        </select>
      </div>

      {/* 萃取师卡片网格 */}
      <div className="grid gap-4 md:grid-cols-3 mb-8">
        {h.experts.map((expert) => {
          const cfg = STATUS_CONFIG[expert.status] || STATUS_CONFIG.pending;
          return (
            <div key={expert.id} className="card p-5 hover:shadow-md transition-shadow">
              <div className="text-center mb-3">
                <div className="flex h-16 w-16 mx-auto items-center justify-center rounded-full bg-gradient-to-br from-navy to-primary text-xl text-white font-bold">
                  {expert.name[0]}
                </div>
                <h3 className="mt-2 font-semibold text-foreground text-base">{expert.name}</h3>
                <span className="inline-block mt-1 rounded-full px-2 py-0.5 text-xs" style={{ background: cfg.bg, color: cfg.color }}>
                  {cfg.label}
                </span>
                {expert.domain && (
                  <span className="inline-block mt-1 ml-1 rounded-full px-2 py-0.5 text-xs bg-success-bg text-success">{expert.domain === 'sales.b2b_enterprise' ? '销售' : expert.domain === 'finance.secondary_market' ? '金融' : expert.domain}</span>
                )}
              </div>
              <div className="flex flex-wrap justify-center gap-1 mb-2">
                {expert.styleTags?.map((t) => (
                  <span key={t} className="rounded-full bg-primary-light px-2 py-0.5 text-xs text-primary">{t}</span>
                ))}
                {expert.industryTags?.map((t) => (
                  <span key={t} className="rounded-full bg-primary-light px-2 py-0.5 text-xs text-muted-foreground">{t}</span>
                ))}
              </div>
              <p className="text-center text-xs text-muted-foreground mb-3">
                {SOURCE_LABELS[expert.sourceType] || expert.sourceType}
                {expert.documentCount ? ` · ${expert.documentCount}个文件` : ''}
                {expert.grainCount ? ` · ${expert.grainCount}条法则` : ''}
              </p>
              {/* 操作按钮 */}
              <div className="flex flex-wrap justify-center gap-1 border-t border-border pt-3">
                {(expert.status === 'pending' || expert.status === 'analyzing') && (
                  <span className="text-xs text-muted-foreground-2">⏳ AI分析中，请稍后刷新</span>
                )}

                {expert.status === 'extracting' && (<>
                  {expert.grainCount === 0 && (
                    <button className="text-sm text-primary hover:underline" onClick={async () => {
                      h.setActionLoading(expert.id);
                      try { await extractGrains(expert.id); h.showToast('法则提取完成，请刷新后审核'); h.loadData(); }
                      catch(e) { h.showToast('提取失败'); }
                      finally { h.setActionLoading(null); }
                    }}>{h.actionLoading === expert.id ? '提取中...' : '提取法则'}</button>
                  )}
                  <button className="text-sm text-primary hover:underline" onClick={async () => {
                    const d = await getExpertDetail(expert.id);
                    if (d?.grainGroups?.length) h.setReviewGrains({ expertName: expert.name, groups: d.grainGroups });
                    else h.showToast('暂无萃取法则，请先提取');
                  }}>审核法则</button>
                </>)}

                {expert.status === 'failed' && (
                  <button className="text-sm text-warning hover:underline" onClick={async () => {
                    h.setActionLoading(expert.id);
                    try {
                      await retryExpert(expert.id);
                      h.showToast('已重新加入处理队列');
                      h.loadData();
                    } catch(e) { h.showToast('重试失败'); }
                    finally { h.setActionLoading(null); }
                  }}>{h.actionLoading === expert.id ? '重试中...' : '重试分析'}</button>
                )}

                {expert.status === 'active' && (<>
                  <button className="text-sm text-primary hover:underline" onClick={async () => {
                    const d = await getExpertDetail(expert.id);
                    if (d?.grainGroups?.length) h.setReviewGrains({ expertName: expert.name, groups: d.grainGroups });
                    else h.showToast('暂无萃取法则');
                  }}>查看法则</button>
                  <button className="text-sm text-primary hover:underline" onClick={async () => {
                    h.setActionLoading(expert.id);
                    try { await extractGrains(expert.id); h.showToast('法则已重新提取'); h.loadData(); }
                    catch(e) { h.showToast('提取失败'); }
                    finally { h.setActionLoading(null); }
                  }}>{h.actionLoading === expert.id ? '提取中...' : '重新提取'}</button>
                  <button className="text-sm text-primary hover:underline" onClick={async () => {
                    if (!confirm('确定要重新激活该萃取师吗？将重新提取法则并更新Skill。')) return;
                    h.setActionLoading(expert.id);
                    try { await activateExpert(expert.id); h.showToast('已重新激活'); h.loadData(); }
                    catch(e) { h.showToast('激活失败'); }
                    finally { h.setActionLoading(null); }
                  }}>重新激活</button>
                </>)}

                <button className="text-sm text-danger hover:underline ml-2" onClick={async () => {
                  if (confirm('确定要删除萃取师"' + expert.name + '"吗？\n将清除其所有数据和关联文件，不可恢复。')) {
                    try { await deleteExpert(expert.id); h.showToast('已删除'); h.loadData(); }
                    catch(e) { h.showToast('删除失败'); }
                  }
                }}>删除</button>
              </div>
            </div>
          );
        })}
        {h.experts.length === 0 && (
          <div className="py-16 text-center"><p className="text-sm text-muted-foreground-2">还没有萃取师经验，上传第一份材料吧</p></div>
        )}

        {/* 分页 */}
        {h.totalPages > 1 && (
          <div className="mt-6 flex items-center justify-center gap-4">
            <button
              onClick={() => h.loadData(h.page - 1)}
              disabled={h.page <= 1}
              className="rounded-lg border border-border-strong px-4 py-2 text-sm text-primary hover:bg-primary-light disabled:opacity-30 disabled:cursor-not-allowed"
            >上一页</button>
            <span className="text-sm text-muted-foreground">第 {h.page} / {h.totalPages} 页</span>
            <button
              onClick={() => h.loadData(h.page + 1)}
              disabled={h.page >= h.totalPages}
              className="rounded-lg border border-border-strong px-4 py-2 text-sm text-primary hover:bg-primary-light disabled:opacity-30 disabled:cursor-not-allowed"
            >下一页</button>
          </div>
        )}
      </div>

      {/* 上传弹窗 */}
      <UploadExpertModal
        open={h.showUpload}
        onClose={() => h.setShowUpload(false)}
        existingExperts={h.experts.map(e => ({ id: e.id, name: e.name }))}
        onUpload={async (data) => {
          try {
            // 第一步：创建萃取师（不传文件元数据，文件走第二步独立上传）
            const result: any = await uploadExpertMaterials({
              name: data.name,
              description: data.description,
              styleTags: data.styleTags,
              industryTags: data.industryTags,
              seniority: data.seniority,
              domain: data.domain,
              existingExpertId: data.existingExpertId || undefined,
              files: [],
            });
            const expertId = result?.data?.id;
            if (!expertId) throw new Error('未获取到萃取师ID');
            // 第二步：逐文件上传字节
            for (const file of data.files) {
              await uploadDocumentFile(expertId, file);
            }
            h.setShowUpload(false);
            h.loadData();
            alert(`萃取师已创建，${data.files.length} 个文件上传成功！AI正在后台分析，请稍后刷新查看。`);
          } catch(e: any) { alert('上传失败: ' + (e?.message || '请检查网络连接')); }
        }}
      />

      {/* 综合指令预览弹窗 */}
      {h.showPreview && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm" onClick={() => h.setShowPreview(false)}>
          <div className="mx-4 w-full max-w-[900px] max-h-[80vh] overflow-y-auto rounded-2xl bg-surface-2 p-6 shadow-xl" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-xl font-bold text-foreground">综合Skill指令预览 · {h.previewData?.version || h.composite?.version}</h2>
              <button onClick={() => { h.setShowPreview(false); h.setPreviewData(null); }} className="text-muted-foreground-2 hover:text-foreground text-xl">✕</button>
            </div>
            <div className="space-y-4 text-sm">
              <div className="rounded bg-success-bg p-4">
                <p className="font-semibold text-success">✅ 共识经验（{h.previewData?.consensusCount || h.composite?.consensusCount}条）</p>
                <p className="mt-1 text-muted-foreground">多位萃取师一致认可的经验，如"当销冠说凭感觉时追问看到了什么信号"。AI优先采用共识经验。</p>
              </div>
              <div className="rounded bg-primary-light p-4">
                <p className="font-semibold text-primary">📌 独家经验（{h.previewData?.singleCount || h.composite?.singleCount}条）</p>
                <p className="mt-1 text-muted-foreground">单一萃取师的独特经验，如"像医生看X光片一样观察销冠微表情"。标注来源后使用。</p>
              </div>
              {((h.previewData?.conflictCount || h.composite?.conflictCount || 0) > 0) && (
                <div className="rounded bg-warning-bg p-4">
                  <p className="font-semibold text-warning">⚠️ 矛盾项（{h.previewData?.conflictCount || h.composite?.conflictCount}条）</p>
                  <p className="mt-1 text-muted-foreground">不同萃取师意见矛盾，需人工确认后使用。</p>
                </div>
              )}
              <div className="rounded bg-surface p-4">
                <p className="font-semibold text-foreground">📋 包含萃取师</p>
                <p className="mt-1 text-muted-foreground">张萃取师（追问型·金融）· 王萃取师（温和型·快消）</p>
              </div>
              <div className="rounded bg-surface p-4">
                <p className="font-semibold text-foreground">⚙️ 综合Skill生成规则</p>
                <p className="mt-1 text-muted-foreground text-xs leading-relaxed">
                  1. 加载所有status=active的萃取师expert_grain<br/>
                  2. 按category分组比对application_rule语义相似度<br/>
                  3. 相似度&gt;90%合并为共识，优先级+1<br/>
                  4. 相似度&lt;50%且同场景标记为conflict<br/>
                  5. 按优先级排序生成expert_h.composite.md
                </p>
              </div>
            </div>
            <div className="mt-4 text-right">
              <button onClick={() => { h.setShowPreview(false); h.setPreviewData(null); }} className="rounded-lg bg-primary-light px-4 py-2 text-sm">关闭</button>
            </div>
          </div>
        </div>
      )}

      {h.reviewGrains && (
        <ReviewGrainsModal
          open={true}
          expertName={h.reviewGrains!.expertName}
          grainGroups={h.reviewGrains!.groups}
          onClose={() => h.setReviewGrains(null)}
          onConfirm={async () => {
            if (h.reviewGrains) { const r = h.reviewGrains;
              const expert = h.experts.find(e => e.name === h.reviewGrains!.expertName);
              if (expert) {
                try {
                  await activateExpert(expert.id);
                  h.showToast('萃取师已激活，可在访谈中选择');
                } catch (e) {
                  h.showToast('激活失败，请重试');
                }
              }
            }
            h.setReviewGrains(null);
            h.loadData();
          }}
          onEditGrain={(grainId) => console.log('edit grain:', grainId)}
          onDeleteGrain={async (grainId) => {
            if (h.reviewGrains) { const r = h.reviewGrains;
              const expert = h.experts.find(e => e.name === h.reviewGrains!.expertName);
              if (expert) {
                await deleteGrain(expert.id, grainId);
                h.loadData();
              }
            }
          }}
        />
      )}
    </div>
  );
}
