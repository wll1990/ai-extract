'use client';

import { PHASE_LABELS } from '@/lib/constants';
import React, { useState } from 'react';

/** 恢复弹窗 Props */
export interface ResumeModalProps {
  open: boolean;
  topic: string;
  currentPhase: string;
  lastActiveAt?: string;
  onResume: () => void;
  onRestart: () => void;
  onClose: () => void;
}

/**
 * 中断恢复弹窗组件
 *
 * 在用户有未完成的访谈时显示。
 * 展示主题、当前进度、上次活跃时间。
 * 两个操作：[继续上次访谈] [重新开始]。
 * 重新开始需要二次确认。
 */
export const ResumeModal: React.FC<ResumeModalProps> = ({
  open,
  topic,
  currentPhase,
  lastActiveAt,
  onResume,
  onRestart,
  onClose,
}) => {
  const [confirmRestart, setConfirmRestart] = useState(false);

  if (!open) return null;

  const phaseMap = PHASE_LABELS;

  /**
   * 处理重新开始点击
   */
  const handleRestartClick = () => {
    if (!confirmRestart) {
      setConfirmRestart(true);
    } else {
      onRestart();
      setConfirmRestart(false);
    }
  };

  /**
   * 处理关闭
   */
  const handleClose = () => {
    setConfirmRestart(false);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-md rounded-2xl bg-surface-2 p-6 shadow-xl">
        {/* 图标 */}
        <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-warning-bg">
          <svg className="h-7 w-7 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6h4.5m4.5 0a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>

        {/* 标题 */}
        <h2 className="mb-2 text-center text-xl font-bold text-foreground">
          你有未完成的访谈
        </h2>

        {/* 信息 */}
        <div className="mb-6 space-y-2 rounded-lg bg-surface p-4 text-sm">
          <div className="flex justify-between">
            <span className="text-muted-foreground">主题</span>
            <span className="font-medium text-foreground">{topic}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted-foreground">当前进度</span>
            <span className="font-medium text-primary">
              {phaseMap[currentPhase] || currentPhase}
            </span>
          </div>
          {lastActiveAt && (
            <div className="flex justify-between">
              <span className="text-muted-foreground">上次活跃</span>
              <span className="text-foreground">
                {formatDate(lastActiveAt)}
              </span>
            </div>
          )}
        </div>

        {/* 按钮 */}
        <div className="flex flex-col gap-3">
          <button
            type="button"
            onClick={onResume}
            className="w-full rounded-lg bg-primary py-3 text-sm font-semibold text-white transition-colors hover:bg-primary-hover"
          >
            继续上次访谈
          </button>

          {!confirmRestart ? (
            <button
              type="button"
              onClick={handleRestartClick}
              className="w-full rounded-lg bg-transparent py-3 text-sm text-muted-foreground transition-colors hover:bg-primary-light"
            >
              重新开始
            </button>
          ) : (
            <div className="rounded-lg bg-danger-bg p-3">
              <p className="mb-2 text-center text-sm text-danger">
                重新开始将丢失当前进度，确定吗？
              </p>
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={() => setConfirmRestart(false)}
                  className="flex-1 rounded-lg bg-primary-light py-2 text-sm text-muted-foreground transition-colors hover:bg-border"
                >
                  取消
                </button>
                <button
                  type="button"
                  onClick={handleRestartClick}
                  className="flex-1 rounded-lg bg-danger py-2 text-sm text-white transition-colors hover:bg-danger"
                >
                  确定重新开始
                </button>
              </div>
            </div>
          )}
        </div>

        {/* 底部关闭 */}
        <button
          type="button"
          onClick={handleClose}
          className="mt-4 w-full text-center text-sm text-muted-foreground-2 hover:text-muted-foreground"
        >
          关闭
        </button>
      </div>
    </div>
  );
};

/**
 * 格式化日期
 */
function formatDate(dateStr: string): string {
  try {
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN', {
      month: 'numeric',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return dateStr;
  }
}
