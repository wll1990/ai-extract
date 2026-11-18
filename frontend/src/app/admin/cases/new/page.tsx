'use client';

import React, { useState, useEffect, useRef } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import {
  createCase, uploadCase, getCaseUploadStatus, retryCaseUpload, listCaseUploadTasks,
  UPLOAD_TASK_STATUS_LABELS, type UploadTask,
} from '@/lib/api/knowledge';
import { ExternalImportPanel } from '@/components/admin/ExternalImportPanel';

const FUNNEL_STAGES = ['线索', '首触', '需求', '方案', '异议', '报价', '追单', '成交', '复购'];

export default function AdminCasesNewPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [tab, setTab] = useState<'form' | 'upload' | 'import'>(
    searchParams.get('tab') === 'upload' ? 'upload'
      : searchParams.get('tab') === 'import' ? 'import' : 'form');

  // 支持从知识缺口/评测失败题带参预填
  const [title, setTitle] = useState(searchParams.get('title') || '');
  const [funnelStage, setFunnelStage] = useState('');
  const [sceneTag, setSceneTag] = useState('');
  const [result, setResult] = useState('');
  const [objection, setObjection] = useState(searchParams.get('objection') || '');
  const [keyActions, setKeyActions] = useState('');
  const [scripts, setScripts] = useState('');
  const [reusablePoints, setReusablePoints] = useState('');
  const [failurePoints, setFailurePoints] = useState('');
  const [source, setSource] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 结构化客户画像 + 客户原话（不再要求用户手写 JSON）
  const [industry, setIndustry] = useState('');
  const [scale, setScale] = useState('');
  const [role, setRole] = useState('');
  const [quotes, setQuotes] = useState('');
  const [emotionChange, setEmotionChange] = useState('');
  const [advanceNodes, setAdvanceNodes] = useState('');

  const [file, setFile] = useState<File | null>(null);
  const [sourceKind, setSourceKind] = useState<'internal' | 'book'>('internal');
  const [bookConfirmed, setBookConfirmed] = useState(false);
  const [task, setTask] = useState<UploadTask | null>(null);
  const [recentTasks, setRecentTasks] = useState<UploadTask[]>([]);
  const [uploading, setUploading] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  // 最近上传任务：离开页面后回来仍能找到进度与产出
  useEffect(() => {
    listCaseUploadTasks()
      .then(r => setRecentTasks(r.content || []))
      .catch(() => setRecentTasks([]));
  }, []);

  const openTask = (t: UploadTask) => {
    setTask(t);
    if (t.status === 'extracting') startPolling(t.id);
  };

  const submit = async () => {
    if (!title.trim()) { alert('标题为必填'); return; }
    setSubmitting(true);
    const profileEntries = [['industry', industry], ['scale', scale], ['role', role]]
      .map(([k, v]) => [k, v.trim()] as const)
      .filter(([, v]) => v);
    const customerProfile = profileEntries.length
      ? JSON.stringify(Object.fromEntries(profileEntries))
      : undefined;
    const quoteList = quotes.split('\n').map(s => s.trim()).filter(Boolean);
    const dialogueQuote = quoteList.length ? JSON.stringify(quoteList) : undefined;
    try {
      await createCase({
        title: title.trim(),
        customerProfile,
        dialogueQuote,
        emotionChange: emotionChange.trim() || undefined,
        advanceNodes: advanceNodes.trim() || undefined,
        funnelStage: funnelStage || undefined,
        sceneTag: sceneTag.trim() || undefined,
        result: result || undefined,
        objection: objection.trim() || undefined,
        keyActions: keyActions.trim() || undefined,
        scripts: scripts.trim() || undefined,
        reusablePoints: reusablePoints.trim() || undefined,
        failurePoints: failurePoints.trim() || undefined,
        source: source.trim() || undefined,
      });
      router.push('/admin/cases');
    } catch (e) {
      alert(e instanceof Error ? e.message : '创建失败');
      setSubmitting(false);
    }
  };

  const startPolling = (tid: string) => {
    pollRef.current = setInterval(async () => {
      try {
        const t = await getCaseUploadStatus(tid);
        setTask(t);
        if (t.status === 'done' || t.status === 'failed') {
          if (pollRef.current) clearInterval(pollRef.current);
          setUploading(false);
          if (t.status === 'done') {
            alert(`萃取完成，共产出 ${t.producedCount} 个案例（待审核）`);
            router.push('/admin/cases');
          }
        }
      } catch { /* 轮询失败忽略 */ }
    }, 3000);
  };

  const startUpload = async () => {
    if (!file) { alert('请先选择文件'); return; }
    setUploading(true);
    try {
      const { taskId } = await uploadCase(file, sourceKind);
      startPolling(taskId);
    } catch (e) {
      alert(e instanceof Error ? e.message : '上传失败');
      setUploading(false);
    }
  };

  const retry = async () => {
    if (!task?.id) return;
    setUploading(true);
    try {
      await retryCaseUpload(task.id);
      startPolling(task.id);
    } catch (e) {
      alert(e instanceof Error ? e.message : '续传失败');
      setUploading(false);
    }
  };

  const input = 'w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground';

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[720px]">
        <button onClick={() => router.push('/admin/cases')} className="mb-4 text-sm text-muted-foreground hover:underline">← 返回案例库</button>
        <h1 className="mb-4 text-[28px] font-bold text-foreground">新增案例</h1>
        {searchParams.get('title') && (
          <div className="mb-4 rounded-lg border border-primary/30 bg-primary-light px-4 py-2 text-xs text-primary">
            已从知识缺口/评测失败题带入标题与异议，请补充客户画像、过程和结果后保存。
          </div>
        )}

        <div className="mb-5 flex gap-1 rounded-lg bg-surface-2 p-1 w-fit">
          <button onClick={() => setTab('form')}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'form' ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground'}`}>手工录入</button>
          <button onClick={() => setTab('upload')}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'upload' ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground'}`}>文档上传萃取</button>
          <button onClick={() => setTab('import')}
            className={`rounded-md px-4 py-1.5 text-sm font-medium ${tab === 'import' ? 'bg-surface text-foreground shadow-sm' : 'text-muted-foreground'}`}>外部提取导入</button>
        </div>

        {tab === 'form' ? (
          <div className="space-y-4 rounded-xl border border-border bg-surface-2 p-6">
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">标题 *</label>
              <input value={title} onChange={e => setTitle(e.target.value)} className={input} placeholder="如：拿下某银行大单" />
            </div>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">漏斗阶段</label>
                <select value={funnelStage} onChange={e => setFunnelStage(e.target.value)} className={input}>
                  <option value="">未指定</option>
                  {FUNNEL_STAGES.map(s => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">场景标签</label>
                <input value={sceneTag} onChange={e => setSceneTag(e.target.value)} className={input} placeholder="2-4 字" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">结果</label>
                <select value={result} onChange={e => setResult(e.target.value)} className={input}>
                  <option value="">未指定</option>
                  <option value="win">赢单</option>
                  <option value="loss">丢单</option>
                  <option value="delay">搁置</option>
                </select>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">客户画像（参与检索，建议填写）</label>
              <div className="grid grid-cols-3 gap-3">
                <input value={industry} onChange={e => setIndustry(e.target.value)} className={input} placeholder="行业，如 制造业" />
                <input value={scale} onChange={e => setScale(e.target.value)} className={input} placeholder="规模，如 500 人" />
                <input value={role} onChange={e => setRole(e.target.value)} className={input} placeholder="关键角色，如 采购总监" />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">客户原话（每行一句，可引用到回答里）</label>
              <textarea value={quotes} onChange={e => setQuotes(e.target.value)} rows={3} className={input}
                placeholder={'你们比竞品贵 20%\n我们再考虑一下'} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">情绪变化</label>
                <textarea value={emotionChange} onChange={e => setEmotionChange(e.target.value)} rows={2} className={input} placeholder="如：戒备 → 认同" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">推进节点</label>
                <textarea value={advanceNodes} onChange={e => setAdvanceNodes(e.target.value)} rows={2} className={input} placeholder="如：首触 → 方案 → 报价 → 签约" />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">核心异议</label>
              <textarea value={objection} onChange={e => setObjection(e.target.value)} rows={2} className={input} />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">关键动作</label>
              <textarea value={keyActions} onChange={e => setKeyActions(e.target.value)} rows={3} className={input} />
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">关键话术</label>
              <textarea value={scripts} onChange={e => setScripts(e.target.value)} rows={3} className={input} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">可复用点</label>
                <textarea value={reusablePoints} onChange={e => setReusablePoints(e.target.value)} rows={3} className={input} />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">失败点</label>
                <textarea value={failurePoints} onChange={e => setFailurePoints(e.target.value)} rows={3} className={input} />
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">出处（可选）</label>
              <input value={source} onChange={e => setSource(e.target.value)} className={input} />
            </div>
            <button onClick={submit} disabled={submitting}
              className="w-full rounded-full bg-primary py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
              {submitting ? '提交中…' : '保存'}
            </button>
          </div>
        ) : tab === 'upload' ? (
          <div className="space-y-4 rounded-xl border border-border bg-surface-2 p-6">
            <p className="text-sm text-muted-foreground">上传已整理的案例文档，系统按章节切片后提取全套案例字段（画像/阶段/过程/结果/可复用点）；原文分块不落库、不参与检索。</p>
            <p className="text-xs text-muted-foreground">
              单文件 ≤20MB；解析后正文 ≤30 万字。案例信息密度低，整本几百页的产品说明不适合传这里，请只上传相关章节。
            </p>
            <p className="text-xs text-muted-foreground">
              上传内容会自动脱敏：手机号、身份证、银行卡、座机、邮箱、详细地址、合同/招标编号、车牌、微信/QQ，以及「张总/李经理」这类称呼。
              企业私有案例保留客户公司名、项目名和金额；书籍案例或提交到公共库时强制强脱敏，公司名、政府机关、项目名和具体金额都会替换。
            </p>
            <div>
              <p className="mb-2 text-sm font-medium text-foreground">这份材料是什么？</p>
              <div className="grid grid-cols-2 gap-3">
                <button onClick={() => setSourceKind('internal')}
                  className={`rounded-xl border p-3 text-left ${sourceKind === 'internal' ? 'border-primary bg-primary-light' : 'border-border bg-surface hover:bg-surface-2'}`}>
                  <p className="text-sm font-medium text-foreground">企业内部案例</p>
                  <p className="mt-1 text-xs text-muted-foreground">客户纪要、复盘文档。信息完整时直接进入「待审核」。</p>
                </button>
                <button onClick={() => setSourceKind('book')}
                  className={`rounded-xl border p-3 text-left ${sourceKind === 'book' ? 'border-primary bg-primary-light' : 'border-border bg-surface hover:bg-surface-2'}`}>
                  <p className="text-sm font-medium text-foreground">书籍 / 课程案例</p>
                  <p className="mt-1 text-xs text-muted-foreground">二手案例，只提取信息完整的客户故事，一律先落「草稿」并标注书名章节，补齐后才能审核。</p>
                </button>
              </div>
              {sourceKind === 'book' && (
                <>
                  <p className="mt-2 text-xs text-warning-text">
                    书籍案例属于二手资料，采信度低于内部实战案例；单次最多提取 100 条。
                  </p>
                  <label className="mt-2 flex items-start gap-2 text-xs text-muted-foreground">
                    <input type="checkbox" checked={bookConfirmed}
                      onChange={e => setBookConfirmed(e.target.checked)} className="mt-0.5 h-3.5 w-3.5 accent-primary" />
                    我确认已获得该资料的使用授权，且仅用于企业内部培训（系统只保留结构化案例并标注出处）。
                  </label>
                </>
              )}
            </div>
            {recentTasks.length > 0 && (
              <div className="rounded-xl border border-border bg-surface p-4">
                <p className="mb-2 text-sm font-semibold text-foreground">最近上传任务</p>
                <ul className="space-y-1 text-xs">
                  {recentTasks.map(t => (
                    <li key={t.id}>
                      <button onClick={() => openTask(t)} className="w-full text-left text-muted-foreground hover:text-primary">
                        {(t.createdAt ? new Date(t.createdAt) : new Date()).toLocaleString()} · {t.fileName || '文档'} ·{' '}
                        {UPLOAD_TASK_STATUS_LABELS[t.status] || t.status}
                        {t.status === 'done' ? ` · 产出 ${t.producedCount} 条` : ''}
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <input type="file" accept=".pdf,.docx,.txt,.md,.pptx,.csv,.html,.htm"
              onChange={e => setFile(e.target.files?.[0] || null)}
              className="block w-full text-sm text-foreground file:mr-4 file:rounded-full file:border-0 file:bg-primary file:px-4 file:py-2 file:text-sm file:text-white" />
            <button onClick={startUpload} disabled={uploading || !file || (sourceKind === 'book' && !bookConfirmed)}
              className="w-full rounded-full bg-primary py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
              {uploading ? '萃取中…' : '开始萃取'}
            </button>
            {task && (
              <div className="rounded-md bg-surface p-4 text-sm text-foreground">
                <p>任务状态：{UPLOAD_TASK_STATUS_LABELS[task.status] || task.status}
                  {task.status === 'done' ? `（产出 ${task.producedCount} 个）` : ''}
                </p>
                {task.sourceKind === 'book' && (
                  <p className="mt-1 text-xs text-primary">书籍模式：案例已标注书名与章节，且全部为草稿，需补齐字段后审核。</p>
                )}
                {(task.skippedCount ?? 0) > 0 && (
                  <p className="mt-1 text-xs text-muted-foreground">已跳过 {task.skippedCount} 条（重复、结果不明或超出单次上限）。</p>
                )}
                {(task.failedSections ?? 0) > 0 && (
                  <p className="mt-1 text-xs text-danger">有 {task.failedSections} 个章节模型提取失败，未能产出案例；可稍后重试上传或手动补录。</p>
                )}
                {task.guideMessage && (
                  <p className="mt-1 text-xs text-muted-foreground">{task.guideMessage}</p>
                )}
                {task.status === 'extracting' && task.totalSections > 0 && (
                  <p className="mt-1 text-muted-foreground">
                    正在萃取：第 {Math.min(task.processedSections + 1, task.totalSections)} / {task.totalSections} 节
                  </p>
                )}
                {task.status === 'failed' && (
                  <>
                    <p className="mt-1 text-danger">{task.errorMessage || '萃取失败，请检查文件内容后重试'}</p>
                    <button onClick={retry} disabled={uploading}
                      className="mt-2 rounded-full border border-primary px-4 py-1.5 text-sm text-primary hover:bg-primary-light disabled:opacity-50">
                      {task.processedSections < task.totalSections
                        ? `续传（从第 ${task.processedSections + 1} 节继续）`
                        : '重试'}
                    </button>
                  </>
                )}
              </div>
            )}
          </div>
        ) : (
          <ExternalImportPanel kind="case" onImported={() => router.push('/admin/cases')} />
        )}
      </div>
    </div>
  );
}
