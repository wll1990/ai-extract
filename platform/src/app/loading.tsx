export default function Loading() {
  return (
    <div className="min-h-screen flex items-center justify-center" style={{ background: 'var(--s1)' }}>
      <div className="text-center">
        <div style={{ fontSize: 48, marginBottom: 16, animation: 'pulse-orange 2s infinite' }}>💎</div>
        <p style={{ color: 'var(--fg-low)', fontSize: 14 }}>Loading...</p>
      </div>
    </div>
  );
}
