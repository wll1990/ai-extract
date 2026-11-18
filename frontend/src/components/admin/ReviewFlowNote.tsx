'use client';

import React from 'react';

type LibraryKind = 'knowledge' | 'case';

/**
 * 审核规则说明：把「提交 / 通过 / 生效 / 本库门槛」拆成四个独立区块，
 * 避免知识库和案例库用同一段笼统文案，也避免首屏被一大段文字占满。
 */
export function ReviewFlowNote({
  className = '',
  collapsible = false,
  defaultOpen = false,
  kind,
}: {
  className?: string;
  collapsible?: boolean;
  defaultOpen?: boolean;
  kind?: LibraryKind;
}) {
  const extraRule = kind === 'case'
    ? '结果必须是赢单/丢单/搁置；客户画像、核心异议、关键动作/话术需达标；失效时间可选。公共案例仅平台超管可写。'
    : kind === 'knowledge'
      ? '失效时间必填；标题 4–60 字、正文达标、分类合法。公共知识仅平台超管可写。'
      : '按内容所属库的准入标准校验；公共内容仅平台超管可写。';

  const rules = [
    { label: '提交', text: '员工只能提交（KNOWLEDGE_SUBMIT）；管理员/运营可新增、上传或让 AI 生成草稿。' },
    { label: '通过', text: '企业管理员或平台超管（KNOWLEDGE_MANAGE）点「通过」。没有指定审核人，当前允许通过自己提交的内容。' },
    { label: '生效', text: '审核通过 + 在有效期内 + 索引完成，分身才会引用；审核通过不等于立刻可检索。' },
    { label: kind === 'case' ? '案例门槛' : kind === 'knowledge' ? '知识门槛' : '本库门槛', text: extraRule },
  ];

  const body = (
    <>
      <div className="grid overflow-hidden rounded-lg border border-border sm:grid-cols-2 lg:grid-cols-4">
        {rules.map((rule, index) => (
          <div
            key={rule.label}
            className={`px-3 py-2.5 ${
              index > 0 ? 'border-t border-border lg:border-l lg:border-t-0' : ''
            } ${index === 2 ? 'sm:border-t lg:border-t-0' : ''}`}
          >
            <p className="text-[11px] font-semibold text-foreground">{rule.label}</p>
            <p className="mt-1 text-[11px] leading-5 text-muted-foreground">{rule.text}</p>
          </div>
        ))}
      </div>
      <p className="mt-2 rounded-lg bg-surface-2 px-3 py-2 text-[11px] leading-5 text-muted-foreground">
        AI 待审只是 AI 生成内容的筛选视图，和知识库/案例库的「待我审」是同一批记录；
        在这里通过 = 在知识库/案例库通过（考题需要 EVAL_MANAGE）。
      </p>
    </>
  );

  if (collapsible) {
    return (
      <details className={`group rounded-xl border border-border bg-surface ${className}`} open={defaultOpen || undefined}>
        <summary className="flex cursor-pointer list-none flex-wrap items-center justify-between gap-2 px-4 py-2.5">
          <span className="text-[13px] font-semibold text-foreground">
            审核规则{kind === 'case' ? '（案例库）' : kind === 'knowledge' ? '（知识库）' : ''}
          </span>
          <span className="text-[11px] text-muted-foreground">
            <span className="group-open:hidden">展开</span>
            <span className="hidden group-open:inline">收起</span>
          </span>
        </summary>
        <div className="border-t border-border px-4 py-3">{body}</div>
      </details>
    );
  }

  return (
    <section className={`rounded-xl border border-border bg-surface px-4 py-3 ${className}`}>
      <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h2 className="text-[13px] font-semibold text-foreground">
          审核规则{kind === 'case' ? '（案例库）' : kind === 'knowledge' ? '（知识库）' : ''}
        </h2>
        <span className="text-[11px] text-muted-foreground">没有「指定审核人」，能进管理页就能审</span>
      </div>
      {body}
    </section>
  );
}
