'use client';

import { MAX_TRANSCRIPT_CHARS as MAX } from '@/lib/api/sales';

export function TranscriptEditor({
  value,
  onChange,
  error,
}: {
  value: string;
  onChange: (v: string) => void;
  error?: string;
}) {
  const over = value.length > MAX;
  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <label className="text-sm text-gray-700">对话文本</label>
        <span className={`text-xs ${over ? 'text-red-600' : 'text-gray-400'}`}>
          {value.length.toLocaleString()} / {MAX.toLocaleString()}
        </span>
      </div>
      <textarea
        value={value}
        onChange={e => onChange(e.target.value)}
        rows={14}
        className={`w-full rounded border px-3 py-2 font-mono text-sm ${
          error || over ? 'border-red-400' : 'border-gray-300'
        }`}
        placeholder={
          '每行一句，建议以「客户：」「销售：」开头，系统会按行切段：\n客户：你们这个多少钱？\n销售：我们按年付费，先看您的规模。'
        }
      />
      <p className="mt-1 text-xs text-gray-500">
        支持直接粘贴录音转写文本。系统按行切段，报告引用会定位到具体段落。
      </p>
      {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
      {over && <p className="mt-1 text-xs text-red-600">超出 {MAX} 字，请删减后再提交</p>}
    </div>
  );
}
