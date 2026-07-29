'use client';

export default function Error({ reset }: { error: Error; reset: () => void }) {
  return (
    <div className="min-h-screen flex items-center justify-center" style={{ background: 'var(--s1)' }}>
      <div className="text-center">
        <p style={{ color: 'var(--fg-mid)', fontSize: 14, marginBottom: 16 }}>页面加载失败</p>
        <button onClick={reset} className="btn btn-primary">重试</button>
      </div>
    </div>
  );
}
