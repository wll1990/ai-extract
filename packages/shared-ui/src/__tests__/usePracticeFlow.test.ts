import { describe, it, expect } from 'vitest';
import { practiceReducer } from '../hooks/usePracticeFlow';
import type { PracticeState } from '../hooks/usePracticeFlow';

const initial: PracticeState = {
  phase: 'idle', data: null, messages: [], evaluation: null,
  sources: [], angles: { current: 1, total: 3 },
  sceneLabel: '', showEndConfirm: false,
};

const mockStartData = {
  practiceId: 'test123',
  conversationId: 'conv001',
  scene: { title: '价格谈判', setting: '客户嫌价格太高', customerLine: '你们太贵了，我们预算有限' },
  practiceAngles: ['开场试探', '深入追问', '最终施压'],
  totalAngles: 3,
};

const mockEval = {
  strengths: [{ point: '回应及时', quote: '好的我理解' }],
  improvements: [{ point: '缺数据支撑', quote: '没那么贵', suggestion: '加 ROI 数据' }],
  demo_script: '我们可以帮您算一笔账...',
  next_advice: '准备 ROI 计算器',
};

describe('practiceReducer', () => {
  it('START action sets phase=active and creates first customer message', () => {
    const next = practiceReducer(initial, {
      type: 'START', data: mockStartData, sceneLabel: '价格谈判',
    });
    expect(next.phase).toBe('active');
    expect(next.messages).toHaveLength(1);
    expect(next.messages[0].role).toBe('customer');
    expect(next.messages[0].content).toBe('你们太贵了，我们预算有限');
    expect(next.sceneLabel).toBe('价格谈判');
    expect(next.angles.total).toBe(3);
  });

  it('ADD_MESSAGE appends a message', () => {
    const state: PracticeState = { ...initial, phase: 'active' };
    const next = practiceReducer(state, {
      type: 'ADD_MESSAGE',
      message: { role: 'user', content: '我们性价比更高' },
    });
    expect(next.messages).toHaveLength(1);
    expect(next.messages[0].content).toBe('我们性价比更高');
  });

  it('UPDATE_LAST_USER merges eval fields into last user message', () => {
    const state: PracticeState = {
      ...initial, phase: 'active',
      messages: [{ role: 'user', content: 'test reply' }],
    };
    const next = practiceReducer(state, {
      type: 'UPDATE_LAST_USER',
      updates: { championAnswer: '销冠这样说', technique: 'ROI锚定法' },
    });
    expect(next.messages[0].championAnswer).toBe('销冠这样说');
    expect(next.messages[0].technique).toBe('ROI锚定法');
  });

  it('REMOVE_LAST_CUSTOMER removes the last customer message', () => {
    const state: PracticeState = {
      ...initial, phase: 'active',
      messages: [
        { role: 'customer', content: 'line 1' },
        { role: 'user', content: 'reply 1' },
        { role: 'customer', content: 'line 2' },
      ],
    };
    const next = practiceReducer(state, { type: 'REMOVE_LAST_CUSTOMER' });
    expect(next.messages).toHaveLength(2);
    expect(next.messages[0].content).toBe('line 1');
    expect(next.messages[1].content).toBe('reply 1');
  });

  it('SET_EVALUATION transitions to evaluate phase', () => {
    const state: PracticeState = { ...initial, phase: 'active' };
    const next = practiceReducer(state, { type: 'SET_EVALUATION', evaluation: mockEval });
    expect(next.phase).toBe('evaluate');
    expect(next.evaluation).toEqual(mockEval);
    expect(next.showEndConfirm).toBe(false);
  });

  it('RESET returns to initialState', () => {
    const state: PracticeState = {
      phase: 'evaluate', data: mockStartData,
      messages: [
        { role: 'customer', content: 'hello' },
        { role: 'user', content: 'hi', championAnswer: 'ans' },
      ],
      evaluation: mockEval, sources: [{ reportId: 'r1', reportTitle: 't1' }],
      angles: { current: 2, total: 3 }, sceneLabel: 'test', showEndConfirm: true,
    };
    const next = practiceReducer(state, { type: 'RESET' });
    expect(next).toEqual(initial);
  });
});
