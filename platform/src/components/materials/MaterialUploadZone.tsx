'use client';

import { useState, useRef } from 'react';

interface Props {
  disabled: boolean;
  onFilesSelected: (files: File[]) => void;
}

export function MaterialUploadZone({ disabled, onFilesSelected }: Props) {
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (disabled) return;
    const dropped = Array.from(e.dataTransfer.files);
    if (dropped.length > 0) onFilesSelected(dropped);
  };

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    if (selected.length > 0) onFilesSelected(selected);
    if (inputRef.current) inputRef.current.value = '';
  };

  return (
    <div style={{ marginBottom: 20 }}>
      <div
        onDragOver={(e) => { e.preventDefault(); if (!disabled) setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        onClick={() => { if (!disabled && inputRef.current) inputRef.current.click(); }}
        style={{
          padding: '40px 20px', textAlign: 'center', borderRadius: 16,
          border: dragOver ? '2px solid #2147ff' : '2px dashed #cdd7ff',
          background: dragOver ? '#eef2ff' : '#fff',
          cursor: disabled ? 'not-allowed' : 'pointer',
          opacity: disabled ? 0.5 : 1,
          transition: 'all 0.3s',
          transform: dragOver ? 'scale(1.02)' : 'scale(1)',
        }}
      >
        <div style={{ fontSize: 40, marginBottom: 8 }}>📁</div>
        <p style={{ fontSize: 14, fontWeight: 600, color: disabled ? '#a0aec0' : '#10162f', margin: 0 }}>
          拖拽文件到此处，或点击选择
        </p>
        <p style={{ fontSize: 12, color: '#747f9e', marginTop: 6 }}>
          支持 PDF · Word · TXT · 录音 · 图片
        </p>
        <p style={{ fontSize: 11, color: '#a0aec0', marginTop: 2 }}>
          单文件不超过 20MB
        </p>
      </div>
      <input
        ref={inputRef}
        type="file"
        multiple
        accept=".pdf,.doc,.docx,.txt,.mp3,.m4a,.wav,.jpg,.jpeg,.png"
        onChange={handleInputChange}
        style={{ display: 'none' }}
      />
    </div>
  );
}
