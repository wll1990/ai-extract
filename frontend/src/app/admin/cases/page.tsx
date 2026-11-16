'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useRouter } from 'next/navigation';
import {
  listCases, deprecateCase, approveCase, copyCase, suggestCaseRevision, reindexCase,
  getCaseImprovements, effectiveStatus, listCaseSceneTags, STATUS_LABELS, type IndustryCase,
} from '@/lib/api/knowledge';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import { PageHelp } from '@/components/ui/PageHelp';
import { RevisionModal } from '@/components/ui/RevisionModal';
import { IndexStatusBadge } from '@/components/ui/IndexStatusBadge';
import { ExpiryDialog } from '@/components/admin/ExpiryDialog';
import { getCurrentUser } from '@/lib/api/auth';
import { ApiError } from '@/lib/api/client';

const STATUS_CLS: Record<string, string> = {
  draft: 'bg-gray-100 text-gray-600',
  pending: 'bg-warning-bg text-warning-text',
  active: 'bg-success-bg text-success',
  deprecated: 'bg-gray-100 text-gray-500',
  expired: 'bg-danger-bg text-danger',
};

const RESULT_LABELS: Record<string, string> = { win: '赢单', loss: '丢单', delay: '搁置' };
const RESULT_CLS: Record<string, string> = {
  win: 'bg-success-bg text-success',
  loss: 'bg-danger-bg text-danger',
  delay: 'bg-warning-bg text-warning-text',
};

