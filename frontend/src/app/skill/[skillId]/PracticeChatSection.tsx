'use client';

import React, { useState, useRef, useEffect } from 'react';
import { SkillChatView } from '@/components/skill/SkillChatView';
import { PracticeView } from '@/components/skill/PracticeView';
import { ChatHero } from '@/components/chat/ChatHero';
import EndConfirmModal from '@/components/skill/EndConfirmModal';
import { usePracticeFlow } from './hooks/usePracticeFlow';
import type { PracticeEval } from './hooks/usePracticeFlow';

interface Props {
  skillId: string;
  ownerName: string;
  initialSceneTag?: string;
  setChatMode: (m: 'qa' | 'talk' | 'practice') => void;
  abortRef: React.MutableRefObject<AbortController | null>;
  /** C 端分享页显式 Bearer（内部页不传 = 零行为变化） */
  authToken?: string;
  /** 游客免费额度用尽回调（分享页弹注册抽屉） */
  onLimit?: (info: { used: number; limit: number; pendingText: string }) => void;
}

export default function PracticeChatSection({ skillId, ownerName, initialSceneTag, setChatMode, abortRef, authToken, onLimit }: Props) {
  // Practice 自有输入状态，不与 QA 共享
  const [practiceInput, setPracticeInput] = useState('');

  const practice = usePracticeFlow({ skillId, setChatMode, abortRef, authToken, onLimit });

  // 有预设场景标签时自动启动对练
  const startedRef = useRef(false);
  useEffect(() => {
    if (initialSceneTag && !startedRef.current && !practice.practiceData) {
      startedRef.current = true;
      practice.handlePracticeStart(initialSceneTag);
    }
  }, [initialSceneTag, practice.practiceData, practice.handlePracticeStart]);

  // 返回问答时重置
  const handleBackToQa = () => {
    setChatMode('qa');
    practice.onResetPractice();
  };

  // 包装 onSend：传递 practiceInput，清空输入
  const handleSend = () => {
    if (!practiceInput.trim() || practice.isStreaming) return;
    const text = practiceInput;
    setPracticeInput('');
    practice.handlePracticeSend(text);
  };

  // 重试后聚焦输入框
  useEffect(() => {
    if (!practice.isStreaming) {
      setTimeout(() => {
        const ta = document.querySelector('textarea');
        if (ta) ta.focus();
      }, 100);
    }
  }, [practice.practiceMessages.length]);

  if (practice.practicePhase === 'evaluate') {
    return (
      <PracticeView
        phase="evaluate"
        evaluation={practice.practiceEval as PracticeEval}
        messages={practice.practiceMessages}
        isStreaming={false}
        showHint={false}
        onToggleHint={() => {}}
        onSend={() => {}}
        onEnd={() => {}}
        onRetry={handleBackToQa}
        onBackToQa={handleBackToQa}
        inputValue=""
        onInputChange={() => {}}
      />
    );
  }

  return (
    <>
      <SkillChatView
        inputValue={practiceInput}
        onInputChange={setPracticeInput}
        onSend={handleSend}
        isStreaming={practice.isStreaming}
        placeholder={practice.practiceData ? '输入你的回应...' : '描述你想练习的场景...'}
        disabled={!practice.practiceData}
      >
        <div className="mx-auto max-w-[720px]">
          {/* 场景角色 — ChatHero 卡片 */}
          {practice.practiceData && (
            <div className="mb-4">
              <ChatHero
                name={ownerName || 'AI 对练伙伴'}
                intro={(practice.practiceData.scene as any)?.title || (practice.practiceData.scene as any)?.label || '模拟真实销售场景，帮助你提升实战能力'}
                privacyNote="对练内容仅用于提升你的技能，不会被保存为分身经验。"
              />
            </div>
          )}
          <PracticeView
            phase="active"
            currentScene={practice.practiceData || undefined}
            messages={practice.practiceMessages}
            isStreaming={practice.isStreaming}
            angleInfo={practice.practiceAngles}
            hint={practice.selectedSceneLabel ? undefined : '专注倾听客户真正的顾虑，不要急着推销。用提问引导对话。'}
            showHint={practice.showHint}
            onToggleHint={() => practice.setShowHint(!practice.showHint)}
            onSend={handleSend}
            onEnd={practice.handleEndPractice}
            onRetry={() => {
              practice.retryPractice();
              setPracticeInput('');
            }}
            onAdvanceRound={practice.advanceRound}
            onBackToQa={handleBackToQa}
            inputValue={practiceInput}
            onInputChange={setPracticeInput}
          />
        </div>
      </SkillChatView>

      {practice.showEndConfirm && (
        <EndConfirmModal
          onCancel={() => practice.setShowEndConfirm(false)}
          onConfirm={practice.confirmEndPractice}
        />
      )}
    </>
  );
}
