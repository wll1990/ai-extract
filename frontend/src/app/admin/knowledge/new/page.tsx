'use client';

import React, { useState, useEffect, useRef } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { ExternalImportPanel } from '@/components/admin/ExternalImportPanel';
import {
  createKnowledge, uploadKnowledge, getKnowledgeUploadStatus, retryKnowledgeUpload,
  listKnowledgeDrafts, adoptKnowledgeDrafts, rejectKnowledgeDrafts, listKnowledgeUploadTasks,
  updateKnowledgeDraft,
  CATEGORY_LABELS, SOURCE_TYPE_LABELS, STATUS_LABELS, UPLOAD_TASK_STATUS_LABELS,
  type UploadTask, type KnowledgeDraft,
} from '@/lib/api/knowledge';

export default function AdminKnowledgeNewPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const initialTab = searchParams.get('tab') === 'upload' ? 'upload'
    : searchParams.get('tab') === 'import' ? 'import' : 'form';
  const [tab, setTab] = useState<'form' | 'upload' | 'import'>(initialTab);

  // 表单（支持从知识缺口/评测失败题带参预填）
  const [title, setTitle] = useState(searchParams.get('title') || '');
  const [category, setCategory] = useState(searchParams.get('category') || 'methodology');
  const [sourceType, setSourceType] = useState('tenant');
  const [content, setContent] = useState(searchParams.get('content') || '');
  const [expireAt, setExpireAt] = useState('');
  const [source, setSource] = useState('');
  const [status, setStatus] = useState('pending');
  const [submitting, setSubmitting] = useState(false);

  // 上传
  const [file, setFile] = useState<File | null>(null);
  const [sourceKind, setSourceKind] = useState<'internal' | 'book'>('internal');
  const [bookConfirmed, setBookConfirmed] = useState(false);
  const [taskId, setTaskId] = useState<string | null>(null);
  const [task, setTask] = useState<UploadTask | null>(null);
  const [uploading, setUploading] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // KN-06 萃取结果预览
  const [recentTasks, setRecentTasks] = useState<UploadTask[]>([]);
  const [drafts, setDrafts] = useState<KnowledgeDraft[]>([]);
  const [draftsLoading, setDraftsLoading] = useState(false);
  const [selectedDrafts, setSelectedDrafts] = useState<Set<string>>(new Set());
  const [acting, setActing] = useState(false);

  // 补齐未达标草稿（标题/正文/分类/出处）
  const [editingDraft, setEditingDraft] = useState<KnowledgeDraft | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editCategory, setEditCategory] = useState('methodology');
  const [editSource, setEditSource] = useState('');

  const openEdit = (d: KnowledgeDraft) => {
    setEditingDraft(d);
    setEditTitle(d.title);
    setEditContent(d.content);
    setEditCategory(d.category);
    setEditSource(d.source || '');
  };

  const saveEdit = async () => {
    if (!taskId || !editingDraft) return;
    setActing(true);
    try {
      const updated = await updateKnowledgeDraft(taskId, editingDraft.id, {
        title: editTitle.trim(),
        content: editContent.trim(),
        category: editCategory,
        source: editSource.trim() || undefined,
      });
      setEditingDraft(null);
      await loadDrafts(taskId);
      if (updated.qualityIssues) {
        alert('仍有未达标项：' + updated.qualityIssues);
      }
    } catch (e) {
      alert(e instanceof Error ? e.message : '保存失败');
    } finally {
      setActing(false);
    }
  };

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  // 最近上传任务：离开页面后回来仍能找到进度与草稿
  useEffect(() => {
    listKnowledgeUploadTasks()
      .then(r => setRecentTasks(r.content || []))
      .catch(() => setRecentTasks([]));
  }, []);

  const openTask = async (task: UploadTask) => {
    setTaskId(task.id);
    setTask(task);
    if (task.status === 'extracting') {
      startPolling(task.id);
    } else {
      loadDrafts(task.id);
    }
  };

  const submit = async () => {
    if (!title.trim() || !content.trim() || !expireAt) {
      alert('标题、内容、失效时间为必填');
      return;
    }
    setSubmitting(true);
    try {
      await createKnowledge({
        title: title.trim(),
        category,
        sourceType,
        content: content.trim(),
        expireAt,
        source: source.trim() || undefined,
        status,
      });
      router.push('/admin/knowledge');
    } catch (e) {
      alert(e instanceof Error ? e.message : '创建失败');
      setSubmitting(false);
    }
  };

  const startPolling = (tid: string) => {
    pollRef.current = setInterval(async () => {
      try {
        const t = await getKnowledgeUploadStatus(tid);
        setTask(t);
        if (t.status === 'done' || t.status === 'failed') {
          if (pollRef.current) clearInterval(pollRef.current);
          setUploading(false);
          if (t.status === 'done') {
            await loadDrafts(tid);
          }
        }
      } catch { /* 轮询失败忽略，下一轮重试 */ }
    }, 3000);
  };

  const loadDrafts = async (tid: string) => {
    setDraftsLoading(true);
    try {
      const list = await listKnowledgeDrafts(tid);
      setDrafts(list);
      setSelectedDrafts(new Set(list.filter(d => d.status === 'pending').map(d => d.id)));
    } catch (e) {
      alert(e instanceof Error ? e.message : '加载萃取结果失败');
    } finally {
      setDraftsLoading(false);
    }
  };

  const toggleDraft = (id: string) => setSelectedDrafts(prev => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  const runDraftAction = async (kind: 'adopt' | 'reject') => {
    if (!taskId) return;
    if (!selectedDrafts.size) { alert('请先选择条目'); return; }
    setActing(true);
    try {
      const ids = Array.from(selectedDrafts);
      if (kind === 'adopt') {
        const result = await adoptKnowledgeDrafts(taskId, ids);
        await loadDrafts(taskId);
        if (result.skipped > 0) {
          const detail = result.skippedItems
            .slice(0, 5)
            .map(s => `「${s.title || s.draftId.slice(0, 8)}」${s.reason}`)
            .join('\n');
          alert(`已采纳 ${result.adopted} 条，跳过 ${result.skipped} 条：\n${detail}`
            + (result.skipped > 5 ? '\n…（其余见条目上的未达标提示）' : ''));
        }
      } else {
        await rejectKnowledgeDrafts(taskId, ids);
        await loadDrafts(taskId);
      }
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const adoptAll = async () => {
    if (!taskId) return;
    setActing(true);
    try {
      const result = await adoptKnowledgeDrafts(taskId, []);
      await loadDrafts(taskId);
      if (result.skipped > 0) {
        const detail = result.skippedItems
          .slice(0, 5)
          .map(s => `「${s.title || s.draftId.slice(0, 8)}」${s.reason}`)
          .join('\n');
        alert(`已采纳 ${result.adopted} 条，跳过 ${result.skipped} 条：\n${detail}`
          + (result.skipped > 5 ? '\n…（其余见条目上的未达标提示）' : ''));
      }
    } catch (e) {
      alert(e instanceof Error ? e.message : '操作失败');
    } finally {
      setActing(false);
    }
  };

  const startUpload = async () => {
    if (!file) { alert('请先选择文件'); return; }
    setUploading(true);
    try {
      const { taskId } = await uploadKnowledge(file, sourceKind);
      setTaskId(taskId);
      startPolling(taskId);
    } catch (e) {
      alert(e instanceof Error ? e.message : '上传失败');
      setUploading(false);
    }
  };

  const retry = async () => {
    if (!taskId) return;
    setUploading(true);
    try {
      await retryKnowledgeUpload(taskId);
      startPolling(taskId);
    } catch (e) {
      alert(e instanceof Error ? e.message : '续传失败');
      setUploading(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[720px]">
        <button onClick={() => router.push('/admin/knowledge')} className="mb-4 text-sm text-muted-foreground hover:underline">← 返回知识库</button>
        <h1 className="mb-4 text-[28px] font-bold text-foreground">新增知识</h1>
        {searchParams.get('title') && (
          <div className="mb-4 rounded-lg border border-primary/30 bg-primary-light px-4 py-2 text-xs text-primary">
            已从知识缺口/评测失败题带入标题与内容，请补充正文、分类和失效时间后保存。
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
              <input value={title} onChange={e => setTitle(e.target.value)}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" placeholder="如：BANT 四问法" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">分类 *</label>
                <select value={category} onChange={e => setCategory(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  {Object.entries(CATEGORY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
                <p className="mt-1 text-xs text-muted-foreground">
                  行业知识选「行业介绍」：通用行业认知放公共库，本企业的行业打法放企业私有。
                </p>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">来源类型 *</label>
                <select value={sourceType} onChange={e => setSourceType(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  {Object.entries(SOURCE_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">内容 *</label>
              <textarea value={content} onChange={e => setContent(e.target.value)} rows={8}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" placeholder="客观事实内容…" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">失效时间 *</label>
                <input value={expireAt} onChange={e => setExpireAt(e.target.value)} type="datetime-local"
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">状态</label>
                <select value={status} onChange={e => setStatus(e.target.value)}
                  className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground">
                  <option value="pending">待审核</option>
                  <option value="draft">草稿</option>
                  <option value="active">已生效</option>
                </select>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">出处（可选）</label>
              <input value={source} onChange={e => setSource(e.target.value)}
                className="w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-foreground" placeholder="版权/来源标注" />
            </div>
            <button onClick={submit} disabled={submitting}
              className="w-full rounded-full bg-primary py-2.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
              {submitting ? '提交中…' : '保存'}
            </button>
          </div>
        ) : tab === 'upload' ? (
          <div className="space-y-4 rounded-xl border border-border bg-surface-2 p-6">
            <p className="text-sm text-muted-foreground">
              上传文档（pdf/docx/txt/md/pptx/csv/html），系统按章节切片后由模型提炼事实型知识卡；原文分块不落库、不参与检索。萃取结果先进入预览，确认采纳后进入知识库「待审核」，审批通过才生效。
            </p>
            <p className="text-xs text-muted-foreground">
              单文件 ≤20MB；解析后正文 ≤50 万字（约 500 页）。产品说明书这类大文件请按手册/产品线/章节拆分后再传——一次上传越大，萃取越慢、越容易混进无关内容。
            </p>
            <p className="text-xs text-muted-foreground">
              合同模板、组织架构、培训课件等纯参考资料：系统没有原始文档库。只有分身需要回答其中可独立陈述的事实/条款时，才把它拆成知识卡上传；不需要就不要上传。
            </p>
            <p className="text-xs text-muted-foreground">
              上传内容会自动脱敏：手机号、身份证、银行卡、座机、邮箱、详细地址、合同/招标编号、车牌、微信/QQ，以及「张总/李经理」这类称呼。
              企业私有资料保留客户公司名、项目名和金额（业务需要）；选择「书籍 / 课程」或提交到公共库时会强制强脱敏，公司名、政府机关、项目名和具体金额都会替换。
            </p>
            <div>
              <p className="mb-2 text-sm font-medium text-foreground">这份材料是什么？</p>
              <div className="grid grid-cols-2 gap-3">
                <button onClick={() => setSourceKind('internal')}
                  className={`rounded-xl border p-3 text-left ${sourceKind === 'internal' ? 'border-primary bg-primary-light' : 'border-border bg-surface hover:bg-surface-2'}`}>
                  <p className="text-sm font-medium text-foreground">企业自有资料</p>
                  <p className="mt-1 text-xs text-muted-foreground">产品资料、价格政策、制度文件、客户纪要。按事实条目提取。</p>
                </button>
                <button onClick={() => setSourceKind('book')}
                  className={`rounded-xl border p-3 text-left ${sourceKind === 'book' ? 'border-primary bg-primary-light' : 'border-border bg-surface hover:bg-surface-2'}`}>
                  <p className="text-sm font-medium text-foreground">书籍 / 课程</p>
                  <p className="mt-1 text-xs text-muted-foreground">整本书或课件。只提炼可检索的方法论卡片，自动丢弃版权页、目录与叙事段落，并标注书名章节出处。</p>
                </button>
              </div>
              {sourceKind === 'book' && (
                <>
                  <p className="mt-2 text-xs text-warning-text">
                    书籍内容按「观点卡片」入库，单次最多 200 条；建议按主题拆书上传，避免一本大部头产出过多待审条目。
                  </p>
                  <label className="mt-2 flex items-start gap-2 text-xs text-muted-foreground">
                    <input type="checkbox" checked={bookConfirmed}
                      onChange={e => setBookConfirmed(e.target.checked)} className="mt-0.5 h-3.5 w-3.5 accent-primary" />
                    我确认已获得该资料的使用授权，且仅用于企业内部培训与知识整理（系统只保留提炼后的观点卡片并标注出处，不复制原文）。
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
                        {(t.failedSections ?? 0) > 0 ? ` · ${t.failedSections} 个章节失败` : ''}
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
            {taskId && task && (
              <div className="rounded-md bg-surface p-4 text-sm text-foreground">
                <p>任务状态：{UPLOAD_TASK_STATUS_LABELS[task.status] || task.status}
                  {task.status === 'done' ? `（产出 ${task.producedCount} 条）` : ''}
                </p>
                {task.sourceKind === 'book' && (
                  <p className="mt-1 text-xs text-primary">书籍模式：按方法论卡片提炼，条目已标注书名与章节出处。</p>
                )}
                {(task.skippedCount ?? 0) > 0 && (
                  <p className="mt-1 text-xs text-muted-foreground">已跳过 {task.skippedCount} 条（重复、版权页噪声或超出单次上限）。</p>
                )}
                {task.guideMessage && (
                  <p className="mt-1 text-xs text-muted-foreground">{task.guideMessage}</p>
                )}
                {task.status === 'extracting' && task.totalSections > 0 && (
                  <p className="mt-1 text-muted-foreground">
                    正在萃取：第 {Math.min(task.processedSections + 1, task.totalSections)} / {task.totalSections} 节
                  </p>
                )}
                {(task.failedSections ?? 0) > 0 && (
                  <p className="mt-1 text-warning-text">
                    {task.failedSections} 个章节萃取失败（其余章节已产出），可稍后重试或手工补录。
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

            {/* KN-06 萃取结果预览 + 批量采纳 */}
            {(draftsLoading || drafts.length > 0) && (
              <div className="rounded-xl border border-border bg-surface p-4">
                <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <h2 className="text-sm font-semibold text-foreground">萃取结果</h2>
                    <p className="text-xs text-muted-foreground">
                      共 {drafts.length} 条，待采纳 {drafts.filter(d => d.status === 'pending').length} 条
                      {drafts.some(d => d.status === 'pending' && d.qualityIssues) && (
                        <span className="text-danger">
                          ，其中 {drafts.filter(d => d.status === 'pending' && d.qualityIssues).length} 条未达准入标准需先补齐（不能采纳）
                        </span>
                      )}
                      。采纳后进入知识库「待审核」。
                    </p>
                  </div>
                  <div className="flex gap-2">
                    <button disabled={acting || !selectedDrafts.size} onClick={() => runDraftAction('adopt')}
                      className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">
                      采纳选中
                    </button>
                    <button disabled={acting || !selectedDrafts.size} onClick={() => runDraftAction('reject')}
                      className="rounded-full border border-border px-4 py-1.5 text-xs text-foreground hover:bg-surface-2 disabled:opacity-50">
                      拒绝选中
                    </button>
                    <button disabled={acting} onClick={adoptAll}
                      className="rounded-full border border-primary px-4 py-1.5 text-xs text-primary hover:bg-primary-light disabled:opacity-50">
                      全部采纳
                    </button>
                  </div>
                </div>

                {editingDraft && (
                  <div className="mb-3 space-y-2 rounded-lg border border-primary/40 bg-surface p-3">
                    <p className="text-xs font-semibold text-foreground">补齐后保存，系统会重新校验</p>
                    <input value={editTitle} onChange={e => setEditTitle(e.target.value)}
                      placeholder="标题（写明场景 + 观点）"
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                    <textarea value={editContent} onChange={e => setEditContent(e.target.value)} rows={5}
                      placeholder="正文（结论先行，写清适用条件）"
                      className="w-full rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                    <div className="grid grid-cols-2 gap-2">
                      <select value={editCategory} onChange={e => setEditCategory(e.target.value)}
                        className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground">
                        {Object.entries(CATEGORY_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                      </select>
                      <input value={editSource} onChange={e => setEditSource(e.target.value)}
                        placeholder="出处（书籍必须填书名 + 章节）"
                        className="rounded-md border border-border bg-surface-2 px-3 py-2 text-sm text-foreground" />
                    </div>
                    <div className="flex gap-2">
                      <button onClick={saveEdit} disabled={acting}
                        className="rounded-full bg-primary px-4 py-1.5 text-xs font-medium text-white hover:opacity-90 disabled:opacity-50">保存</button>
                      <button onClick={() => setEditingDraft(null)}
                        className="rounded-full border border-border px-4 py-1.5 text-xs text-foreground hover:bg-surface-2">取消</button>
                    </div>
                  </div>
                )}

                {draftsLoading ? (
                  <p className="py-6 text-center text-sm text-muted-foreground">加载中…</p>
                ) : (
                  <div className="max-h-[420px] space-y-2 overflow-y-auto pr-1">
                    {drafts.map(d => {
                      const selected = selectedDrafts.has(d.id);
                      const pending = d.status === 'pending';
                      const blocked = pending && !!d.qualityIssues;
                      return (
                        <label key={d.id}
                          className={`block rounded-lg border p-3 transition-colors ${pending ? (blocked ? 'border-danger/40 bg-danger-bg/30' : selected ? 'border-primary bg-primary-light' : 'border-border hover:bg-surface-2') : 'border-border bg-surface-2 opacity-70'}`}>
                          <div className="flex items-start gap-2">
                            <input type="checkbox" checked={selected} disabled={!pending || blocked}
                              onChange={() => toggleDraft(d.id)} className="mt-1 h-4 w-4 accent-primary" />
                            <div className="min-w-0 flex-1">
                              <div className="flex flex-wrap items-center gap-2">
                                <span className="truncate text-sm font-medium text-foreground">{d.title}</span>
                                <span className="shrink-0 rounded-full bg-surface px-2 py-0.5 text-[11px] text-muted-foreground">
                                  {CATEGORY_LABELS[d.category] || d.category} · 第 {d.sectionNo} 节
                                </span>
                                {typeof d.qualityScore === 'number' && (
                                  <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] ${d.qualityScore >= 80 ? 'bg-success-bg text-success' : d.qualityScore >= 60 ? 'bg-warning-bg text-warning-text' : 'bg-danger-bg text-danger'}`}>
                                    质量 {d.qualityScore}
                                  </span>
                                )}
                                {blocked && (
                                  <span className="shrink-0 rounded-full bg-danger-bg px-2 py-0.5 text-[11px] font-medium text-danger">需补齐</span>
                                )}
                                {pending && (
                                  <button
                                    onClick={ev => { ev.preventDefault(); openEdit(d); }}
                                    className="ml-auto shrink-0 text-[11px] text-primary hover:underline">
                                    {blocked ? '补齐' : '编辑'}
                                  </button>
                                )}
                                {!pending && (
                                  <span className="shrink-0 rounded-full bg-surface px-2 py-0.5 text-[11px] text-muted-foreground">
                                    {d.status === 'adopted' ? '已采纳' : '已拒绝'}
                                  </span>
                                )}
                              </div>
                              <p className="mt-1 line-clamp-3 whitespace-pre-wrap text-xs text-muted-foreground">{d.content}</p>
                              {d.qualityIssues && (
                                <p className="mt-1 text-[11px] text-danger">未达准入：{d.qualityIssues}</p>
                              )}
                              {d.qualityWarnings && (
                                <p className="mt-1 text-[11px] text-warning-text">建议补充：{d.qualityWarnings}</p>
                              )}
                            </div>
                          </div>
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            )}
          </div>
        ) : (
          <ExternalImportPanel kind="knowledge" onImported={() => router.push('/admin/knowledge')} />
        )}
      </div>
    </div>
  );
}
