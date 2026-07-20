'use client';

import Link from 'next/link';
import { useExtraction } from '@/hooks/use-extraction';
import { ChatWindow } from '@/components/chat/chat-window';
import { K2JEditor } from '@/components/extract/k2j-editor';
import { OutputSelector } from '@/components/extract/output-selector';
import { StepIndicator } from '@/components/extract/step-indicator';
import { ArrowLeft, LogIn, Sparkles } from 'lucide-react';
import { MindsmithTopBar } from '@/components/brand/mindsmith-shell';
import { useMindsmithTheme } from '@/components/brand/mindsmith-theme';
import { authStore } from '@/lib/auth';

export default function ExtractPage() {
  const { theme, setTheme } = useMindsmithTheme('dark');
  const currentUser = authStore.getCurrentUser();
  const {
    currentStep,
    messages,
    isLoading,
    k2jData,
    selectedOutput,
    generatedContent,
    generatedMetadata,
    sendMessage,
    updateK2JData,
    selectOutput,
    generateOutput,
    goToStep,
  } = useExtraction();

  return (
    <div className="min-h-screen bg-[radial-gradient(circle_at_top_right,rgba(37,99,235,0.12),transparent_24%),linear-gradient(180deg,rgba(248,250,252,0.88),rgba(255,255,255,1))] px-4 py-4 sm:px-6 sm:py-6">
      <div className="mx-auto flex min-h-[calc(100vh-2rem)] max-w-7xl flex-col gap-6">
        <MindsmithTopBar
          theme={theme}
          setTheme={setTheme}
          backHref="/"
          backLabel="返回首页"
          rightSlot={
            currentUser ? (
              <div className="text-sm text-muted-foreground">
                当前用户：<span className="font-medium text-foreground">{currentUser.name}</span>
              </div>
            ) : (
              <div className="flex items-center gap-3">
                <span className="hidden text-sm text-muted-foreground sm:inline">登录后可保存进度并进入团队空间</span>
                <Link href="/auth/login" className="inline-flex items-center gap-2 text-sm text-primary hover:text-primary/80">
                  <LogIn className="size-4" />
                  登录
                </Link>
              </div>
            )
          }
        />

        <div className="flex-1 overflow-hidden rounded-[32px] border border-border/70 bg-card/90 shadow-[0_24px_80px_rgba(15,23,42,0.10)]">
          <header className="border-b border-border/50 bg-card/50 backdrop-blur-sm">
            <div className="max-w-6xl mx-auto px-6 py-4 flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl gradient-primary flex items-center justify-center">
                  <Sparkles className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-semibold">MindSmith 知识创作</h1>
                  <p className="text-xs text-muted-foreground">像聊天一样讲故事，AI 自动提炼成可部署的 K2J Skill 与专家知识资产</p>
                </div>
              </div>
              <div className="flex items-center justify-between gap-4">
                <div className="hidden rounded-full border border-primary/15 bg-primary/8 px-3 py-1 text-xs font-medium text-primary sm:inline-flex">
                  登录 / 选择工作模式 / 萃取 / 输出 已整合到同一路径
                </div>
                <StepIndicator currentStep={currentStep} onStepClick={goToStep} />
              </div>
            </div>
          </header>

          <main className="flex-1 overflow-hidden">
            {currentStep === 1 && (
              <ChatWindow
                messages={messages}
                isLoading={isLoading}
                onSendMessage={sendMessage}
                onComplete={() => goToStep(2)}
              />
            )}

            {currentStep === 2 && k2jData && (
              <K2JEditor
                data={k2jData}
                onChange={updateK2JData}
                onConfirm={() => goToStep(3)}
                onBack={() => goToStep(1)}
              />
            )}

            {currentStep === 3 && (
              <OutputSelector
                selected={selectedOutput}
                onSelect={selectOutput}
                onGenerate={generateOutput}
                k2jData={k2jData}
                generatedContent={generatedContent}
                generatedMetadata={generatedMetadata}
                isLoading={isLoading}
              />
            )}
          </main>

          <footer className="border-t border-border/50 bg-card/30 py-3 px-6">
            <div className="max-w-6xl mx-auto flex flex-col gap-2 text-xs text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
              <span>按 Enter 发送，Shift + Enter 换行</span>
              <div className="flex items-center gap-4">
                <Link href="/auth/register" className="inline-flex items-center gap-1 hover:text-foreground">
                  <ArrowLeft className="size-3 rotate-180" />
                  注册账号并保存成果
                </Link>
                <span>MindSmith AI 正在为你服务</span>
              </div>
            </div>
          </footer>
        </div>
      </div>
    </div>
  );
}
