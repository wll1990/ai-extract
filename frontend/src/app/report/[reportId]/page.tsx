'use client';
import { LoadingSpinner } from '@/components/ui/LoadingSpinner';
import React from 'react';
import { useParams, useRouter } from 'next/navigation';
import { rateReport } from '@/lib/api/report';
import { useReport, type Chapter } from './useReport';

export default function ReportDetailPage() {
  const params = useParams();
  const router = useRouter();
  const reportId = params.reportId as string;
  const h = useReport(reportId);

  if (h.loading) return <div className="flex min-h-screen items-center justify-center bg-surface"><LoadingSpinner fullScreen={false} /></div>;

  return (
    <div className="min-h-screen bg-surface">
      <div className="sticky top-0 z-40 h-[3px] bg-border"><div className="h-full bg-primary transition-all duration-200" style={{ width: `${h.readProgress}%` }} /></div>

      <div className="mx-auto flex max-w-[1200px] gap-8 px-6 py-8">
        <div ref={h.contentRef} className="flex-1 min-w-0">
          <div className="mb-8">
            <button type="button" onClick={() => router.back()} className="mb-4 text-sm text-primary transition-colors hover:text-foreground">← 返回</button>
            <h1 className="text-[28px] font-bold text-foreground leading-tight">{h.report?.title}</h1>
            {h.report?.subtitle && <p className="mt-2 text-lg text-muted-foreground">{h.report.subtitle}</p>}
            <div className="mt-3 flex items-center gap-4 text-sm text-muted-foreground">
              {h.report?.authorName && <span>{h.report.authorName}</span>}
              {h.report?.createdAt && <span>{new Date(h.report.createdAt).toLocaleDateString('zh-CN')}</span>}
              <span className="flex items-center gap-1 text-primary">{'★'.repeat(Math.round(h.report?.rating || 0))}{'☆'.repeat(5 - Math.round(h.report?.rating || 0))} {h.report?.rating}</span>
            </div>
            <div className="mt-4 flex items-center gap-3">
              <button type="button" onClick={() => h.handleDownload('word')} className="rounded-lg bg-primary-light px-4 py-2 text-sm text-foreground transition-colors hover:bg-border">📥 Word</button>
              <button type="button" onClick={() => h.handleDownload('ppt')} className="rounded-lg bg-primary-light px-4 py-2 text-sm text-foreground transition-colors hover:bg-border">📥 PPT</button>
              <button type="button" onClick={() => h.startEdit(h.chapters.find(c => c.order === 1) || h.chapters[0])} className="rounded-lg px-4 py-2 text-sm text-primary transition-colors hover:bg-primary-light">✏️ 编辑</button>
              <button type="button" onClick={() => { navigator.clipboard.writeText(window.location.href); alert('链接已复制！'); }} className="rounded-lg px-4 py-2 text-sm text-primary transition-colors hover:bg-primary-light">🔗 分享</button>
            </div>
          </div>

          <div className="space-y-10">
            {h.chapters.map((chapter) => (
              <section key={chapter.order} data-chapter={chapter.order}>
                <h2 className="mb-6 text-2xl font-bold text-foreground">{chapter.title}</h2>
                {chapter.content && <div className="rounded-xl bg-surface-2 p-6 shadow-sm"><div className="prose max-w-none text-base leading-[1.8] text-foreground">{chapter.content.split('\n').map((p, i) => <p key={i} className="mb-3">{p}</p>)}</div></div>}
                {chapter.steps && <div className="space-y-3">{chapter.steps.map((step) => (
                  <details key={step.order} className="group rounded-xl bg-surface-2 shadow-sm">
                    <summary className="cursor-pointer px-6 py-4 text-lg font-semibold text-foreground list-none"><span className="mr-3 inline-flex h-7 w-7 items-center justify-center rounded-full bg-foreground text-sm text-white">{step.order}</span>{step.name}<span className="ml-2 text-muted-foreground-2 group-open:hidden">▶</span><span className="ml-2 hidden text-muted-foreground-2 group-open:inline">▼</span></summary>
                    <div className="border-t border-border px-6 py-4 space-y-3">
                      <div className="rounded-lg bg-primary-light p-4"><span className="text-xs font-medium text-muted-foreground">核心动作</span><p className="mt-1 text-sm">{step.action}</p></div>
                      {step.script && <div className="rounded-lg border-l-[3px] border-primary bg-warning-bg/30 p-4"><span className="text-xs font-medium text-primary">话术示例</span><p className="mt-1 text-sm italic">"{step.script}"</p></div>}
                      {step.mistake && <div className="rounded-lg border-l-[3px] border-danger bg-danger-bg/50 p-4"><span className="text-xs font-medium text-danger">常见错误</span><p className="mt-1 text-sm">{step.mistake}</p></div>}
                    </div>
                  </details>
                ))}</div>}
                {chapter.decisions && <div className="space-y-4">{chapter.decisions.map((d, i) => (
                  <div key={i} className="rounded-xl bg-surface-2 p-6 shadow-sm"><h3 className="mb-4 text-lg font-semibold text-foreground">{d.title}</h3>
                    <div className="space-y-2">{d.options.map((opt: string) => <div key={opt} className={`rounded-lg border px-4 py-3 text-sm ${opt === d.chosen ? 'border-success bg-success-bg text-success font-medium' : 'border-danger-bg bg-danger-bg/50 text-danger'}`}>{opt === d.chosen ? '✅ ' : '❌ '}{opt}</div>)}</div>
                    {d.reason && <p className="mt-4 rounded-lg bg-primary-light p-4 text-sm text-foreground">💡 {d.reason}</p>}
                  </div>
                ))}</div>}
                {chapter.quotes && <div className="space-y-4">
                  {chapter.oneliner && <div className="rounded-xl border-l-[3px] border-primary bg-warning-bg/20 p-6"><p className="text-lg font-semibold italic text-foreground">"{chapter.oneliner}"</p>{chapter.metaphor && <p className="mt-2 text-sm text-muted-foreground">像{chapter.metaphor}</p>}</div>}
                  {chapter.quotes.map((quote, i) => <div key={i} className="flex items-start gap-3 rounded-xl bg-surface-2 p-5 shadow-sm"><span className="text-primary text-xl">"</span><p className="flex-1 text-sm italic text-foreground">{quote}</p><button type="button" onClick={() => navigator.clipboard.writeText(quote)} className="rounded-lg p-1.5 text-muted-foreground-2 transition-colors hover:bg-primary-light hover:text-primary" title="复制金句"><svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}><path strokeLinecap="round" strokeLinejoin="round" d="M15.666 3.888A2.25 2.25 0 0013.5 2.25h-3c-1.03 0-1.9.693-2.166 1.638m7.332 0c.055.194.084.4.084.612v0a.75.75 0 01-.75.75H9a.75.75 0 01-.75-.75v0c0-.212.03-.418.084-.612m7.332 0c.646.049 1.288.11 1.927.184 1.1.128 1.907 1.077 1.907 2.185V19.5a2.25 2.25 0 01-2.25 2.25H6.75A2.25 2.25 0 014.5 19.5V6.257c0-1.108.806-2.057 1.907-2.185a48.208 48.208 0 011.927-.184" /></svg></button></div>)}
                </div>}
                {chapter.pitfalls && <div className="space-y-3">{chapter.pitfalls.map((p, i) => <div key={i} className="flex items-start gap-3 rounded-xl bg-surface-2 p-5 shadow-sm"><span className="mt-0.5 flex h-6 w-6 items-center justify-center rounded-full bg-danger-bg text-sm text-danger">⚠</span><div><p className="text-sm font-medium text-foreground">{p.title}</p>{p.solution && <p className="mt-1 text-sm text-success">→ {p.solution}</p>}</div></div>)}</div>}
                {chapter.checklist && <div className="overflow-hidden rounded-xl bg-surface-2 shadow-sm"><div className="bg-foreground px-6 py-3 text-sm font-semibold text-white">✅ 行动检查清单</div><div className="divide-y divide-border">{chapter.checklist.map((item: any) => { const key = `step-${item.step}`; return <label key={key} className="flex cursor-pointer items-center gap-4 px-6 py-4 transition-colors hover:bg-surface"><input type="checkbox" checked={!!h.checklistItems[key]} onChange={() => h.toggleChecklist(key)} className="h-5 w-5 rounded accent-navy" /><span className={`text-sm ${h.checklistItems[key] ? 'text-muted-foreground-2 line-through' : 'text-foreground'}`}><span className="mr-2 font-medium text-muted-foreground">步骤{item.step}</span>{item.action}</span></label>; })}</div><div className="border-t border-border px-6 py-3"><button type="button" onClick={() => window.print()} className="text-sm text-primary transition-colors hover:text-foreground">🖨️ 打印检查清单</button></div></div>}
                {chapter.practiceScene && <div className="rounded-xl bg-surface-2 p-6 shadow-sm"><h3 className="mb-4 text-lg font-semibold text-foreground">🎯 {chapter.practiceScene.title}</h3><div className="mb-4 rounded-lg bg-warning-bg/40 border-l-[3px] border-primary p-4"><p className="text-sm text-muted-foreground">{chapter.practiceScene.setting}</p><p className="mt-2 text-sm font-medium text-foreground">"{chapter.practiceScene.customerLine}"</p></div><textarea value={h.practiceAnswer} onChange={e => h.setPracticeAnswer(e.target.value)} placeholder="输入你的回答..." rows={4} className="w-full resize-none rounded-lg border border-border px-4 py-3 text-sm outline-none transition-colors focus:border-foreground" /><div className="mt-3 flex gap-3"><button type="button" onClick={() => h.setShowAnswer(!h.showAnswer)} className="rounded-lg bg-primary-light px-4 py-2 text-sm text-primary transition-colors hover:bg-border">{h.showAnswer ? '隐藏参考答案' : '查看参考答案'}</button></div>{h.showAnswer && <div className="mt-4 rounded-lg bg-success-bg p-4 text-sm text-foreground">💡 参考答案：深呼吸，保持微笑，先不急于报价。问对方："您最看重的是什么？除了价格，还有什么让您犹豫？"倾听并记录，然后给方案而非降价。</div>}</div>}
              </section>
            ))}
          </div>

          <div className="mt-12 border-t border-border pt-8"><h3 className="text-center text-lg font-semibold text-foreground">觉得有用？</h3><div className="mt-3 flex justify-center gap-1">{[1,2,3,4,5].map(star => <button key={star} type="button" onClick={() => { h.setUserRating(star); rateReport(reportId, star).catch(() => {}); }} className={`text-2xl transition-colors ${star <= h.userRating ? 'text-primary' : 'text-border-strong'}`}>★</button>)}</div></div>
        </div>

        <aside className="sticky top-16 hidden w-[200px] flex-shrink-0 lg:block"><nav className="space-y-1">{h.chapters.map(ch => <a key={ch.order} href={`#chapter-${ch.order}`} onClick={e => { e.preventDefault(); document.querySelector(`[data-chapter="${ch.order}"]`)?.scrollIntoView({ behavior: 'smooth' }); }} className={`block rounded-lg px-3 py-2 text-sm transition-all ${h.activeChapter === ch.order ? 'border-l-2 border-primary bg-primary-light font-semibold text-foreground' : 'text-muted-foreground hover:bg-primary-light hover:text-foreground'}`}>{ch.title}</a>)}</nav></aside>
      </div>

      {h.editing && h.editChapter && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"><div className="mx-4 w-full max-w-2xl rounded-2xl bg-surface-2 p-6 shadow-xl"><h3 className="mb-4 text-lg font-bold text-foreground">编辑 - {h.editChapter.title}</h3><textarea value={h.editContent} onChange={e => h.setEditContent(e.target.value)} rows={12} className="w-full resize-none rounded-lg border border-border px-4 py-3 text-sm outline-none" /><div className="mt-4 flex justify-end gap-3"><button type="button" onClick={() => h.setEditing(false)} className="rounded-lg px-4 py-2 text-sm text-muted-foreground">取消</button><button type="button" onClick={h.handleSave} className="rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white">保存</button></div></div></div>}

      {h.showSaveModal && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"><div className="mx-4 w-full max-w-sm rounded-2xl bg-surface-2 p-6 shadow-xl"><h3 className="mb-2 text-lg font-bold text-foreground">内容已更新</h3><p className="mb-6 text-sm text-muted-foreground">Web版已自动更新。是否同时重新生成Word和PPT？</p><div className="flex gap-3"><button type="button" onClick={() => h.confirmSave(false)} className="flex-1 rounded-lg border border-border py-3 text-sm text-muted-foreground">仅更新Web</button><button type="button" onClick={() => h.confirmSave(true)} className="flex-1 rounded-lg bg-primary py-3 text-sm font-semibold text-white">重新生成全部</button></div></div></div>}
    </div>
  );
}
