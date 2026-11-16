'use client';

import { useState } from 'react';
import { ArrowRight, CheckCircle } from '@phosphor-icons/react';
import { API_BASE } from '@/lib/api/client';
import { csrfHeader } from '@/lib/csrf';
import { track } from '@/lib/analytics';

interface FormState {
  companyName: string;
  contactName: string;
  role: string;
  teamSize: string;
  contact: string;
  message: string;
  website: string;
}

const EMPTY: FormState = {
  companyName: '', contactName: '', role: '', teamSize: '', contact: '', message: '', website: '',
};

export function DemoRequestForm() {
  const [form, setForm] = useState<FormState>(EMPTY);
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = (key: keyof FormState) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    setForm(prev => ({ ...prev, [key]: e.target.value }));
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (saving) return;
    setError(null);
    setSaving(true);
    try {
      const res = await fetch(`${API_BASE}/public/demo-request`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...csrfHeader() },
        body: JSON.stringify(form),
      });
      const body = await res.json().catch(() => null);
      if (!res.ok || body?.code !== 200) {
        throw new Error(body?.message || '提交失败，请稍后重试');
      }
      track('demo_request_submit', { team_size: form.teamSize || null, role: form.role || null });
      setDone(true);
      setForm(EMPTY);
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败，请稍后重试');
    } finally {
      setSaving(false);
    }
  };

  if (done) {
    return (
      <div className="demo-request-success">
        <CheckCircle weight="fill" />
        <h3>已收到，我们会在 1 个工作日内联系你。</h3>
        <p>我们会先确认你的团队规模和当前跟进流程，再约 20 分钟演示，不占用更多时间。</p>
        <button type="button" className="button button-secondary" onClick={() => setDone(false)}>
          再提交一条
        </button>
      </div>
    );
  }

  return (
    <form className="demo-request-form" onSubmit={submit}>
      <div className="form-row">
        <label>
          <span>公司名称 *</span>
          <input value={form.companyName} onChange={set('companyName')} maxLength={200} required />
        </label>
        <label>
          <span>你的称呼 *</span>
          <input value={form.contactName} onChange={set('contactName')} maxLength={100} required />
        </label>
      </div>
      <div className="form-row">
        <label>
          <span>角色</span>
          <input value={form.role} onChange={set('role')} placeholder="例如：销售负责人 / 培训负责人" maxLength={100} />
        </label>
        <label>
          <span>团队规模</span>
          <input value={form.teamSize} onChange={set('teamSize')} placeholder="例如：30 人" maxLength={50} />
        </label>
      </div>
      <label>
        <span>联系方式 *</span>
        <input value={form.contact} onChange={set('contact')} placeholder="手机号或微信" maxLength={200} required />
      </label>
      <label>
        <span>你现在最想解决的一个问题</span>
        <textarea value={form.message} onChange={set('message')} rows={3} maxLength={2000} />
      </label>

      {/* 蜜罐：正常用户不可见 */}
      <input
        tabIndex={-1}
        autoComplete="off"
        value={form.website}
        onChange={set('website')}
        style={{ position: 'absolute', left: '-9999px', width: 1, height: 1, opacity: 0 }}
        aria-hidden
      />

      {error && <p className="form-error">{error}</p>}

      <button type="submit" className="button button-primary" disabled={saving}>
        {saving ? '提交中…' : '预约 20 分钟演示'} <ArrowRight />
      </button>
      <p className="form-note">提交即表示同意我们就演示与你联系；不会用于其它用途。</p>
    </form>
  );
}
