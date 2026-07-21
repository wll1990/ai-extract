'use client';

import { useState, useEffect, useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getSpaces } from '@/lib/api/spaces';
import { getAvailableExperts } from '@/lib/api/expert';
import { createInterview, getActiveSessions, type ActiveSessionItem } from '@/lib/api/interview';

export interface ExpertOption {
  id: string; name: string; styleTags?: string[]; industryTags?: string[]; type: 'composite' | 'single' | 'none';
}

interface SpaceOption {
  id: string; ownerName: string; title: string; grainCount?: number;
}

export function useInterviewCreate() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const preselectedSpaceId = searchParams.get('spaceId') || '';
  const inviteType = searchParams.get('invite') || '';
  const interviewType = (inviteType === 'expert' ? 'expert' : 'sales') as 'sales' | 'expert';

  const [topicInput, setTopicInput] = useState('');
  const [selectedExpert, setSelectedExpert] = useState<ExpertOption>({ id: '', name: '综合（推荐）', type: 'composite' });
  const [expertOptions, setExpertOptions] = useState<ExpertOption[]>([]);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [loading, setLoading] = useState(false);
  const [isFirstInterview, setIsFirstInterview] = useState(true);
  const [activeSession, setActiveSession] = useState<ActiveSessionItem | null>(null);
  const [spaceId, setSpaceId] = useState(preselectedSpaceId);
  const [spaces, setSpaces] = useState<SpaceOption[]>([]);

  const selectedSpace = spaces.find(s => s.id === spaceId);

  useEffect(() => {
    getSpaces(undefined, undefined, 1, 50).then(d => {
      const list: SpaceOption[] = (d.content || []).map((s: any) => ({
        id: s.id, ownerName: s.ownerName, title: s.title,
        grainCount: s.grainCount,
      }));
      setSpaces(list);
      if (list.length === 1) {
        setSpaceId(list[0].id);
      } else if (!preselectedSpaceId && list.length > 0 && !spaceId) {
        setSpaceId(list[0].id);
      }
    }).catch(() => {});
  }, []);

  useEffect(() => {
    getActiveSessions().then(data => {
      if (data.hasActive && data.sessions.length > 0) setActiveSession(data.sessions[0]);
    }).catch(() => {});
    getAvailableExperts().then(data => {
      if (data.length > 0) {
        const list = data.map((e: any) => ({
          id: e.id || '', name: e.name, styleTags: e.styleTags,
          industryTags: e.industryTags, type: e.type as ExpertOption['type'],
        }));
        setExpertOptions(list);
        const composite = list.find((e: ExpertOption) => e.type === 'composite');
        if (composite) setSelectedExpert(composite);
      }
    }).catch(() => {});
  }, []);

  const handleStart = useCallback(async () => {
    const topic = topicInput.trim();
    if (!topic || !spaceId) return;
    setLoading(true);
    try {
      const session = await createInterview({
        spaceId, topic,
        expertSkillId: selectedExpert.type === 'composite' ? undefined : selectedExpert.id,
        interviewType,
      });
      router.push(`/interview/${session.sessionId}`);
    } catch (err) {
      console.error('创建访谈失败:', err);
    } finally {
      setLoading(false);
    }
  }, [topicInput, spaceId, selectedExpert, router]);

  const handleContinue = useCallback(() => {
    if (activeSession) router.push(`/interview/${activeSession.sessionId}`);
  }, [activeSession, router]);

  return {
    topicInput, setTopicInput,
    selectedExpert, setSelectedExpert,
    expertOptions,
    showAdvanced, setShowAdvanced,
    loading, isFirstInterview, setIsFirstInterview,
    activeSession, spaceId, setSpaceId,
    spaces, selectedSpace, preselectedSpaceId,
    handleStart, handleContinue,
    interviewType,
  };
}
