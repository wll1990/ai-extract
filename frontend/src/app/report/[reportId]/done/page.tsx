'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { getReport, type ReportDetail } from '@/lib/api/report';

/** 生成阶段 */
type GenerationPhase = 'generating' | 'done' | 'failed';

/** 进度步骤 */
const PROGRESS_STEPS = [
  '分析对话内容...',
  '提取案例故事...',
  '梳理方法论步骤...',
  '挖掘决策点...',
  '提炼专家心法...',
  '生成最终报告...',
];

/**
 * B4 报告生成完成页
 *
 * 展示报告生成进度和完成结果。
 * 生成中：金色光环旋转+进度文字逐个亮起。
 * 完成：封面卡片+下载按钮+分身就绪卡片。
 */
export default function ReportDonePage() {
  const params = useParams();
  const router = useRouter();
  const reportId = params.reportId as string;

  const [phase, setPhase] = useState<GenerationPhase>('generating');
  const [report, setReport] = useState<ReportDetail | null>(null);
  const [activeStep, setActiveStep] = useState(0);
  const [error, setError] = useState<string | null>(null);

  /**
   * 轮询拉取报告（后端异步生成，轮询直到报告就绪）
   */
  useEffect(() => {
    if (!reportId) return;

    let cancelled = false;
    let pollTimer: ReturnType<typeof setTimeout>;
    let stepTimer: ReturnType<typeof setInterval>;
    let retries = 0;
    const MAX_RETRIES = 60; // 2分钟超时

    // 进度动画
    stepTimer = setInterval(() => {
      setActiveStep((prev) => {
        if (prev < PROGRESS_STEPS.length - 1) return prev + 1;
        return prev;
      });
    }, 2000);

    // 轮询拉取报告
    const pollReport = async () => {
      try {
        const data = await getReport(reportId);
        if (!cancelled) {
          setReport(data);
          setPhase('done');
          setActiveStep(PROGRESS_STEPS.length);
        }
      } catch {
        retries++;
        if (retries >= MAX_RETRIES) {
          if (!cancelled) {
            setPhase('failed');
            setError('报告生成超时，请稍后重试');
          }
          return;
        }
        if (!cancelled) {
          pollTimer = setTimeout(pollReport, 2000);
        }
      }
    };

    pollTimer = setTimeout(pollReport, 1000);

    return () => {
      cancelled = true;
      clearTimeout(pollTimer);
      clearInterval(stepTimer);
    };
  }, [reportId]);

  /**
   * 重试
   */
  const handleRetry = useCallback(() => {
    setPhase('generating');
    setActiveStep(0);
    setError(null);
    window.location.reload();
  }, []);

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="mx-4 w-full max-w-[560px]">

        {/* 生成中 */}
        {phase === 'generating' && (
          <div className="rounded-2xl bg-surface-2 p-10 text-center shadow-lg">
            {/* 旋转光环 */}
            <div className="relative mx-auto mb-8 h-24 w-24">
              <div className="absolute inset-0 animate-spin rounded-full border-4 border-muted-foreground-2 border-t-gold" />
              <div className="absolute inset-3 flex items-center justify-center rounded-full bg-warning-bg">
                <svg className="h-8 w-8 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
                </svg>
              </div>
            </div>

            <h2 className="mb-4 text-xl font-bold text-foreground">
              正在生成萃取报告
            </h2>

            {/* 进度文字 */}
            <div className="space-y-3">
              {PROGRESS_STEPS.map((step, index) => (
                <p
                  key={step}
                  className={`text-sm transition-all duration-500 ${
                    index <= activeStep
                      ? 'text-foreground opacity-100'
                      : 'text-muted-foreground-2 opacity-40'
                  }`}
                >
                  {index < activeStep && (
                    <span className="mr-2 text-success">✓</span>
                  )}
                  {index === activeStep && (
                    <span className="mr-2 inline-block h-3 w-3 animate-pulse rounded-full bg-primary" />
                  )}
                  {index > activeStep && (
                    <span className="mr-2 text-border-strong">○</span>
                  )}
                  {step}
                </p>
              ))}
            </div>
          </div>
        )}

        {/* 完成 */}
        {phase === 'done' && report && (
          <div className="space-y-6">
            {/* 祝贺 */}
            <div className="text-center">
              <span className="text-5xl">🎉</span>
              <h1 className="mt-3 text-[28px] font-bold text-foreground">
                萃取完成！
              </h1>
            </div>

            {/* 封面卡片 */}
            <div className="relative overflow-hidden rounded-2xl bg-surface-2 p-10 shadow-lg">
              {/* 装饰纹路 */}
              <div className="absolute -right-10 -top-10 h-40 w-40 rounded-full bg-warning-bg/40" />
              <div className="absolute -bottom-8 -left-8 h-32 w-32 rounded-full bg-primary-light" />

              <div className="relative">
                <h2 className="text-[32px] font-bold text-foreground leading-tight">
                  {report.title}
                </h2>
                <div className="my-4 h-[2px] w-16 bg-primary" />
                {report.subtitle && (
                  <p className="mb-6 text-lg text-muted-foreground">{report.subtitle}</p>
                )}
                <div className="flex items-center gap-4 text-sm text-muted-foreground">
                  {report.authorName && <span>{report.authorName}</span>}
                  {report.createdAt && (
                    <span>{new Date(report.createdAt).toLocaleDateString('zh-CN')}</span>
                  )}
                  <span className="flex items-center gap-1 text-primary">
                    ⭐ {report.rating}
                  </span>
                </div>
              </div>
            </div>

            {/* 查看报告 */}
            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => router.push(`/report/${reportId}`)}
                className="flex flex-col items-center gap-1 rounded-xl bg-primary-light px-4 py-4 text-sm text-foreground transition-colors hover:bg-border"
              >
                <svg className="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="#00B42A" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 6.042A8.967 8.967 0 006 3.75c-1.052 0-2.062.18-3 .512v14.25A8.987 8.987 0 016 18c2.305 0 4.408.867 6 2.292m0-14.25a8.966 8.966 0 016-2.292c1.052 0 2.062.18 3 .512v14.25A8.987 8.987 0 0018 18a8.967 8.967 0 00-6 2.292m0-14.25v14.25" />
                </svg>
                <span>查看报告</span>
              </button>
            </div>

            {/* AI分身就绪卡片 */}
            <div className="rounded-xl bg-surface-2 p-5 shadow-sm">
              <div className="flex items-start gap-4">
                <span className="text-[40px]">🤖</span>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">
                    AI分身已发布
                  </h3>
                  <p className="mt-1 text-sm text-muted-foreground">
                    你的经验已经被AI学习，现在团队可以直接向"你的分身"提问，
                    获取你的销售智慧。
                  </p>
                  <button
                    type="button"
                    onClick={() => report?.skillId ? router.push(`/admin/skills/${report.skillId}/audit`) : router.push('/admin/skills')}
                    className="mt-3 text-sm font-medium text-primary transition-colors hover:text-primary-hover"
                  >
                    进入审核 →
                  </button>
                </div>
              </div>
            </div>

            {/* 萃取师来源提示（如果有） */}
            {/* TODO: 从report中获取expertSkillUsed字段 */}
          </div>
        )}

        {/* 失败 */}
        {phase === 'failed' && (
          <div className="rounded-2xl bg-surface-2 p-10 text-center shadow-lg">
            <span className="text-5xl">😕</span>
            <h2 className="mt-4 text-xl font-bold text-foreground">
              报告生成失败
            </h2>
            <p className="mt-2 text-sm text-muted-foreground">
              {error || 'AI服务暂时不可用，请稍后重试'}
            </p>
            <button
              type="button"
              onClick={handleRetry}
              className="mt-6 rounded-lg bg-primary px-8 py-3 text-sm font-semibold text-white transition-colors hover:bg-primary-hover"
            >
              重新生成
            </button>
          </div>
        )}

        {/* 返回按钮 */}
        <div className="mt-6 text-center">
          <button
            type="button"
            onClick={() => router.push('/')}
            className="text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            返回我的空间
          </button>
        </div>
      </div>
    </div>
  );
}
