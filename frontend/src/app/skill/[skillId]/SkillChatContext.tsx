'use client';

import React, { createContext, useContext } from 'react';

export type ChatMode = 'qa' | 'talk' | 'practice';

export interface SkillInfo {
  ownerName: string;
  ownerTitle: string;
  ownerQuote: string;
}

interface SkillChatContextValue {
  skillId: string;
  skillInfo: SkillInfo;
  chatMode: ChatMode;
  setChatMode: (m: ChatMode) => void;
  modeSelected: boolean;
  setModeSelected: (v: boolean) => void;
}

const SkillChatContext = createContext<SkillChatContextValue | null>(null);

export function useSkillChatContext() {
  const ctx = useContext(SkillChatContext);
  if (!ctx) throw new Error('useSkillChatContext must be used within SkillChatProvider');
  return ctx;
}

export function SkillChatProvider({ value, children }: { value: SkillChatContextValue; children: React.ReactNode }) {
  return (
    <SkillChatContext.Provider value={value}>
      {children}
    </SkillChatContext.Provider>
  );
}