export default function AdminCasesPage() {
  const router = useRouter();
  const [cases, setCases] = useState<IndustryCase[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [funnelStage, setFunnelStage] = useState('');
  const [sceneTag, setSceneTag] = useState('');
  const [sceneTags, setSceneTags] = useState<string[]>([]);
  const [result, setResult] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [aiOnly, setAiOnly] = useState(false);
  const [scope, setScope] = useState<'' | 'public' | 'private'>('');
  const [isSuperAdmin, setIsSuperAdmin] = useState(false);
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);
  const [revisionTarget, setRevisionTarget] = useState<IndustryCase | null>(null);
  const [approveTarget, setApproveTarget] = useState<IndustryCase | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [pendingCount, setPendingCount] = useState(0);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listCases({ funnelStage: funnelStage || undefined, sceneTag: sceneTag || undefined, result: result || undefined, status: status || undefined, keyword: keyword || undefined, scope: scope || undefined, aiGenerated: aiOnly || undefined, page, size: 20 })
      .then(data => {
        setCases(data.content || []);
        setTotal(data.total || 0);
      })
      .catch(e => {
        if (e instanceof ApiError && e.status === 403) {
          setError('没有案例库管理权限，请联系企业管理员开通');
        } else if (e instanceof ApiError && e.status >= 500) {
          setError('服务暂时不可用，请稍后重试');
        } else if (e instanceof TypeError) {
          setError('网络连接失败，请检查网络后重试');
        } else {
          setError(e instanceof Error ? e.message : '加载失败');
        }
      })
      .finally(() => setLoading(false));
    listCases({ status: 'pending', scope: scope || undefined, page: 1, size: 1 })
      .then(d => setPendingCount(d.total || 0))
      .catch(() => setPendingCount(0));
  }, [funnelStage, sceneTag, result, status, keyword, scope, page, aiOnly]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    getCurrentUser().then(u => setIsSuperAdmin(u.role === 'super_admin')).catch(() => {});
  }, []);

  // 场景标签候选值随筛选范围更新
  useEffect(() => {
    listCaseSceneTags().then(setSceneTags).catch(() => setSceneTags([]));
  }, [scope]);

  useEffect(() => {
    if (!menuFor) return;
    const close = () => setMenuFor(null);
    window.addEventListener('click', close);
    return () => window.removeEventListener('click', close);
  }, [menuFor]);

  const runAction = async (fn: () => Promise<unknown>) => {
    setActing(true);
    try { await fn(); await load(); }
    catch (e) { alert(e instanceof Error ? e.message : '操作失败'); }
    finally { setActing(false); }
  };

  const runActionApproved = async (fn: () => Promise<unknown>) => {
    setActing(true);
    try { await fn(); await load(); setToast('已审批生效'); }
    catch (e) { alert(e instanceof Error ? e.message : '操作失败'); }
    finally { setActing(false); }
  };

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2500);
    return () => clearTimeout(t);
  }, [toast]);

  const onApprove = (c: IndustryCase) => {
    if (c.expireAt) {
      runAction(() => approveCase(c.id, c.expireAt || undefined));
      return;
    }
    setApproveTarget(c);
  };
  const onDeprecate = (c: IndustryCase) => {
    if (!window.confirm(`确认下架「${c.title}」？下架后分身立即停止引用，30 天内可重新上架。`)) return;
    runAction(() => deprecateCase(c.id));
  };
  const onCopy = (c: IndustryCase) => runAction(() => copyCase(c.id));
  const onSuggest = (c: IndustryCase) => setRevisionTarget(c);

  const onReindex = (c: IndustryCase) => runAction(() => reindexCase(c.id));

  const totalPages = Math.max(1, Math.ceil(total / 20));

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1080px]">
        <div className="mb-4 flex items-center justify-between">
          <div>
            <h1 className="mb-1 text-[28px] font-bold text-foreground">案例库</h1>
            <p className="text-sm text-muted-foreground">过程型案例（客户画像/推进过程/结果/可复用点）</p>
          </div>
          <div className="flex gap-3">
            <PageHelp
              title="案例库使用说明"
              intro="案例库存一次真实客户推进（画像/异议/原话/动作/结果），是分身回答「这类客户怎么打」的实战依据。只有「已生效」且未过期的案例会被检索，相似度阈值 0.50（比知识更严），每次最多 3 条。"
              steps={[
                { title: '新增或上传', desc: '少量案例用「新增案例」手填；整份材料用「上传文档萃取」。书籍/课程案例一律先落草稿并标「二手」，需补齐后审批。' },
                { title: '补齐字段', desc: '必须有客户画像、核心异议、关键动作/话术、结果（赢单/丢单/搁置）。不达标的案例会标红并禁止审批。' },
                { title: '审批生效', desc: '草稿和待审核的案例点「审批」后生效，可设置失效时间（案例可长期有效）。' },
                { title: '查看与改写', desc: '点标题进详情看全文与被引用情况；「AI改写」按指令改内容：草稿/待审直接覆盖，已生效会生成新版本，审批后旧版下线。' },
                { title: '下架', desc: '错误或过期的案例点「下架」，分身立即停止引用；30 天内可重新上架。彻底删除不可恢复，仅草稿/待审/已下架可用。' },
              ]}
              notes={[
                '准入标准：标题不能是「案例一」这类占位名；必须有核心异议或关键动作/话术；结果必须是赢单/丢单/搁置；书籍案例必须标出处。',
                '公共案例由平台维护，企业要改先「复制到本企业」；提交公共库前会自动强脱敏（公司名/项目名/金额）。',
                '标题写清「什么客户 + 什么场景」，并填客户原话、关键动作和推进节点，检索命中后才真正有用。',
              ]}
            />
            <button onClick={() => router.push('/admin/cases/new')} data-tour="cases-add"
              className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90">＋ 新增案例</button>
            <button onClick={() => router.push('/admin/cases/new?tab=upload')}
              className="rounded-full border border-primary px-5 py-2.5 text-sm font-medium text-primary hover:bg-primary-light">上传文档萃取</button>
          </div>
        </div>

        <div className="mb-3 flex gap-2">
          {([
            ['', '全部'],
            ['public', '公共案例'],
            ['private', '企业私有'],
          ] as const).map(([value, label]) => (
            <button key={value || 'all'}
              onClick={() => { setScope(value); setPage(1); }}
              className={`rounded-full px-4 py-1.5 text-sm ${scope === value ? 'bg-primary text-white' : 'bg-primary-light text-muted-foreground hover:bg-border'}`}>
              {label}
            </button>
          ))}
          <span className="ml-2 self-center text-xs text-muted-foreground">
            公共案例由平台维护，企业可复制后修改
          </span>
        </div>

        <div className="mb-4 flex flex-wrap gap-2">
          <input value={keyword} onChange={e => setKeyword(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') setPage(1); }}
            placeholder="搜索标题/异议/话术/可复用点…"
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground w-56" />
          <select value={funnelStage} onChange={e => { setFunnelStage(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部阶段</option>
            {['线索', '首触', '需求', '方案', '异议', '报价', '追单', '成交', '复购'].map(s => <option key={s} value={s}>{s}</option>)}
          </select>
          <select value={sceneTag} onChange={e => { setSceneTag(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部场景</option>
            {sceneTags.map(t => <option key={t} value={t}>{t}</option>)}
          </select>
          <select value={result} onChange={e => { setResult(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部结果</option>
            <option value="win">赢单</option>
            <option value="loss">丢单</option>
            <option value="delay">搁置</option>
          </select>
          <select value={status} onChange={e => { setStatus(e.target.value); setPage(1); }}
            className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
            <option value="">全部状态</option>
            <option value="draft">草稿</option>
            <option value="pending">待审核</option>
            <option value="active">已生效（未过期）</option>
            <option value="expired">已过期</option>
            <option value="deprecated">已下架</option>
          </select>
          <button onClick={() => setPage(1)} className="rounded-md bg-primary px-4 py-2 text-sm text-white hover:opacity-90">搜索</button>
          <button onClick={() => { setAiOnly(v => !v); setPage(1); }}
            className={`rounded-full px-3 py-2 text-xs font-medium ${
              aiOnly ? 'bg-primary text-white' : 'border border-border text-muted-foreground hover:bg-primary-light'}`}>
            AI 生成
          </button>
          {pendingCount > 0 && (
            <button onClick={() => { setStatus('pending'); setPage(1); }}
              className={`rounded-full px-3 py-2 text-xs font-medium ${status === 'pending' ? 'bg-warning-bg text-warning-text' : 'border border-warning-text/40 text-warning-text hover:bg-warning-bg'}`}>
              待我审 {pendingCount}
            </button>
          )}
        </div>

        {error && (
          <div className="mb-4 rounded-xl bg-danger-bg border border-red-200 px-4 py-3 text-sm text-danger">
            {error}
            <button onClick={() => window.location.reload()} className="ml-3 underline">重试</button>
          </div>
        )}

        {loading ? <LoadingSpinner /> : (
          <>
            {cases.length === 0 ? (
              <div className="py-20 text-center">
                <span className="text-5xl">{keyword || status || result || funnelStage || sceneTag ? '🔍' : '🎞️'}</span>
                <h2 className="mt-4 text-lg font-semibold text-foreground">
                  {keyword || status || result || funnelStage || sceneTag ? '没有匹配的案例' : '暂无案例'}
                </h2>
                <p className="mt-2 text-sm text-muted-foreground">
                  {keyword || status || result || funnelStage || sceneTag
                    ? '换个关键词或清空筛选条件再试。'
                    : '还没有案例。案例是分身回答的“实战依据”，建议先用最近 3 个成交/丢单的客户对话各建一条。'}
                </p>
                <div className="mt-5 flex justify-center gap-3">
                  <button onClick={() => router.push('/admin/cases/new')}
                    className="rounded-full bg-primary px-5 py-2.5 text-sm font-medium text-white hover:opacity-90">
                    新增案例
                  </button>
                  <button onClick={() => router.push('/admin/cases/new?tab=upload')}
                    className="rounded-full border border-primary px-5 py-2.5 text-sm font-medium text-primary hover:bg-primary-light">
                    上传文档萃取
                  </button>
                </div>
              </div>
            ) : (
              <div className="rounded-xl border border-border bg-surface-2">
                <div className="grid grid-cols-12 gap-2 px-5 py-3 text-xs font-semibold text-muted-foreground border-b border-border">
                  <span className="col-span-4">标题</span>
                  <span className="col-span-2">阶段</span>
                  <span className="col-span-2">结果</span>
                  <span className="col-span-2">状态</span>
                  <span className="col-span-2 text-right">操作</span>
                </div>
                {cases.map(c => {
                  const st = effectiveStatus(c.status, c.expireAt);
                  return (
                    <div key={c.id} className="grid grid-cols-12 gap-2 px-5 py-3.5 border-b border-border last:border-0 items-center hover:bg-primary-light transition-colors">
                      <button onClick={() => router.push(`/admin/cases/${c.id}`)} className="col-span-4 min-w-0 text-left">
                        <p className="truncate text-sm font-medium text-foreground hover:underline">{c.title}</p>
                        <p className="truncate text-xs text-muted-foreground" title={c.objection || c.customerProfile || ''}>
                          {c.sceneTag ? `${c.sceneTag} · ` : ''}{c.objection || c.customerProfile || '—'}
                        </p>
                      </button>
                      <span className="col-span-2 text-sm text-foreground">{c.funnelStage || '—'}</span>
                      <span className="col-span-2">
                        {c.result ? <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${RESULT_CLS[c.result] || 'bg-gray-100 text-gray-600'}`}>{RESULT_LABELS[c.result] || c.result}</span> : <span className="text-muted-foreground">—</span>}
                      </span>
                      <span className="col-span-2 flex min-w-0 flex-col gap-0.5">
                        <span className="flex items-center gap-1.5">
                          <span className={`inline-flex px-2 py-0.5 rounded-full text-xs font-medium ${STATUS_CLS[st] || 'bg-gray-100 text-gray-600'}`}>{STATUS_LABELS[st] || st}</span>
                          {c.origin === 'book' && (
                            <span className="rounded-full bg-warning-bg px-1.5 py-0.5 text-[10px] text-warning-text">书籍</span>
                          )}
                          {c.generated && <span className="text-[10px] text-primary">AI 生成</span>}
                          {st === 'active' && <IndexStatusBadge status={c.embeddingStatus} title={c.indexError || undefined} />}
                        </span>
                        {isSuperAdmin ? (
                          <span className="truncate text-[10px] text-muted-foreground" title={c.companyName || '公共案例'}>
                            {c.companyName || '公共'}
                          </span>
                        ) : (
                          <>
                            {c.companyId === null && <span className="text-[10px] text-primary">公共</span>}
                            {c.sourceCompanyId && <span className="text-[10px] text-muted-foreground">企业提交</span>}
                          </>
                        )}
                      </span>
                      <div className="col-span-2 flex justify-end items-center gap-2">
                        {(c.status === 'pending' || c.status === 'draft') && (
                          <button onClick={() => onApprove(c)} disabled={acting} className="text-xs text-primary font-medium hover:underline">审批</button>
                        )}
                        {c.companyId === null && (
                          <button onClick={() => onCopy(c)} disabled={acting} className="text-xs text-primary font-medium hover:underline">复制</button>
                        )}
                        <div className="relative">
                          <button
                            onClick={e => { e.stopPropagation(); setMenuFor(menuFor === c.id ? null : c.id); }}
                            className="text-xs text-muted-foreground hover:text-foreground hover:underline">更多</button>
                          {menuFor === c.id && (
                            <div className="absolute right-0 top-5 z-20 w-28 rounded-md border border-border bg-surface py-1 shadow-lg">
                              {st === 'active' && c.embeddingStatus !== 'indexed' && (
                                <button onClick={() => { setMenuFor(null); onReindex(c); }} disabled={acting}
                                  className="block w-full px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light disabled:opacity-50">重建索引</button>
                              )}
                              {c.status !== 'deprecated' && (
                                <button onClick={() => { setMenuFor(null); onSuggest(c); }} disabled={acting}
                                  className="block w-full px-3 py-1.5 text-left text-xs text-foreground hover:bg-primary-light disabled:opacity-50">AI 改写</button>
                              )}
                              {c.status !== 'deprecated' && (
                                <button onClick={() => { setMenuFor(null); onDeprecate(c); }} disabled={acting}
                                  className="block w-full px-3 py-1.5 text-left text-xs text-danger hover:bg-danger-bg disabled:opacity-50">下架</button>
                              )}
                              {c.status === 'deprecated' && !(st === 'active' && c.embeddingStatus !== 'indexed') && (
                                <p className="px-3 py-1.5 text-xs text-muted-foreground">无可执行操作</p>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {totalPages > 1 && (
              <div className="mt-4 flex items-center justify-center gap-3 text-sm">
                <button disabled={page <= 1} onClick={() => setPage(p => p - 1)}
                  className="rounded-md border border-border px-3 py-1.5 disabled:opacity-40 hover:bg-primary-light">上一页</button>
                <span className="text-muted-foreground">{page} / {totalPages}（共 {total} 条）</span>
                <button disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}
                  className="rounded-md border border-border px-3 py-1.5 disabled:opacity-40 hover:bg-primary-light">下一页</button>
              </div>
            )}
          </>
        )}
      </div>

      <RevisionModal
        open={!!revisionTarget}
        title="AI 改写案例"
        behaviorNote={revisionTarget?.status === 'active'
          ? '已生效内容：生成新版本草稿，审批通过后才替换旧版。'
          : '草稿/待审内容：直接覆盖当前条目，不额外生成版本。'}
        submitLabel={revisionTarget?.status === 'active' ? '生成新版本草稿' : '直接改写'}
        entryTitle={revisionTarget?.title || ''}
        currentContent={revisionTarget
          ? [
              `标题：${revisionTarget.title}`,
              `客户画像：${revisionTarget.customerProfile || '—'}`,
              `核心异议：${revisionTarget.objection || '—'}`,
              `关键动作：${revisionTarget.keyActions || '—'}`,
              `关键话术：${revisionTarget.scripts || '—'}`,
              `推进节点：${revisionTarget.advanceNodes || '—'}`,
              `可复用点：${revisionTarget.reusablePoints || '—'}`,
              `失败点：${revisionTarget.failurePoints || '—'}`,
            ].join('\n\n')
          : ''}
        presets={[
          '补充客户异议的应对话术',
          '补充客户原话',
          '把推进节点按时间顺序重排',
          '压缩到 500 字以内',
        ]}
        onLoadSuggestions={() => getCaseImprovements(revisionTarget!.id)}
        onSubmit={async instruction => {
          const draft = await suggestCaseRevision(revisionTarget!.id, instruction);
          setRevisionTarget(null);
          router.push(`/admin/cases/${draft.id}`);
        }}
        onClose={() => setRevisionTarget(null)}
      />

      <ExpiryDialog
        open={!!approveTarget}
        title="审批生效"
        description={`「${approveTarget?.title || ''}」审批后会被分身检索，可设置失效时间。`}
        onCancel={() => setApproveTarget(null)}
        onConfirm={iso => {
          const target = approveTarget;
          setApproveTarget(null);
          if (target) runActionApproved(() => approveCase(target.id, iso));
        }}
      />

      {toast && (
        <div className="fixed bottom-6 left-1/2 z-[70] -translate-x-1/2 rounded-full bg-foreground px-4 py-2 text-sm text-surface shadow-lg">
          {toast}
        </div>
      )}
    </div>
  );
}
