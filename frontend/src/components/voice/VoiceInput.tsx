'use client';

import React, { useState, useCallback, useRef } from 'react';
import { cn } from '@/lib/utils';

/** 语音输入状态 */
export type VoiceStatus = 'idle' | 'recording' | 'recognizing' | 'done';

/** 语音输入组件 Props */
export interface VoiceInputProps {
  onTranscription?: (text: string) => void;
  disabled?: boolean;
}

/**
 * 语音输入组件
 *
 * 四种状态：
 * - 待机：灰色麦克风图标
 * - 录音中：金色图标+呼吸光环动画
 * - 识别中：旋转动画
 * - 完成：绿色对勾，200ms弹入→1s恢复待机
 *
 * 降级策略：Typeless → Web Speech API → 隐藏按钮
 */
export const VoiceInput: React.FC<VoiceInputProps> = ({
  onTranscription,
  disabled = false,
}) => {
  const [status, setStatus] = useState<VoiceStatus>('idle');
  const [supported, setSupported] = useState(true);
  const recognitionRef = useRef<any>(null);

  /**
   * 检查浏览器是否支持语音识别
   */
  const checkSupport = useCallback((): boolean => {
    const hasSpeechApi =
      typeof window !== 'undefined' &&
      ('SpeechRecognition' in window || 'webkitSpeechRecognition' in window);
    return hasSpeechApi;
  }, []);

  /**
   * 开始录音
   */
  const startRecording = useCallback(() => {
    if (disabled || !checkSupport()) {
      setSupported(false);
      return;
    }

    const SpeechRecognition: any =
      (window as any).SpeechRecognition
      || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) return;

    const recognition = new SpeechRecognition();
    recognition.lang = 'zh-CN';
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;

    recognition.onstart = () => {
      setStatus('recording');
    };

    recognition.onresult = (event: any) => {
      setStatus('recognizing');
      const transcript = event.results[0][0].transcript;

      setTimeout(() => {
        setStatus('done');
        onTranscription?.(transcript);

        setTimeout(() => {
          setStatus('idle');
        }, 1000);
      }, 300);
    };

    recognition.onerror = () => {
      setStatus('idle');
    };

    recognition.onend = () => {
      if (status === 'recording') {
        setStatus('idle');
      }
    };

    recognitionRef.current = recognition;
    recognition.start();
  }, [disabled, checkSupport, onTranscription, status]);

  /**
   * 停止录音
   */
  const stopRecording = useCallback(() => {
    if (recognitionRef.current) {
      recognitionRef.current.stop();
    }
    setStatus('idle');
  }, []);

  /**
   * 点击处理
   */
  const handleClick = useCallback(() => {
    if (status === 'recording' || status === 'recognizing') {
      stopRecording();
    } else {
      startRecording();
    }
  }, [status, startRecording, stopRecording]);

  // 不支持语音输入，隐藏按钮
  if (!supported) return null;

  const isActive = status === 'recording' || status === 'recognizing';

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={disabled}
      className={cn(
        'relative flex h-10 w-10 items-center justify-center rounded-full transition-all duration-200',
        'focus:outline-none focus:ring-2 focus:ring-primary/30',
        status === 'idle' && 'text-muted-foreground hover:text-primary hover:bg-primary-light',
        status === 'recording' && 'text-primary',
        status === 'recognizing' && 'text-primary',
        status === 'done' && 'text-success',
        disabled && 'opacity-50 cursor-not-allowed',
      )}
      aria-label={isActive ? '停止录音' : '开始录音'}
    >
      {/* 呼吸光环 */}
      {status === 'recording' && (
        <div className="absolute inset-0 rounded-full border-2 border-primary animate-[pulse_1.5s_ease-in-out_infinite]" />
      )}

      {/* 图标 */}
      {status === 'idle' && (
        <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 18.75a6 6 0 006-6v-1.5m-6 7.5a6 6 0 01-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15.75a3 3 0 01-3-3V4.5a3 3 0 116 0v8.25a3 3 0 01-3 3z" />
        </svg>
      )}

      {status === 'recording' && (
        <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 18.75a6 6 0 006-6v-1.5m-6 7.5a6 6 0 01-6-6v-1.5m6 7.5v3.75m-3.75 0h7.5M12 15.75a3 3 0 01-3-3V4.5a3 3 0 116 0v8.25a3 3 0 01-3 3z" />
        </svg>
      )}

      {status === 'recognizing' && (
        <svg className="h-5 w-5 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182" />
        </svg>
      )}

      {status === 'done' && (
        <svg className="h-5 w-5 animate-[scaleIn_200ms_ease-out]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5}>
          <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
        </svg>
      )}
    </button>
  );
};
