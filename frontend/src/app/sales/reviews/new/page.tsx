'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  LeadSummary,
  createReview,
  listLeads,
  parseReviewFile,
  MAX_TRANSCRIPT_CHARS,
} from '@/lib/api/sales';
import { CoachPicker } from '@/components/sales/CoachPicker';
import { TranscriptEditor } from '@/components/sales/TranscriptEditor';
import { Button } from '@/components/ui/Button';
import { PageHelp } from '@/components/ui/PageHelp';

const LEAD_STATUS: Record<string, string> = {
  new: '待接触',
  following: '跟进中',
  converted: '已成交',
  invalid: '无效',
};

export default function NewReviewPage() {
  const router = useRouter();
  const [coach, setCoach] = useState<{ id: string; name: string } | null>(null);
  const [transcript, setTranscript] = useState('');
  const [customerName, setCustomerName] = useState('');
  const [leadId, setLeadId] = useState<string | null>(null);
  const [leads, setLeads] = useState<LeadSummary[]>([]);
  const [topic, setTopic] = useState('');
  const [share, setShare] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);

  // 只拉最近 100 个客户做选择器；拉不到不影响新建（按新客户处理）
  useEffect(() => {
    listLeads({ size: 100 })
      .then(res => setLeads(res.content))
      .catch(() => setLeads([]));
  }, []);

  const onUploadFile = async (file: File | undefined) => {
    if (!file) return;
    setUploading(true);
    setError(null);
    try {
      const { text } = await parseReviewFile(file);
      setTranscript(text);
      setErrors(prev => ({ ...prev, transcript: '' }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '文件解析失败，请手动粘贴对话内容');
    } finally {
      setUploading(false);
    }
  };

  const submit = async () => {
    const next: Record<string, string> = {};
    if (!coach) next.coach = '请选择教练';
    if (!transcript.trim()) next.transcript = '请粘贴对话文本';
    if (transcript.length > MAX_TRANSCRIPT_CHARS) {
      next.transcript = `文本不能超过 ${MAX_TRANSCRIPT_CHARS} 字`;
    }
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    setSaving(true);
    setError(null);
    try {
      const res = await createReview({
        coachSkillId: coach!.id,
        transcript,
        customerName: customerName.trim() || undefined,
        topic: topic.trim() || undefined,
        shareTranscriptWithCoach: share,
        leadId: leadId ?? undefined,
      });
      router.push(`/sales/reviews/${res.session.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : '创建失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="min-h-screen bg-surface px-6 py-8">
      <div className="mx-auto max-w-[960px]">
        <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-[28px] font-bold leading-tight text-foreground">新建复盘</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              选一位销冠分身，粘贴这次客户对话。提交后自动生成报告，不会阻塞当前页面。
            </p>
          </div>
          <PageHelp
            title="新建复盘使用说明"
            intro="复盘的质量取决于对话素材：客户说了什么、你怎么回应、最后推进到哪一步。"
            steps={[
              { title: '选教练', desc: '只能选已发布的个体分身；分身的经验领域会决定复盘的视角。' },
              { title: '填客户信息', desc: '客户名用于列表识别；关联已有客户后，会自动对比同一客户的上一次复盘。' },
              { title: '提供对话', desc: '可上传 txt / docx / pdf 或直接粘贴；录音请先转成文字。' },
              { title: '提交', desc: '创建成功后跳到详情页，报告在后台生成，通常 30–90 秒。' },
            ]}
            actions={[
              { label: '上传对话文件', desc: '解析 txt / docx / pdf 后填入下方文本框，可继续手动修改，不会直接提交。' },
              { label: '创建复盘', desc: '校验教练和对话文本后创建复盘，并在后台生成报告；失败会留在本页显示原因。', tone: 'primary' },
              { label: '取消', desc: '返回上一页，不保存任何内容。' },
              { label: '允许教练查看完整原文', desc: '决定教练台账能看到摘要还是完整对话；提交后仍可在详情页修改。' },
            ]}
          />
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-danger/30 bg-danger-bg px-4 py-2.5 text-sm text-danger">
            {error}
          </div>
        )}

        <div className="mb-4 rounded-xl border border-border bg-surface-2 px-4 py-2.5 text-xs text-muted-foreground">
          提交后请到详情页查看报告进度；报告生成失败可重试，不会丢失这次提交。
        </div>

        <div className="space-y-5 rounded-xl border border-border bg-surface-2 p-5">
          <div>
            <label className="mb-1 block text-sm font-medium text-foreground">教练</label>
            <CoachPicker value={coach} onChange={setCoach} error={errors.coach} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block text-sm">
              <span className="mb-1 block font-medium text-foreground">客户/公司</span>
              <input
                className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
                value={customerName}
                onChange={e => {
                  setCustomerName(e.target.value);
                  // 手改了客户名就按新客户处理，避免误挂到别的客户档案上
                  setLeadId(null);
                }}
              />
            </label>
            <label className="block text-sm">
              <span className="mb-1 block font-medium text-foreground">主题</span>
              <input
                className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
                value={topic}
                onChange={e => setTopic(e.target.value)}
              />
            </label>
          </div>
          <label className="block text-sm">
            <span className="mb-1 block font-medium text-foreground">关联已有客户（可选）</span>
            <select
              className="w-full rounded-lg border border-border-strong bg-surface px-3 py-2 text-foreground"
              value={leadId ?? ''}
              onChange={e => {
                const id = e.target.value;
                setLeadId(id || null);
                const lead = leads.find(l => l.id === id);
                if (lead) setCustomerName(lead.name);
              }}
            >
              <option value="">不关联（按新客户处理）</option>
              {leads.map(l => (
                <option key={l.id} value={l.id}>
                  {l.name}（{LEAD_STATUS[l.status] ?? l.status}）
                </option>
              ))}
            </select>
            <span className="mt-1 block text-xs text-muted-foreground">
              关联后，本次复盘会和同一客户的上一次复盘自动对比：上次的下一步做了没有、客户信号变了没有。
            </span>
          </label>
          <div className="rounded-xl border border-dashed border-border-strong bg-surface px-4 py-3">
            <div className="flex flex-wrap items-center gap-3">
              <label className={`cursor-pointer rounded-[10px] border border-border-strong bg-surface-2 px-3 py-1.5 text-sm text-foreground hover:bg-primary-light ${uploading ? 'pointer-events-none opacity-50' : ''}`}>
                {uploading ? '解析中…' : '上传对话文件'}
                <input
                  type="file"
                  accept=".txt,.docx,.pdf"
                  className="hidden"
                  disabled={uploading}
                  onChange={e => { onUploadFile(e.target.files?.[0]); e.target.value = ''; }}
                />
              </label>
              <span className="text-xs text-muted-foreground">支持 txt / docx / pdf，解析后可直接编辑</span>
            </div>
            <p className="mt-1.5 text-xs text-muted-foreground">
              解析失败或说话人识别不准时，仍可在下方手动粘贴/修改文本。
            </p>
          </div>
          <TranscriptEditor value={transcript} onChange={setTranscript} error={errors.transcript} />
          <label className="flex items-start gap-2 text-sm text-foreground">
            <input
              type="checkbox"
              checked={share}
              onChange={e => setShare(e.target.checked)}
              className="mt-0.5"
            />
            <span>
              允许教练查看完整原文
              <span className="block text-xs text-muted-foreground">
                不勾选时，教练台账只看到摘要；之后可在复盘详情里再授权。
              </span>
            </span>
          </label>
          <div className="flex justify-end gap-2 border-t border-border pt-4">
            <Button variant="neutral" onClick={() => router.back()}>取消</Button>
            <Button variant="primary" onClick={submit} disabled={saving}>
              {saving ? '创建中…' : '创建复盘'}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
