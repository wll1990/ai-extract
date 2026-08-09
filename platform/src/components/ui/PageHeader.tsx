'use client';

import { useRouter } from 'next/navigation';

const C = {
  blue: '#2147ff', blueBg: '#eef2ff',
  text: '#10162f', textMid: '#5b6886', textLow: '#8e97b0',
  cardBorder: '#e8ecf4', cardHover: '#f5f7fd',
};

interface Props {
  backTo?: string;       // route path, e.g. '/platform/my'
  backLabel?: string;    // display text, e.g. '我的分身'
  title?: string;
  subtitle?: string;
  actions?: React.ReactNode;  // right-side buttons
  transparent?: boolean;     // no background
}

export default function PageHeader({ backTo, backLabel, title, subtitle, actions, transparent }: Props) {
  const router = useRouter();

  return (
    <div style={{
      padding: transparent ? '16px 20px 0' : '32px 20px 24px',
      background: transparent ? 'transparent' : `linear-gradient(160deg, ${C.blueBg} 0%, #f4f6ff 40%, #fff 80%)`,
    }}>
      <div style={{ maxWidth: 960, margin: '0 auto' }}>
        {(backTo || backLabel) && (
          <button
            onClick={() => backTo ? router.push(backTo) : router.back()}
            style={{
              background: 'none', border: 'none', cursor: 'pointer', padding: '6px 12px',
              borderRadius: 8, fontSize: 13, color: C.textMid, fontFamily: 'inherit',
              marginBottom: title ? 16 : 0, marginLeft: -12, transition: 'all 0.15s',
            }}
            onMouseEnter={e => { e.currentTarget.style.background = C.cardHover; e.currentTarget.style.color = C.text; }}
            onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = C.textMid; }}
          >
            ← {backLabel || '返回'}
          </button>
        )}

        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            {title && (
              <h1 style={{
                fontSize: 28, fontWeight: 800, color: C.text, margin: '0 0 6px',
                letterSpacing: '-0.03em', lineHeight: 1.2,
              }}>
                {title}
              </h1>
            )}
            {subtitle && (
              <p style={{ fontSize: 14, color: C.textMid, margin: 0, lineHeight: 1.5 }}>
                {subtitle}
              </p>
            )}
          </div>
          {actions && (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', flexShrink: 0 }}>
              {actions}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
