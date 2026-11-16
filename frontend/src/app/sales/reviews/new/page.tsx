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
    <div className="mx-auto max-w-3xl p-6">
      <h1 className="mb-1 text-xl font-semibold text-gray-900">新建复盘</h1>
      <p className="mb-5 text-sm text-gray-500">可上传 txt / docx / pdf，或直接粘贴对话文本；录音请先转成文字。</p>
      {error && (
        <div className="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      )}
      <div className="space-y-5">
        <div>
          <label className="mb-1 block text-sm text-gray-700">教练</label>
          <CoachPicker value={coach} onChange={setCoach} error={errors.coach} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">客户/公司</span>
            <input
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={customerName}
              onChange={e => {
                setCustomerName(e.target.value);
                // 手改了客户名就按新客户处理，避免误挂到别的客户档案上
                setLeadId(null);
              }}
            />
          </label>
          <label className="block text-sm">
            <span className="mb-1 block text-gray-700">主题</span>
            <input
              className="w-full rounded border border-gray-300 px-3 py-2"
              value={topic}
              onChange={e => setTopic(e.target.value)}
            />
          </label>
        </div>
        <label className="block text-sm">
          <span className="mb-1 block text-gray-700">关联已有客户（可选）</span>
          <select
            className="w-full rounded border border-gray-300 px-3 py-2"
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
          <span className="mt-1 block text-xs text-gray-500">
            关联后，本次复盘会和同一客户的上一次复盘自动对比：上次的下一步做了没有、客户信号变了没有。
          </span>
        </label>
        <div className="rounded-lg border border-dashed border-gray-300 bg-gray-50 px-3 py-3">
          <div className="flex flex-wrap items-center gap-3">
            <label className={`cursor-pointer rounded border border-gray-300 bg-white px-3 py-1.5 text-sm ${uploading ? 'opacity-50' : ''}`}>
              {uploading ? '解析中…' : '上传对话文件'}
              <input
                type="file"
                accept=".txt,.docx,.pdf"
                className="hidden"
                disabled={uploading}
                onChange={e => { onUploadFile(e.target.files?.[0]); e.target.value = ''; }}
              />
            </label>
            <span className="text-xs text-gray-500">支持 txt / docx / pdf，解析后可直接编辑</span>
          </div>
          <p className="mt-1 text-xs text-gray-400">
            解析失败或说话人识别不准时，仍可在下方手动粘贴/修改文本。
          </p>
        </div>
        <TranscriptEditor value={transcript} onChange={setTranscript} error={errors.transcript} />
        <label className="flex items-start gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={share}
            onChange={e => setShare(e.target.checked)}
            className="mt-0.5"
          />
          <span>
            允许教练查看完整原文
            <span className="block text-xs text-gray-500">
              不勾选时，教练台账只看到摘要；之后可在复盘详情里再授权。
            </span>
          </span>
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" onClick={() => router.back()} className="rounded border px-3 py-2 text-sm">
            取消
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={saving}
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
          >
            {saving ? '创建中…' : '创建复盘'}
          </button>
        </div>
      </div>
    </div>
  );
}
