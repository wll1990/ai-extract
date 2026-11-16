'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import { apiClient } from '@/lib/api/client';
import { copyToClipboard } from '@/lib/clipboard';
import { submitGrainCorrection } from '@/lib/api/grain-corrections';

interface GrainTrace {
  grainId: string;
  spaceId?: string;
  sceneDescription?: string;
  expertThought?: string;
  standardScript?: string;
  commonMistakes?: string;
  qualityScore?: number;
  difficultyLevel?: string;
  reportTitle?: string;
  reportId?: string;
  sourceName?: string;
  sourceType?: string;
  sourceSnippet?: string;
  avgSimilarity?: number;
}

interface TraceabilityDrawerProps {
  grainIds: string;
  avgSimilarity?: number | string;
  open: boolean;
  onClose: () => void;
  /** 组织分身 ID — 传入后渲染成员可点击链接 */
  orgSkillId?: string;
  /** 当前分身 ID — 传入后可对单条颗粒提交纠错建议 */
  skillId?: string;
}

function matchLevel(sim: number) {
  if (sim >= 50) return { label: '精准匹配', cls: 'bg-green-100 text-green-700', icon: '🏅' };
  if (sim >= 30) return { label: '关联匹配', cls: 'bg-blue-100 text-blue-700', icon: '📎' };
  return { label: '参考', cls: 'bg-gray-100 text-gray-600', icon: '📖' };
}

