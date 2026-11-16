'use client';

import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import Link from 'next/link';
import {
  LeadContact,
  LeadContactRole,
  LeadDetail,
  addLeadNote,
  assignLead,
  deleteLeadContact,
  getLead,
  updateLeadStatus,
} from '@/lib/api/sales';
import { apiClient } from '@/lib/api/client';
import { StatusBadge } from '@/components/sales/StatusBadge';
import { LeadEditModal } from '@/components/sales/LeadEditModal';
import { LeadContactModal } from '@/components/sales/LeadContactModal';

const STATUS_ACTIONS: Record<string, Array<{ label: string; status: string }>> = {
  new: [
    { label: '开始跟进', status: 'following' },
    { label: '标记无效', status: 'invalid' },
  ],
  following: [
    { label: '标记成交', status: 'converted' },
    { label: '标记无效', status: 'invalid' },
  ],
  converted: [{ label: '标记无效', status: 'invalid' }],
  invalid: [{ label: '恢复为新线索', status: 'new' }],
};

const ROLE_LABEL: Record<LeadContactRole, string> = {
  decision_maker: '决策人',
  influencer: '影响人',
  user: '使用人',
  gatekeeper: '把关人',
  other: '其他',
};

const ACTIVITY_LABEL: Record<string, string> = {
  created: '创建线索',
  status_changed: '状态变更',
  field_updated: '更新资料',
  note: '备注',
  contact_added: '添加联系人',
  contact_updated: '更新联系人',
  contact_removed: '删除联系人',
  task_created: '创建任务',
  task_transferred: '转交任务',
  task_escalated: '升级任务',
  task_completed: '完成跟进',
  task_cancelled: '放弃跟进',
  owner_changed: '分配负责人',
};

