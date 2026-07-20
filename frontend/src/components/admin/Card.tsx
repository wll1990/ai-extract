'use client';

import React from 'react';

interface CardProps {
  /** 卡片标题（可选） */
  title?: string;
  /** 标题右侧操作区 */
  action?: React.ReactNode;
  /** 无 padding 模式（图表/表格已自带内边距时用） */
  flush?: boolean;
  className?: string;
  children: React.ReactNode;
}

/**
 * 统一白卡容器 —— 对标 Datadog 卡片体系。
 *
 * 纯白底 + 12px 圆角 + 1px 边框 + 微阴影。
 * 标题行：蓝色前缀短线 + 14px semibold uppercase + letter-spacing。
 */
export function Card({ title, action, flush = false, className = '', children }: CardProps) {
  return (
    <div
      className={`rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_2px_rgba(15,23,42,0.06)] transition-shadow duration-150 hover:shadow-[0_8px_20px_rgba(15,23,42,0.08)] ${className}`}
    >
      {/* 标题行 */}
      {title && (
        <div className="flex items-center justify-between px-6 pt-5 pb-0">
          <h3
            className="text-[14px] font-semibold uppercase tracking-[0.06em] flex items-center gap-2"
            style={{ color: '#2563EB' }}
          >
            <span
              className="inline-block w-[18px] h-[2px] rounded-[1px] flex-shrink-0"
              style={{ background: '#2563EB' }}
            />
            {title}
          </h3>
          {action && <div className="text-xs">{action}</div>}
        </div>
      )}

      {/* 内容区 */}
      <div className={flush ? '' : 'p-6 pt-4'}>
        {children}
      </div>
    </div>
  );
}
