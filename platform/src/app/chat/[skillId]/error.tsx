'use client';

export default function ChatError({ error, reset }: { error: Error; reset: () => void }) {
  return (
    <div style={{
      display: 'flex', height: '100vh', alignItems: 'center',
      justifyContent: 'center', background: 'var(--s1)',
    }}>
      <div style={{ textAlign: 'center', maxWidth: 360, padding: '0 20px' }}>
        <div style={{ fontSize: 48, marginBottom: 16 }}>💎</div>
        <p style={{ fontSize: 14, color: 'var(--fg-mid)', marginBottom: 8 }}>
          加载失败
        </p>
        <p style={{ fontSize: 12, color: 'var(--fg-low)', marginBottom: 20 }}>
          {error.message || '请稍后重试'}
        </p>
        <button onClick={reset} className="btn btn-primary">
          重试
        </button>
      </div>
    </div>
  );
}
