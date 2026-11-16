/* eslint-disable @next/next/no-img-element */
import type { ReactNode } from 'react';
import { Sparkle } from '@phosphor-icons/react';

export type PillTone = 'purple' | 'green' | 'orange' | 'gray' | 'red' | 'white';

export function StatusPill({ tone = 'purple', children }: { tone?: PillTone; children: ReactNode }) {
  return <span className={`status-pill tone-${tone}`}>{children}</span>;
}

export function Avatar({
  name,
  avatarUrl,
  small = false,
}: {
  name: string;
  avatarUrl?: string | null;
  small?: boolean;
}) {
  const initial = name.trim().slice(0, 1) || '·';
  return (
    <div className={`expert-avatar ${small ? 'avatar-small' : ''} ${avatarUrl ? '' : 'mm-initial-avatar'}`}>
      {avatarUrl
        ? <img src={avatarUrl} alt={`${name}的头像`} />
        : <span aria-hidden>{initial}</span>}
    </div>
  );
}

export function PageHeading({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <span className="eyebrow">
          <Sparkle weight="fill" />
          {eyebrow}
        </span>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}
