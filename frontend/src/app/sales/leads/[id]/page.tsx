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
import { ActionLegend } from '@/components/sales/ActionLegend';
import { Button } from '@/components/ui/Button';
import { PageHelp } from '@/components/ui/PageHelp';

const STATUS_ACTIONS: Record<string, Array<{ label: string; status: string; hint: string }>> = {
  new: [
    { label: '开始跟进', status: 'following', hint: '改为跟进中，表示已开始接触客户' },
    { label: '标记无效', status: 'invalid', hint: '停用线索，不再进入待跟进；可恢复' },
  ],
  following: [
    { label: '标记成交', status: 'converted', hint: '记录为已成交；不会自动创建订单' },
    { label: '标记无效', status: 'invalid', hint: '停用线索，不再进入待跟进；可恢复' },
  ],
  converted: [{ label: '标记无效', status: 'invalid', hint: '成交后如发现误操作，可标记无效' }],
  invalid: [{ label: '恢复为新线索', status: 'new', hint: '恢复成新线索，重新进入待跟进' }],
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

  if (loading) return <div className="p-6 text-sm text-muted-foreground">加载中…</div>;
  if (!detail) return <div className="p-6 text-sm text-danger">{error || '线索不存在'}</div>;

  const s = detail.summary;
  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[1200px]">
        <Link href="/sales/leads" className="text-xs text-muted-foreground hover:text-primary">
          ← 返回线索
        </Link>

        <section className="mt-2 rounded-xl border border-border bg-surface-2 p-5">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-[26px] font-bold leading-tight text-foreground">{s.name}</h1>
                <StatusBadge status={s.status} domain="lead" />
              </div>
              <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
                <span>负责人：{s.ownerName || '—'}</span>
                {members.length > 0 && (
                  <select
                    defaultValue=""
                    onChange={e => { assign(e.target.value); e.target.value = ''; }}
                    className="rounded-md border border-border-strong bg-surface px-2 py-1 text-xs text-foreground"
                    title="分配负责人：只改负责人，不改线索状态"
                  >
                    <option value="">分配负责人…</option>
                    {members.map(m => (
                      <option key={m.id} value={m.id}>{m.name}</option>
                    ))}
                  </select>
                )}
                {detail.sourceReview && (
                  <Link href={`/sales/reviews/${detail.sourceReview.id}`} className="text-primary hover:underline">
                    来源：复盘
                  </Link>
                )}
                {!detail.sourceReview && s.sourceReviewId && <span>来源复盘已删除</span>}
                {detail.nextFollowUpAt && (
                  <span>下次跟进：{new Date(detail.nextFollowUpAt).toLocaleString()}</span>
                )}
              </p>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <PageHelp
                title="线索详情使用说明"
                intro="这里管理一个客户线索：推进状态、分配给同事、记录联系人和跟进备注，并回看所有活动。"
                steps={[
                  { title: '推进状态', desc: '按业务进展点「开始跟进 / 标记成交 / 标记无效」；点错可恢复。' },
                  { title: '补联系人', desc: '记录决策人、影响人、使用人和把关人，方便后续沟通。' },
                  { title: '记录跟进', desc: '在需求摘要下加备注；备注会写入活动时间线，不会覆盖旧内容。' },
                  { title: '看任务', desc: '关联任务来自复盘「生成跟进任务」；完成情况在「跟进任务」页更新。' },
                ]}
                actions={[
                  { label: '开始跟进', desc: '新线索 → 跟进中；表示已经开始接触客户。', tone: 'primary' },
                  { label: '标记成交', desc: '跟进中 → 已成交；不会自动创建订单。' },
                  { label: '标记无效 / 恢复为新线索', desc: '无效线索退出待跟进，记录保留；可随时恢复。' },
                  { label: '编辑资料', desc: '修改名称、需求摘要、下次跟进时间等线索字段，不改状态和活动记录。' },
                  { label: '分配负责人', desc: '把线索交给本企业其他成员；只改负责人，活动时间线会记录操作人。' },
                  { label: '添加联系人 / 编辑 / 删除', desc: '维护客户关键人；删除不可撤销，已删除的联系人不会出现在时间线之外。' },
                  { label: '添加备注', desc: '把本次沟通结论写入活动时间线；不会修改需求摘要。' },
                ]}
                notes={[
                  '活动时间线是只读审计记录，状态变更、负责人变更和备注都会自动追加。',
                  '关联任务只展示最近 50 条，完整列表请去「跟进任务」。',
                ]}
              />
              {(STATUS_ACTIONS[s.status] || []).map(a => (
                <Button
                  key={a.status}
                  variant={a.status === 'following' ? 'primary' : 'neutral'}
                  onClick={() => move(a.status)}
                  title={a.hint}
                >
                  {a.label}
                </Button>
              ))}
              <Button variant="secondary" onClick={() => setEditing(true)} title="修改线索资料，不改状态和活动记录">
                编辑资料
              </Button>
            </div>
          </div>
        </section>

        <ActionLegend
          className="mt-3"
          items={[
            { label: '开始跟进 / 标记成交', desc: '推进线索阶段；成交只是状态，不会自动建订单。', tone: 'primary' },
            { label: '标记无效 / 恢复', desc: '无效线索退出待跟进，记录保留；点「恢复为新线索」可重新跟进。' },
            { label: '编辑资料 / 分配负责人', desc: '改字段或换负责人；活动时间线会自动记录操作人。' },
            { label: '添加联系人 / 添加备注', desc: '补关键人和沟通结论；备注进入时间线，不覆盖需求摘要。' },
          ]}
        />

        {error && (
          <div className="mt-3 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-surface-2 p-4">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-foreground">联系人</h2>
              <Button variant="ghost" size="sm" onClick={() => setContactOpen(true)} title="添加客户关键人">
                ＋ 添加联系人
              </Button>
            </div>
            {detail.contacts.length === 0 && (
              <p className="rounded-lg bg-surface px-3 py-4 text-sm text-muted-foreground">
                还没有联系人，先加一个关键人，后续沟通和任务都能挂在他身上。
              </p>
            )}
            <ul className="space-y-2">
              {detail.contacts.map(c => (
                <li key={c.id} className="rounded-lg bg-surface p-3 text-sm">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-foreground">{c.name}</span>
                      {c.primary && (
                        <span className="rounded-full bg-primary-light px-2 py-0.5 text-[11px] text-primary">
                          主联系人
                        </span>
                      )}
                    </div>
                    <div className="flex gap-2 text-xs">
                      <button
                        type="button"
                        onClick={() => setContactEditing(c)}
                        title="修改联系人信息"
                        className="text-primary hover:underline"
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        onClick={() => removeContact(c)}
                        title="删除联系人；不可撤销"
                        className="text-danger hover:underline"
                      >
                        删除
                      </button>
                    </div>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {[ROLE_LABEL[c.role || 'other'], c.phone].filter(Boolean).join(' · ')}
                  </p>
                  {c.note && <p className="mt-1 text-xs text-muted-foreground">{c.note}</p>}
                </li>
              ))}
            </ul>
          </section>

          <section className="rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-foreground">活动时间线</h2>
            {detail.activities.length === 0 && (
              <p className="rounded-lg bg-surface px-3 py-4 text-sm text-muted-foreground">还没有活动记录。</p>
            )}
            <ul className="space-y-3">
              {detail.activities.map(a => (
                <li key={a.id} className="border-l-2 border-border-strong pl-3">
                  <p className="text-sm text-foreground">
                    {ACTIVITY_LABEL[a.type] || a.type}
                    {a.content ? `：${a.content}` : ''}
                  </p>
                  <p className="mt-0.5 text-xs text-muted-foreground">
                    {a.actorName || '系统'} · {new Date(a.occurredAt).toLocaleString()}
                  </p>
                </li>
              ))}
            </ul>
          </section>
        </div>

        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
          <section className="rounded-xl border border-border bg-surface-2 p-4">
            <h2 className="mb-2 text-sm font-semibold text-foreground">需求摘要</h2>
            <p className="whitespace-pre-wrap rounded-lg bg-surface px-3 py-3 text-sm text-foreground">
              {detail.needSummary || '未填写；点右上角「编辑资料」补充客户需求。'}
            </p>
            <div className="mt-4">
              <h3 className="mb-2 text-sm font-semibold text-foreground">添加备注</h3>
              <textarea
                rows={3}
                className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-sm text-foreground"
                placeholder="记录这次沟通的关键信息；保存后会追加到活动时间线"
                value={note}
                onChange={e => setNote(e.target.value)}
              />
              <div className="mt-2 flex items-center justify-between">
                <span className="text-[11px] text-muted-foreground">备注只追加，不覆盖历史记录</span>
                <Button variant="primary" onClick={saveNote} disabled={noteSaving || !note.trim()}>
                  {noteSaving ? '保存中…' : '添加备注'}
                </Button>
              </div>
            </div>
          </section>

          <section className="rounded-xl border border-border bg-surface-2 p-4">
            <div className="mb-2 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-foreground">关联任务（最近 50 条）</h2>
              <Link href="/sales/tasks" className="text-xs text-primary hover:underline">
                去任务页 →
              </Link>
            </div>
            {detail.tasks.length === 0 && (
              <p className="rounded-lg bg-surface px-3 py-4 text-sm text-muted-foreground">
                还没有任务。到复盘详情点「生成跟进任务」，或在任务页编辑。
              </p>
            )}
            <ul className="space-y-2">
              {detail.tasks.map(t => (
                <li key={t.id} className="flex items-center justify-between gap-2 rounded-lg bg-surface px-3 py-2 text-sm">
                  <span className={t.status === 'done' ? 'text-muted-foreground-2 line-through' : 'text-foreground'}>
                    {t.title}
                  </span>
                  <StatusBadge status={t.status} />
                </li>
              ))}
            </ul>
          </section>
        </div>
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
