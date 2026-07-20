'use client';

import React, { useState, useCallback, useEffect } from 'react';

interface DomainOption { id: string; name: string; children?: DomainOption[]; }

/** UploadExpertModal Props */
export interface UploadExpertModalProps {
  open: boolean;
  onClose: () => void;
  existingExperts?: { id: string; name: string }[];
  onUpload: (data: {
    name: string; description: string; styleTags: string[];
    industryTags: string[]; seniority: string; files: File[];
    existingExpertId?: string; domain?: string;
  }) => void;
}

const STYLE_OPTIONS = ['追问型', '温和型', '结构化', '逻辑型', '故事型'];
const INDUSTRY_OPTIONS = ['金融', '银行', '快消', '零售', '制造', 'B2B', '科技', '医疗'];

/**
 * 上传萃取师材料弹窗
 */
export const UploadExpertModal: React.FC<UploadExpertModalProps> = ({ open, onClose, onUpload, existingExperts }) => {
  const [mode, setMode] = useState<'select' | 'create'>('create');
  const [selectedExpertId, setSelectedExpertId] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [styleTags, setStyleTags] = useState<string[]>([]);
  const [industryTags, setIndustryTags] = useState<string[]>([]);
  const [seniority, setSeniority] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [domain, setDomain] = useState('');
  const [domainOptions, setDomainOptions] = useState<DomainOption[]>([]);

  // 加载领域树
  useEffect(() => {
    if (!open) return;
    fetch('/api/v1/domains')
      .then(r => r.json())
      .then(d => setDomainOptions(d?.data || []))
      .catch(() => setDomainOptions([]));
  }, [open]);

  const toggleTag = useCallback((tag: string, list: string[], setter: (v: string[]) => void) => {
    setter(list.includes(tag) ? list.filter(t => t !== tag) : [...list, tag]);
  }, []);

  const handleFiles = useCallback((newFiles: FileList | null) => {
    if (!newFiles) return;
    const valid = Array.from(newFiles).filter(f => f.size <= 50 * 1024 * 1024);
    setFiles(prev => [...prev, ...valid]);
  }, []);

  const removeFile = useCallback((index: number) => {
    setFiles(prev => prev.filter((_, i) => i !== index));
  }, []);

  const handleSubmit = useCallback(async () => {
    if (mode === 'select' && !selectedExpertId) return;
    if (mode === 'create' && (!name.trim() || files.length === 0 || !domain)) return;
    setUploading(true);
    await onUpload({
      name, description, styleTags, industryTags, seniority, files,
      existingExpertId: mode === 'select' ? selectedExpertId : undefined,
      domain: mode === 'create' ? domain : undefined,
    });
    setUploading(false);
  }, [mode, selectedExpertId, name, description, styleTags, industryTags, seniority, files, domain, onUpload]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="mx-4 w-full max-w-[600px] max-h-[85vh] overflow-y-auto rounded-2xl bg-surface-2 p-6 shadow-xl">
        <h2 className="mb-5 text-xl font-bold text-foreground">上传萃取师材料</h2>

        {/* 模式切换 */}
        <div className="mb-4 flex rounded-lg bg-primary-light p-0.5">
          <button onClick={() => setMode('create')}
            className={`flex-1 rounded-md py-2 text-sm font-medium ${mode === 'create' ? 'bg-surface-2 text-foreground shadow-sm' : 'text-muted-foreground'}`}>
            ➕ 新建萃取师
          </button>
          <button onClick={() => setMode('select')}
            className={`flex-1 rounded-md py-2 text-sm font-medium ${mode === 'select' ? 'bg-surface-2 text-foreground shadow-sm' : 'text-muted-foreground'}`}>
            📂 选择已有萃取师
          </button>
        </div>

        {/* 选择已有萃取师 */}
        {mode === 'select' && (
          <label className="mb-3 block"><span className="text-sm font-medium">选择萃取师</span>
            <select value={selectedExpertId} onChange={e => setSelectedExpertId(e.target.value)}
              className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground">
              <option value="">请选择...</option>
              {(existingExperts || []).map(e => (
                <option key={e.id} value={e.id}>{e.name}</option>
              ))}
            </select>
          </label>
        )}

        {/* 新建萃取师表单 */}
        {mode === 'create' && (<>
        <label className="mb-3 block"><span className="text-sm font-medium">萃取师姓名</span>
          <input type="text" value={name} onChange={e => setName(e.target.value)} placeholder="请输入姓名"
            className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground" />
        </label>

        <label className="mb-3 block"><span className="text-sm font-medium">风格标签</span>
          <div className="mt-1 flex flex-wrap gap-1">
            {STYLE_OPTIONS.map(t => (
              <button key={t} type="button" onClick={() => toggleTag(t, styleTags, setStyleTags)}
                className={`rounded-full px-3 py-1 text-xs ${styleTags.includes(t) ? 'bg-primary text-white' : 'bg-primary-light text-primary'}`}>{t}</button>
            ))}
          </div>
        </label>

        <label className="mb-3 block"><span className="text-sm font-medium">擅长行业</span>
          <div className="mt-1 flex flex-wrap gap-1">
            {INDUSTRY_OPTIONS.map(t => (
              <button key={t} type="button" onClick={() => toggleTag(t, industryTags, setIndustryTags)}
                className={`rounded-full px-3 py-1 text-xs ${industryTags.includes(t) ? 'bg-primary text-white' : 'bg-primary-light text-muted-foreground'}`}>{t}</button>
            ))}
          </div>
        </label>

        <label className="mb-3 block"><span className="text-sm font-medium">资历描述</span>
          <textarea value={seniority} onChange={e => setSeniority(e.target.value)} rows={2} placeholder="如：10年经验萃取师，服务过..."
            className="mt-1 w-full resize-none rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground" />
        </label>

        {/* 领域选择 */}
        <label className="mb-3 block"><span className="text-sm font-medium">所属领域 <span className="text-danger">*</span></span>
          <select value={domain} onChange={e => setDomain(e.target.value)}
            className="mt-1 w-full rounded-lg border border-border-strong px-3 py-2 text-sm outline-none focus:border-foreground">
            <option value="">请选择领域...</option>
            {domainOptions.map(parent => (
              <optgroup key={parent.id} label={parent.name}>
                {(parent.children || []).map(child => (
                  <option key={child.id} value={child.id}>{child.name}</option>
                ))}
              </optgroup>
            ))}
          </select>
          {domainOptions.length === 0 && (
            <p className="mt-1 text-xs text-muted-foreground-2">加载领域列表...</p>
          )}
        </label>

        {/* 文件上传区 */}
        <div
          onDragOver={e => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={e => { e.preventDefault(); setDragOver(false); handleFiles(e.dataTransfer.files); }}
          className={`mb-3 flex flex-col items-center justify-center rounded-lg border-2 border-dashed py-8 transition-colors ${dragOver ? 'border-primary bg-warning-bg/20' : 'border-border-strong bg-surface'}`}>
          <span className="text-4xl">📁</span>
          <p className="mt-2 text-sm text-muted-foreground">拖拽文件到此处，或点击上传</p>
          <p className="mt-1 text-xs text-muted-foreground-2">支持 PDF, Word, PPT, Excel, TXT, MD, HTML, 图片(png/jpg), 音频(mp3/wav) · 单文件上限50MB</p>
          <input type="file" multiple accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.csv,.txt,.md,.html,.htm,.png,.jpg,.jpeg,.gif,.bmp,.webp,.mp3,.wav,.m4a,.ogg,.flac" onChange={e => handleFiles(e.target.files)}
            className="mt-3 text-sm" />
        </div>

        {/* 已选文件 */}
        {files.length > 0 && (
          <div className="mb-4 space-y-1">
            {files.map((f, i) => (
              <div key={i} className="flex items-center justify-between rounded-lg bg-primary-light px-3 py-2 text-sm">
                <span className="truncate">📄 {f.name} ({(f.size / 1024).toFixed(1)}KB)</span>
                <button onClick={() => removeFile(i)} className="text-danger hover:underline">删除</button>
              </div>
            ))}
          </div>
        )}

        </>)}
        <div className="mt-6 flex justify-end gap-3">
          <button onClick={onClose} className="rounded-lg px-4 py-2 text-sm text-muted-foreground">取消</button>
          <button onClick={handleSubmit} disabled={uploading || (mode === 'create' && (!name.trim() || files.length === 0 || !domain)) || (mode === 'select' && !selectedExpertId)}
            className="rounded-lg bg-primary px-6 py-2 text-sm font-semibold text-white disabled:opacity-40">
            {uploading ? '上传中...' : '上传并开始分析'}
          </button>
        </div>
      </div>
    </div>
  );
};
