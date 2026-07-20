'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback } from 'react';

interface TopicFiltersProps {
  topics: string[];
  activeTopic: string;
}

export function TopicFilters({ topics, activeTopic }: TopicFiltersProps) {
  const router = useRouter();
  const searchParams = useSearchParams();

  const selectTopic = useCallback((topic: string) => {
    const params = new URLSearchParams(searchParams.toString());
    if (topic) {
      params.set('topic', topic);
    } else {
      params.delete('topic');
    }
    const qs = params.toString();
    router.replace(`/discover${qs ? '?' + qs : ''}`);
  }, [searchParams, router]);

  return (
    <div
      className="scrollbar-none"
      style={{
        display: 'flex', gap: 8, overflowX: 'auto',
        paddingBottom: 4, marginBottom: 28,
      }}
    >
      <span
        onClick={() => selectTopic('')}
        style={{
          padding: '7px 16px', borderRadius: 100, fontSize: 12,
          fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap',
          flexShrink: 0,
          ...(activeTopic === ''
            ? { background: 'var(--s12)', color: '#fff', boxShadow: 'var(--shadow-btn)' }
            : { background: 'var(--s3)', color: 'var(--fg-mid)' }),
        }}
      >
        全部
      </span>
      {topics.map((topic) => (
        <span
          key={topic}
          onClick={() => selectTopic(topic)}
          style={{
            padding: '7px 16px', borderRadius: 100, fontSize: 12,
            fontWeight: 500, cursor: 'pointer', whiteSpace: 'nowrap',
            flexShrink: 0, transition: 'all 0.15s',
            ...(activeTopic === topic
              ? { background: 'var(--s12)', color: '#fff', boxShadow: 'var(--shadow-btn)' }
              : { background: 'var(--s3)', color: 'var(--fg-mid)' }),
          }}
        >
          {topic}
        </span>
      ))}
    </div>
  );
}
