'use client';

import React from 'react';
import { useImChannels } from './useImChannels';

/** 渠道类型定义 */
const CHANNEL_TYPES = [
  { value: 'feishu', label: '飞书', icon: '🕊️' },
  { value: 'wecom', label: '企业微信', icon: '💼' },
  { value: 'wechat', label: '微信', icon: '💬' },
  { value: 'dingtalk', label: '钉钉', icon: '📌' },
];

export default function AdminImPage() {
  const h = useImChannels();

  return (
    <div>
      {/* 头部 */}
      <div className="mb-6 flex items-center justify-between">
        <h2 className="text-xl font-bold text-foreground">IM渠道配置</h2>
        <button
          type="button"
          onClick={h.openCreate}
          className="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-primary-hover"
        >
          + 新增渠道
        </button>
      </div>

      {/* 渠道列表 */}
      <div className="space-y-4">
        {h.channels.map((ch) => {
          const typeInfo = CHANNEL_TYPES.find((t) => t.value === ch.channelType);
          return (
            <div
              key={ch.id}
              className="rounded-xl bg-surface-2 p-5 shadow-md"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-3">
                  {/* 图标 */}
                  <span className="text-2xl">{typeInfo?.icon || '📡'}</span>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="text-base font-semibold text-foreground">
                        {typeInfo?.label || ch.channelType}
                      </h3>
                      {/* 开关 */}
                      <button
                        type="button"
                        onClick={() => h.toggleEnabled(ch.id, ch.enabled, ch.config, ch.channelType)}
                        className={`relative h-6 w-11 rounded-full transition-colors ${
                          ch.enabled ? 'bg-success' : 'bg-border-strong'
                        }`}
                      >
                        <span
                          className={`absolute top-0.5 h-5 w-5 rounded-full bg-surface-2 shadow transition-transform ${
                            ch.enabled ? 'left-[22px]' : 'left-0.5'
                          }`}
                        />
                      </button>
                    </div>
                    <p className="mt-1 text-sm text-muted-foreground">
                      App ID: {(ch.config as any)?.appId || '—'}
                    </p>
                    {ch.linkedSkills.length > 0 && (
                      <div className="mt-2 flex flex-wrap gap-1">
                        {ch.linkedSkills.map((skill) => (
                          <span
                            key={skill}
                            className="rounded-full bg-primary-light px-2 py-0.5 text-xs text-primary"
                          >
                            {skill}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                </div>

                {/* 操作按钮 */}
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => h.handleTest(ch.id)}
                    disabled={h.testingId === ch.id}
                    className="rounded-lg px-3 py-1.5 text-xs text-primary transition-colors hover:bg-primary-light"
                  >
                    {h.testingId === ch.id ? '测试中...' : '测试连接'}
                  </button>
                  <button
                    type="button"
                    onClick={() => h.openEdit(ch)}
                    className="rounded-lg px-3 py-1.5 text-xs text-primary transition-colors hover:bg-primary-light"
                  >
                    编辑
                  </button>
                  <button
                    type="button"
                    onClick={() => h.handleDelete(ch.id)}
                    className="rounded-lg px-3 py-1.5 text-xs text-danger transition-colors hover:bg-danger-bg"
                  >
                    删除
                  </button>
                </div>
              </div>
            </div>
          );
        })}

        {h.channels.length === 0 && (
          <div className="py-12 text-center">
            <p className="text-sm text-muted-foreground-2">暂无IM渠道，点击"新增渠道"开始配置</p>
          </div>
        )}
      </div>

      {/* 配置表单弹窗 */}
      {h.showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
          <div className="mx-4 w-full max-w-lg rounded-2xl bg-surface-2 p-6 shadow-xl">
            <h3 className="mb-5 text-lg font-bold text-foreground">
              {h.editingId ? '编辑IM渠道' : '新增IM渠道'}
            </h3>

            {/* 渠道类型 */}
            <label className="mb-3 block">
              <span className="text-sm font-medium text-foreground">渠道类型</span>
              <select
                value={h.form.type}
                onChange={(e) => h.setForm(f => ({ ...f, type: e.target.value }))}
                className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm"
              >
                {CHANNEL_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>{t.icon} {t.label}</option>
                ))}
              </select>
            </label>

            {/* 启用开关 */}
            <label className="mb-3 flex items-center gap-3">
              <span className="text-sm font-medium text-foreground">启用</span>
              <button
                type="button"
                onClick={() => h.setForm(f => ({ ...f, enabled: !f.enabled }))}
                className={`relative h-6 w-11 rounded-full transition-colors ${
                  h.form.enabled ? 'bg-success' : 'bg-border-strong'
                }`}
              >
                <span
                  className={`absolute top-0.5 h-5 w-5 rounded-full bg-surface-2 shadow transition-transform ${
                    h.form.enabled ? 'left-[22px]' : 'left-0.5'
                  }`}
                />
              </button>
            </label>

            {/* App ID */}
            <label className="mb-3 block">
              <span className="text-sm font-medium text-foreground">App ID</span>
              <input
                type="text"
                value={h.form.appId}
                onChange={(e) => h.setForm(f => ({ ...f, appId: e.target.value }))}
                placeholder="cli_xxxxxxxxxxxxx"
                className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground"
              />
            </label>

            {/* App Secret */}
            <label className="mb-3 block">
              <span className="text-sm font-medium text-foreground">App Secret</span>
              <input
                type="password"
                autoComplete="off"
                value={h.form.appSecret}
                onChange={(e) => h.setForm(f => ({ ...f, appSecret: e.target.value }))}
                placeholder="••••••••"
                className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground"
              />
            </label>

            {/* Webhook URL */}
            <label className="mb-3 block">
              <span className="text-sm font-medium text-foreground">Webhook URL</span>
              <input
                type="text"
                value={h.form.webhookUrl}
                onChange={(e) => h.setForm(f => ({ ...f, webhookUrl: e.target.value }))}
                placeholder="https://open.feishu.cn/open-apis/..."
                className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground"
              />
            </label>

            {/* 关联分身 */}
            <label className="mb-5 block">
              <span className="text-sm font-medium text-foreground">关联分身 Skill ID（逗号分隔）</span>
              <input
                type="text"
                value={h.form.skills}
                onChange={(e) => h.setForm(f => ({ ...f, skills: e.target.value }))}
                placeholder="skill-001, skill-002"
                className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground"
              />
            </label>

            {/* 按钮 */}
            <div className="flex justify-end gap-3">
              <button
                type="button"
                onClick={() => h.setShowForm(false)}
                className="rounded-lg px-4 py-2 text-sm text-muted-foreground"
              >
                取消
              </button>
              <button
                type="button"
                onClick={h.handleSave}
                className="rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white"
              >
                {h.editingId ? '保存修改' : '创建渠道'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
