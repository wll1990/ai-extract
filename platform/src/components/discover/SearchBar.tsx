'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useState, useEffect } from 'react';

export function SearchBar() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [value, setValue] = useState(searchParams.get('q') || '');

  useEffect(() => {
    setValue(searchParams.get('q') || '');
  }, [searchParams]);

  const handleSubmit = useCallback((e: React.FormEvent) => {
    e.preventDefault();
    const params = new URLSearchParams(searchParams.toString());
    if (value.trim()) {
      params.set('q', value.trim());
    } else {
      params.delete('q');
    }
    const qs = params.toString();
    router.replace(`/discover${qs ? '?' + qs : ''}`);
  }, [value, searchParams, router]);

  return (
    <form onSubmit={handleSubmit} style={{ position: 'relative', maxWidth: 440, marginBottom: 20 }}>
      <span style={{
        position: 'absolute', left: 14, top: '50%',
        transform: 'translateY(-50%)', fontSize: 15, color: 'var(--fg-dim)',
        pointerEvents: 'none',
      }}>
        🔍
      </span>
      <input
        type="text"
        placeholder="搜索专家、场景、话题..."
        value={value}
        onChange={(e) => setValue(e.target.value)}
        style={{
          width: '100%', height: 42, borderRadius: 100,
          border: '1px solid var(--border-subtle)',
          background: 'var(--surface)', padding: '0 18px 0 42px',
          fontSize: 13, outline: 'none', color: 'var(--fg-high)',
          fontFamily: 'inherit',
          boxShadow: 'var(--shadow-glow)',
          transition: 'box-shadow 0.2s',
        }}
        onFocus={(e) => { e.target.style.boxShadow = '0 0 0 3px rgba(255,92,0,0.12)'; }}
        onBlur={(e) => { e.target.style.boxShadow = 'var(--shadow-glow)'; }}
      />
    </form>
  );
}