export function TraceabilityDrawer({ grainIds, avgSimilarity, open, onClose, orgSkillId, skillId }: TraceabilityDrawerProps) {
  const [data, setData] = useState<GrainTrace[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [copied, setCopied] = useState<Record<string, boolean>>({});
  const [memberLinks, setMemberLinks] = useState<Record<string, string>>({});
  const [reportTarget, setReportTarget] = useState<GrainTrace | null>(null);
  const [reportReason, setReportReason] = useState('');
  const [reportSuggestion, setReportSuggestion] = useState('');
  const [reportSubmitting, setReportSubmitting] = useState(false);
  const [reportDone, setReportDone] = useState(false);
  const touchStartX = useRef(0);
  const [swipeOffset, setSwipeOffset] = useState(0);

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
  }, []);

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    const diff = e.touches[0].clientX - touchStartX.current;
    if (diff > 0) setSwipeOffset(diff); // 只跟踪右滑
  }, []);

  const handleTouchEnd = useCallback(() => {
    if (swipeOffset > 80) {
      onClose();
    }
    setSwipeOffset(0);
  }, [swipeOffset, onClose]);

  useEffect(() => {
    if (!open || !grainIds) return;
    setLoading(true);
    setData([]);      // 切换消息时先清空，避免展示上一条的旧数据
    setError('');
    apiClient(`/admin/grains/traceability?grainIds=${encodeURIComponent(grainIds)}`)
      .then(r => setData((r as any) || []))
      .catch((e: unknown) => {
        const status = (e as { status?: number })?.status;
        setError(status === 403
          ? '当前账号无权查看经验溯源（需要颗粒管理权限）'
          : '溯源加载失败，请稍后重试');
      })
      .finally(() => setLoading(false));
  }, [open, grainIds]);

  // 组织分身：查 spaceId → skillId 映射
  useEffect(() => {
    if (!orgSkillId || !open) return;
    apiClient(`/admin/organization-skills/${orgSkillId}/member-links`)
      .then((r: any) => { if (r) setMemberLinks(r); })
      .catch(() => {});
  }, [orgSkillId, open]);

  const handleCopy = (text: string, id: string) => {
    copyToClipboard(text).then(ok => {
      if (ok) {
        setCopied(prev => ({ ...prev, [id]: true }));
        setTimeout(() => setCopied(prev => ({ ...prev, [id]: false })), 1500);
      }
    });
  };

  const submitReport = async () => {
    if (!skillId || !reportTarget || !reportReason.trim() || reportSubmitting) return;
    setReportSubmitting(true);
    try {
      await submitGrainCorrection(skillId, reportTarget.grainId, {
        reason: reportReason.trim(),
        expertThought: reportSuggestion.trim() || undefined,
      });
      setReportDone(true);
      setTimeout(() => {
        setReportTarget(null);
        setReportReason('');
        setReportSuggestion('');
        setReportDone(false);
      }, 1200);
    } catch (e) {
      alert(e instanceof Error ? e.message : '提交失败');
    } finally {
      setReportSubmitting(false);
    }
  };

  if (!open) return null;

  return (
    <>
      {/* 遮罩 */}
      <div onClick={onClose} className="fixed inset-0 bg-black/20 backdrop-blur z-[100]" />

      {/* 抽屉 */}
      <div
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        style={{ transform: swipeOffset > 0 ? `translateX(${swipeOffset}px)` : undefined, transition: swipeOffset === 0 ? 'transform 0.25s ease' : undefined }}
        className="fixed top-0 right-0 bottom-0 w-[420px] max-w-[92vw] bg-white z-[101] shadow-[-8px_0_30px_rgba(0,0,0,0.12)] flex flex-col overflow-hidden animate-[slideInRight_0.3s_ease-out]">

        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-50 flex-shrink-0 bg-gray-50/50">
          <div className="flex items-center gap-2">
            <span className="text-[15px] font-bold text-gray-800">📋 溯源</span>
            {!loading && data.length > 0 && (
              <span className="text-[11px] text-indigo-600 bg-indigo-50 px-2 py-0.5 rounded-full font-medium">
                {data.length} 条经验
              </span>
            )}
          </div>
          <button onClick={onClose} className="bg-transparent border-none cursor-pointer text-lg text-gray-400 hover:text-gray-600 transition-colors p-0 leading-none">
            ✕
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-4 py-4 space-y-3" style={{ scrollbarWidth: 'thin' }}>
          {loading ? (
            <div className="text-center py-12 text-gray-400 text-sm">加载中…</div>
          ) : error ? (
            <div className="text-center py-12 text-red-500 text-sm">
              <p>{error}</p>
              {skillId && grainIds && (
                <button
                  onClick={() => {
                    setReportTarget({ grainId: grainIds.split(',')[0].trim() });
                    setReportReason('');
                    setReportSuggestion('');
                    setReportDone(false);
                  }}
                  className="mt-3 text-xs text-indigo-500 underline"
                >
                  仍然报告这条回答引用的经验有误
                </button>
              )}
            </div>
          ) : data.length === 0 ? (
            <div className="text-center py-12 text-gray-400 text-sm">暂无溯源信息</div>
          ) : data.map((grain, i) => {
            const sim = typeof avgSimilarity === 'number' ? avgSimilarity
              : avgSimilarity ? Number(avgSimilarity) : 0;
            const level = matchLevel(sim);
            const borderColors = ['border-l-indigo-500', 'border-l-violet-500', 'border-l-sky-500'];
            return (
            <div key={grain.grainId} className={`rounded-2xl overflow-hidden border border-gray-100 shadow-sm bg-white border-l-[3px] ${borderColors[i % 3]}`}>

              {/* Card Header */}
              <div className="px-4 pt-4 pb-3 border-b border-gray-50">
                {/* 组织分身：显示可点击的成员名 */}
                {orgSkillId && grain.spaceId && memberLinks[grain.spaceId] && (
                  <a href={`/skill/${memberLinks[grain.spaceId]}`}
                    className="text-[11px] font-medium text-indigo-500 hover:text-indigo-600 hover:underline mb-1 inline-block">
                    👤 成员 →
                  </a>
                )}
                <h3 className="text-sm font-bold text-gray-900 leading-snug">
                  {grain.sceneDescription || '未命名场景'}
                </h3>
                <div className="flex flex-wrap gap-1.5 mt-2">
                  {grain.qualityScore != null && (
                    <span className="text-[10px] font-medium px-2 py-0.5 rounded-md bg-amber-50 text-amber-700">
                      ⭐ {grain.qualityScore}/5
                    </span>
                  )}
                  <span className={`text-[10px] font-medium px-2 py-0.5 rounded-md ${level.cls}`}>
                    {level.icon} {level.label}
                  </span>
                  {grain.difficultyLevel && (
                    <span className="text-[10px] font-medium px-2 py-0.5 rounded-md bg-purple-50 text-purple-700">
                      {grain.difficultyLevel === 'hard' ? '进阶' : grain.difficultyLevel === 'medium' ? '中级' : '基础'}
                    </span>
                  )}
                </div>
              </div>

              {/* 销冠思路 */}
              {grain.expertThought && (
                <div className="px-4 py-3">
                  <div className="flex items-center gap-1.5 mb-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-indigo-500 flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-indigo-600 uppercase tracking-wide">销冠怎么想</span>
                  </div>
                  <p className="text-[13px] text-gray-700 leading-relaxed m-0">
                    {grain.expertThought}
                  </p>
                </div>
              )}

              {/* 标准话术 */}
              {grain.standardScript && (
                <div className="px-4 py-3 border-t border-gray-50">
                  <div className="flex items-center gap-1.5 mb-2">
                    <span className="w-1.5 h-1.5 rounded-full bg-amber-400 flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-amber-600 uppercase tracking-wide">可以这样说</span>
                  </div>
                  <div className="relative bg-amber-50/60 rounded-xl px-3.5 py-3">
                    <span className="absolute left-2.5 top-1.5 text-lg text-amber-300 font-serif leading-none">"</span>
                    <p className="text-[13px] text-amber-900 leading-relaxed italic m-0 pl-2.5">
                      {grain.standardScript}
                    </p>
                    <div className="flex justify-end mt-2">
                      <button
                        onClick={() => handleCopy(grain.standardScript!, grain.grainId)}
                        className={`text-[11px] font-medium px-2.5 py-1 rounded-md transition-colors ${
                          copied[grain.grainId]
                            ? 'bg-green-100 text-green-700'
                            : 'bg-amber-100 text-amber-700 hover:bg-amber-200'
                        }`}
                      >
                        {copied[grain.grainId] ? '✓ 已复制' : '📋 复制话术'}
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* 常见误区 */}
              {grain.commonMistakes && (
                <div className="px-4 py-3 border-t border-gray-50">
                  <div className="flex items-center gap-1.5 mb-1.5">
                    <span className="w-1.5 h-1.5 rounded-full bg-red-400 flex-shrink-0" />
                    <span className="text-[11px] font-semibold text-red-500 uppercase tracking-wide">新手容易踩的坑</span>
                  </div>
                  <div className="bg-red-50/60 rounded-xl px-3.5 py-3">
                    <p className="text-[12px] text-red-800 leading-relaxed m-0">
                      {grain.commonMistakes}
                    </p>
                  </div>
                </div>
              )}

              {/* 原始对话 */}
              {grain.sourceSnippet && (
                <div className="px-4 py-2.5 border-t border-gray-50">
                  <button
                    onClick={() => setExpanded(prev => ({ ...prev, [grain.grainId]: !prev[grain.grainId] }))}
                    className="text-[11px] text-indigo-500 hover:text-indigo-600 font-medium bg-transparent border-none cursor-pointer p-0"
                  >
                    {expanded[grain.grainId] ? '▾ 收起' : '▸ 展开'}原始对话片段
                  </button>
                  {expanded[grain.grainId] && (
                    <div className="mt-2 p-3 rounded-lg bg-gray-50 text-[11px] text-gray-600 whitespace-pre-wrap leading-relaxed border border-gray-100">
                      {grain.sourceSnippet}
                    </div>
                  )}
                </div>
              )}

              {/* Source Footer */}
              <div className="px-4 py-2.5 bg-gray-50/50 border-t border-gray-50 flex items-center gap-2">
                <span className="text-xs">📄</span>
                <span className="text-[11px] text-gray-500 flex-1 truncate">
                  {grain.sourceName || '未知来源'}
                  {grain.reportTitle && <span className="text-gray-400"> · {grain.reportTitle}</span>}
                </span>
                {skillId && (
                  <button
                    onClick={() => { setReportTarget(grain); setReportReason(''); setReportSuggestion(''); setReportDone(false); }}
                    className="text-[10px] text-red-500 hover:text-red-600 font-medium bg-transparent border-none cursor-pointer whitespace-nowrap"
                  >
                    这条有误？
                  </button>
                )}
                {grain.reportId && (
                  <button
                    onClick={() => {
                      if (typeof window !== 'undefined') {
                        window.open(`/report/${grain.reportId}`, '_blank');
                      }
                    }}
                    className="text-[10px] text-indigo-500 hover:text-indigo-600 font-medium bg-transparent border-none cursor-pointer whitespace-nowrap"
                  >
                    查看报告 →
                  </button>
                )}
              </div>

            </div>
          )})}
        </div>
      </div>

      {reportTarget && (
        <div className="fixed inset-0 z-[120] flex items-center justify-center bg-black/40 px-4"
          onClick={() => setReportTarget(null)}>
          <div className="w-full max-w-md rounded-2xl bg-white p-5 shadow-xl" onClick={e => e.stopPropagation()}>
            <h3 className="text-base font-bold text-gray-900">报告经验有误</h3>
            <p className="mt-1 text-xs text-gray-500">
              提交后由管理员审核，通过前不会改动这条经验。
            </p>
            <label className="mt-4 block text-xs font-medium text-gray-600">问题原因（必填）</label>
            <textarea value={reportReason} onChange={e => setReportReason(e.target.value)}
              rows={3} placeholder="例如：话术已过期 / 场景描述不准确 / 造成了误导"
              className="mt-1 w-full resize-y rounded-lg border border-gray-200 px-3 py-2 text-sm outline-none focus:border-indigo-400" />
            <label className="mt-3 block text-xs font-medium text-gray-600">建议改成什么（选填）</label>
            <textarea value={reportSuggestion} onChange={e => setReportSuggestion(e.target.value)}
              rows={3} placeholder="可直接写建议的专家思考/做法"
              className="mt-1 w-full resize-y rounded-lg border border-gray-200 px-3 py-2 text-sm outline-none focus:border-indigo-400" />
            {reportDone && <p className="mt-3 text-sm text-green-600">已提交，等待管理员审核</p>}
            <div className="mt-4 flex justify-end gap-2">
              <button onClick={() => setReportTarget(null)}
                className="rounded-lg border border-gray-200 px-4 py-1.5 text-sm text-gray-600">取消</button>
              <button onClick={submitReport} disabled={!reportReason.trim() || reportSubmitting || reportDone}
                className="rounded-lg bg-indigo-600 px-4 py-1.5 text-sm text-white disabled:opacity-40">
                {reportSubmitting ? '提交中…' : '提交建议'}
              </button>
            </div>
          </div>
        </div>
      )}

      <style>{`
        @keyframes slideInRight {
          from { transform: translateX(100%); }
          to { transform: translateX(0); }
        }
      `}</style>
    </>
  );
}