export default function LeadDetailPage() {
  const params = useParams<{ id: string }>();
  const leadId = params.id;
  const [detail, setDetail] = useState<LeadDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [contactEditing, setContactEditing] = useState<LeadContact | null>(null);
  const [contactOpen, setContactOpen] = useState(false);
  const [note, setNote] = useState('');
  const [noteSaving, setNoteSaving] = useState(false);
  const [members, setMembers] = useState<Array<{ id: string; name: string }>>([]);

  useEffect(() => {
    // 负责人下拉：本企业启用成员（仅具备线索管理权限的角色可读，失败时静默）
    apiClient<Array<{ id: string; name: string }>>('/sales/leads/owners')
      .then(setMembers)
      .catch(() => setMembers([]));
  }, []);

  const assign = async (ownerId: string) => {
    if (!ownerId) return;
    try {
      await assignLead(leadId, ownerId);
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '分配失败');
    }
  };

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);
    try {
      setDetail(await getLead(leadId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      if (!silent) setLoading(false);
    }
  }, [leadId]);

  useEffect(() => {
    void load();
  }, [load]);

  const move = async (status: string) => {
    try {
      await updateLeadStatus(leadId, status);
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '状态更新失败');
    }
  };

  const removeContact = async (contact: LeadContact) => {
    if (!window.confirm(`确认删除联系人「${contact.name}」？`)) return;
    try {
      await deleteLeadContact(leadId, contact.id);
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    }
  };

  const saveNote = async () => {
    if (!note.trim()) return;
    setNoteSaving(true);
    try {
      await addLeadNote(leadId, note.trim());
      setNote('');
      await load(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '添加备注失败');
    } finally {
      setNoteSaving(false);
    }
  };

  if (loading) return <div className="p-6 text-sm text-gray-500">加载中…</div>;
  if (!detail) return <div className="p-6 text-sm text-red-600">{error || '线索不存在'}</div>;

  const s = detail.summary;
  return (
    <div className="mx-auto max-w-5xl p-6">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <Link href="/sales/leads" className="text-xs text-blue-600 hover:underline">
            ← 线索
          </Link>
          <div className="mt-1 flex items-center gap-2">
            <h1 className="text-xl font-semibold text-gray-900">{s.name}</h1>
            <StatusBadge status={s.status} />
          </div>
          <p className="mt-1 flex items-center gap-2 text-sm text-gray-500">
            <span>负责人：{s.ownerName || '—'}</span>
            {members.length > 0 && (
              <select
                defaultValue=""
                onChange={e => { assign(e.target.value); e.target.value = ''; }}
                className="rounded border border-gray-300 px-2 py-1 text-xs"
                title="分配负责人"
              >
                <option value="">分配…</option>
                {members.map(m => (
                  <option key={m.id} value={m.id}>{m.name}</option>
                ))}
              </select>
            )}
            {detail.sourceReview && (
              <>
                {' · 来源：'}
                <Link
                  href={`/sales/reviews/${detail.sourceReview.id}`}
                  className="text-blue-600 hover:underline"
                >
                  来自复盘
                </Link>
              </>
            )}
            {!detail.sourceReview && s.sourceReviewId && ' · 来源复盘已删除'}
            {detail.nextFollowUpAt &&
              ` · 下次跟进：${new Date(detail.nextFollowUpAt).toLocaleString()}`}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {(STATUS_ACTIONS[s.status] || []).map(a => (
            <button
              key={a.status}
              type="button"
              onClick={() => move(a.status)}
              className="rounded border border-gray-300 px-3 py-2 text-sm"
            >
              {a.label}
            </button>
          ))}
          <button
            type="button"
            onClick={() => setEditing(true)}
            className="rounded border border-blue-300 px-3 py-2 text-sm text-blue-700"
          >
            编辑资料
          </button>
        </div>
      </div>

      {error && (
        <div className="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section className="rounded border border-gray-200 p-4">
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-sm font-medium text-gray-900">联系人</h2>
            <button
              type="button"
              onClick={() => setContactOpen(true)}
              className="text-xs text-blue-600 hover:underline"
            >
              + 添加联系人
            </button>
          </div>
          {detail.contacts.length === 0 && (
            <p className="text-sm text-gray-500">还没有联系人，先加一个关键人。</p>
          )}
          <ul className="space-y-2">
            {detail.contacts.map(c => (
              <li key={c.id} className="rounded bg-gray-50 p-3 text-sm">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-gray-900">{c.name}</span>
                    {c.primary && (
                      <span className="rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-700">
                        主联系人
                      </span>
                    )}
                  </div>
                  <div className="flex gap-2 text-xs">
                    <button
                      type="button"
                      onClick={() => setContactEditing(c)}
                      className="text-blue-600 hover:underline"
                    >
                      编辑
                    </button>
                    <button
                      type="button"
                      onClick={() => removeContact(c)}
                      className="text-red-600 hover:underline"
                    >
                      删除
                    </button>
                  </div>
                </div>
                <p className="mt-1 text-xs text-gray-500">
                  {[ROLE_LABEL[c.role || 'other'], c.phone].filter(Boolean).join(' · ')}
                </p>
                {c.note && <p className="mt-1 text-xs text-gray-600">{c.note}</p>}
              </li>
            ))}
          </ul>
        </section>

        <section className="rounded border border-gray-200 p-4">
          <h2 className="mb-2 text-sm font-medium text-gray-900">活动时间线</h2>
          {detail.activities.length === 0 && (
            <p className="text-sm text-gray-500">还没有活动记录。</p>
          )}
          <ul className="space-y-3">
            {detail.activities.map(a => (
              <li key={a.id} className="border-l-2 border-gray-200 pl-3">
                <p className="text-sm text-gray-900">
                  {ACTIVITY_LABEL[a.type] || a.type}
                  {a.content ? `：${a.content}` : ''}
                </p>
                <p className="mt-0.5 text-xs text-gray-500">
                  {a.actorName || '系统'} · {new Date(a.occurredAt).toLocaleString()}
                </p>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section className="rounded border border-gray-200 p-4">
          <h2 className="mb-2 text-sm font-medium text-gray-900">需求摘要</h2>
          <p className="whitespace-pre-wrap text-sm text-gray-800">
            {detail.needSummary || '未填写'}
          </p>
          <div className="mt-4">
            <h3 className="mb-2 text-sm font-medium text-gray-900">添加备注</h3>
            <textarea
              rows={3}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm"
              placeholder="记录这次沟通的关键信息"
              value={note}
              onChange={e => setNote(e.target.value)}
            />
            <div className="mt-2 text-right">
              <button
                type="button"
                onClick={saveNote}
                disabled={noteSaving || !note.trim()}
                className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white disabled:opacity-50"
              >
                {noteSaving ? '保存中…' : '添加备注'}
              </button>
            </div>
          </div>
        </section>

        <section className="rounded border border-gray-200 p-4">
          <h2 className="mb-2 text-sm font-medium text-gray-900">关联任务（最近 50 条）</h2>
          {detail.tasks.length === 0 && <p className="text-sm text-gray-500">还没有任务。</p>}
          <ul className="space-y-2">
            {detail.tasks.map(t => (
              <li key={t.id} className="flex items-center justify-between text-sm">
                <span className={t.status === 'done' ? 'text-gray-400 line-through' : 'text-gray-900'}>
                  {t.title}
                </span>
                <StatusBadge status={t.status} />
              </li>
            ))}
          </ul>
          <div className="mt-3">
            <Link href="/sales/tasks" className="text-xs text-blue-600 hover:underline">
              去任务页查看全部 →
            </Link>
          </div>
        </section>
      </div>

      {editing && (
        <LeadEditModal
          detail={detail}
          onClose={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await load(true);
          }}
        />
      )}
      {(contactOpen || contactEditing) && (
        <LeadContactModal
          leadId={leadId}
          contact={contactEditing || undefined}
          defaultPrimary={detail.contacts.length === 0}
          onClose={() => {
            setContactOpen(false);
            setContactEditing(null);
          }}
          onSaved={async () => {
            setContactOpen(false);
            setContactEditing(null);
            await load(true);
          }}
        />
      )}
    </div>
  );
}
