'use client';

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { cn } from '@/lib/utils';
import { getToken } from '@/lib/storage';

/** 语音输入状态 */
export type VoiceStatus = 'idle' | 'recording' | 'recognizing' | 'done';

/** 语音识别组件 Props */
export interface VoiceRecorderProps {
  /** 最终识别文字回调（isFinal=true 时触发） */
  onTranscription: (text: string) => void;
  /** 实时中间结果回调（isFinal=false 时触发，用于实时展示） */
  onInterimText?: (text: string) => void;
  /** 状态变更回调 */
  onStatusChange?: (status: VoiceStatus) => void;
  /** 是否禁用 */
  disabled?: boolean;
  /** 自定义 className */
  className?: string;
}

/**
 * 语音录制 + DashScope Paraformer 实时识别组件。
 *
 * <p>架构：MediaRecorder → 后端 WebSocket → DashScope → 流式转写 → 填回输入框</p>
 * <p>降级：不支持 MediaRecorder → 回退 Web Speech API → 隐藏按钮</p>
 *
 * <h3>UI 状态</h3>
 * <ul>
 *   <li>idle — 灰色麦克风 SVG，透明背景</li>
 *   <li>hover — 背景变浅蓝 (bg-primary/10)，图标变蓝，transition-colors</li>
 *   <li>recording — 红色脉冲光环 + 红色图标，背景浅红，呼吸动画</li>
 *   <li>recognizing — 蓝色图标，旋转加载动画</li>
 *   <li>done — 绿色对勾，缩放入场</li>
 * </ul>
 *
 * @since 2026-07-30
 */
export function VoiceRecorder({
  onTranscription,
  onInterimText,
  onStatusChange,
  disabled = false,
  className,
}: VoiceRecorderProps) {
  const [status, setStatus] = useState<VoiceStatus>('idle');
  const [hover, setHover] = useState(false);
  const [supported, setSupported] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const interimRef = useRef<string>('');
  const statusRef = useRef<VoiceStatus>('idle');

  // 清理资源
  const cleanup = useCallback(() => {
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current = null;
    }
    if (audioCtxRef.current) {
      audioCtxRef.current.close().catch(() => {});
      audioCtxRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(t => t.stop());
      streamRef.current = null;
    }
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    interimRef.current = '';
  }, []);

  // 组件卸载时清理
  useEffect(() => {
    return () => cleanup();
  }, [cleanup]);

  const setStatusAndNotify = useCallback((s: VoiceStatus) => {
    statusRef.current = s;
    setStatus(s);
    onStatusChange?.(s);
  }, [onStatusChange]);

  /**
   * 通过后端 WebSocket 中继，连接 DashScope Paraformer
   */
  const connectWs = useCallback((): Promise<WebSocket> => {
    return new Promise((resolve, reject) => {
      const token = getToken();
      if (!token) {
        reject(new Error('未登录'));
        return;
      }

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      // 开发模式：Next.js rewrites 不代理 WebSocket，直连后端 8080
      // 生产模式：Nginx 反向代理统一处理，同域连接
      const wsHost = process.env.NODE_ENV === 'development'
        ? 'localhost:8080'
        : window.location.host;
      const wsUrl = `${protocol}//${wsHost}/api/v1/ws/stt?token=${encodeURIComponent(token)}`;

      const ws = new WebSocket(wsUrl);
      ws.binaryType = 'arraybuffer';

      ws.onopen = () => {
        wsRef.current = ws;
        resolve(ws);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.error) {
            console.warn('STT error:', data.error);
            return;
          }
          if (data.text) {
            if (data.isFinal) {
              // 最终结果：追加到已有文本后面
              const finalText = interimRef.current + data.text;
              interimRef.current = '';
              onInterimText?.('');
              onTranscription(finalText);
              setStatusAndNotify('done');
              setTimeout(() => {
                if (wsRef.current === ws) {
                  setStatusAndNotify('idle');
                }
              }, 1000);
            } else {
              // 中间结果：实时展示
              interimRef.current = data.text;
              onInterimText?.(data.text);
            }
          }
        } catch {
          // ignore parse errors
        }
      };

      ws.onerror = () => {
        reject(new Error('WebSocket 连接失败'));
      };

      ws.onclose = () => {
        wsRef.current = null;
        if (statusRef.current === 'recording' || statusRef.current === 'recognizing') {
          setStatusAndNotify('idle');
        }
      };
    });
  }, [onTranscription, onInterimText, setStatusAndNotify]);

  /**
   * 开始录音 — 获取麦克风权限 + 建立 WebSocket
   */
  const startRecording = useCallback(async () => {
    if (disabled) return;
    setError(null);

    // HTTP 环境 getUserMedia 必败，直接走 Web Speech 降级
    if (!window.isSecureContext) {
      fallbackToWebSpeech();
      return;
    }

    try {
      // 1. 建立 WebSocket
      await connectWs();
    } catch (e) {
      console.warn('WebSocket 连接失败，降级到 Web Speech API:', e);
      fallbackToWebSpeech();
      return;
    }

    try {
      // 2. 获取麦克风
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: 16000,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
        },
      });
      streamRef.current = stream;

      // 3. 创建 AudioContext，重采样到 16kHz
      const audioCtx = new AudioContext({ sampleRate: 16000 });
      audioCtxRef.current = audioCtx;

      const source = audioCtx.createMediaStreamSource(stream);

      // 4. ScriptProcessorNode 获取原始 PCM 数据
      // bufferSize=4096 → ~256ms @ 16kHz
      const processor = audioCtx.createScriptProcessor(4096, 1, 1);
      processorRef.current = processor;

      processor.onaudioprocess = (e) => {
        const ws = wsRef.current;
        if (!ws || ws.readyState !== WebSocket.OPEN) return;

        const inputData = e.inputBuffer.getChannelData(0);
        // Float32 → Int16 PCM
        const pcm = new Int16Array(inputData.length);
        for (let i = 0; i < inputData.length; i++) {
          const s = Math.max(-1, Math.min(1, inputData[i]));
          pcm[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }
        ws.send(pcm.buffer);
      };

      source.connect(processor);
      // 零音量 GainNode 保持音频图活跃，避免啸叫
      const gainNode = audioCtx.createGain();
      gainNode.gain.value = 0;
      processor.connect(gainNode);
      gainNode.connect(audioCtx.destination);

      setStatusAndNotify('recording');
    } catch (e) {
      console.warn('麦克风访问失败，降级到 Web Speech API:', e);
      cleanup();
      fallbackToWebSpeech();
    }
  }, [disabled, connectWs, cleanup, setStatusAndNotify]);

  /**
   * 停止录音
   */
  const stopRecording = useCallback(() => {
    // 发送 finish 文本帧通知后端结束识别
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send('finish');
      // 延迟关闭以接收最后的识别结果
      setTimeout(() => {
        cleanup();
      }, 1500);
    } else {
      cleanup();
    }

    if (status === 'recording') {
      setStatusAndNotify('recognizing');
    }
  }, [status, cleanup, setStatusAndNotify]);

  /**
   * 降级方案：Web Speech API
   */
  const fallbackToWebSpeech = useCallback(() => {
    const SpeechRecognition: any =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setSupported(false);
      return;
    }

    const recognition = new SpeechRecognition();
    recognition.lang = 'zh-CN';
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;

    recognition.onstart = () => setStatusAndNotify('recording');
    recognition.onresult = (event: any) => {
      setStatusAndNotify('recognizing');
      const transcript = event.results[0][0].transcript;
      setTimeout(() => {
        onTranscription(transcript);
        setStatusAndNotify('done');
        setTimeout(() => setStatusAndNotify('idle'), 1000);
      }, 300);
    };
    recognition.onerror = (event: any) => {
      const messages: Record<string, string> = {
        'not-allowed': '麦克风权限未授权，请在浏览器设置中允许',
        'network': '语音服务连接失败，可能需要 HTTPS 部署',
        'no-speech': '未检测到语音，请重试',
        'audio-capture': '无法访问麦克风',
      };
      setError(messages[event?.error] || `语音识别失败 (${event?.error || 'unknown'})`);
      setStatusAndNotify('idle');
    };
    recognition.onend = () => {
      if (statusRef.current === 'recording') setStatusAndNotify('idle');
    };

    recognition.start();
  }, [status, onTranscription, setStatusAndNotify]);

  /**
   * 点击处理 — 切换录音状态
   */
  const handleClick = useCallback(() => {
    if (status === 'recording' || status === 'recognizing') {
      stopRecording();
    } else {
      startRecording();
    }
  }, [status, startRecording, stopRecording]);

  const isActive = status === 'recording' || status === 'recognizing';

  return (
    <div className="relative flex items-center">
    <button
      type="button"
      onClick={handleClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      disabled={disabled || !supported}
      title={!supported ? '当前浏览器不支持语音输入' : isActive ? '停止录音' : '语音输入'}
      className={cn(
        'relative flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full transition-all duration-200',
        'focus:outline-none',
        status === 'idle' && !hover && 'text-[#94a3b8] bg-transparent',
        status === 'idle' && hover && 'text-[#2147ff] bg-[#eef2ff]',
        status === 'recording' && 'text-[#ef4444] bg-[#fef2f2]',
        status === 'recognizing' && 'text-[#2147ff] bg-[#eef2ff]',
        status === 'done' && 'text-[#22c55e] bg-[#f0fdf4]',
        disabled && 'opacity-50 cursor-not-allowed',
        className,
      )}
      aria-label={isActive ? '停止录音' : '语音输入'}
    >
      {/* 录音脉冲光环 */}
      {status === 'recording' && (
        <div className="absolute inset-0 rounded-full border-2 border-[#ef4444] animate-[pulse_1.5s_ease-in-out_infinite]" />
      )}

      {/* 图标 — Font Awesome 话筒 */}
      {(status === 'idle' || status === 'recording' || status === 'recognizing') && (
        <svg className={status === 'recognizing' ? 'h-5 w-5 animate-spin' : 'h-5 w-5'} viewBox="0 0 384 512" fill="currentColor" aria-hidden="true">
          <path d="M192 48c26.5 0 48 21.5 48 48v128c0 26.5-21.5 48-48 48s-48-21.5-48-48V96c0-26.5 21.5-48 48-48zM96 96v128c0 53 43 96 96 96s96-43 96-96V96c0-53-43-96-96-96S96 43 96 96zM48 184c0-13.3-10.7-24-24-24S0 170.7 0 184v40c0 97.9 73.3 178.7 168 190.5v49.5H120c-13.3 0-24 10.7-24 24s10.7 24 24 24h144c13.3 0 24-10.7 24-24s-10.7-24-24-24h-48v-49.5c94.7-11.8 168-92.6 168-190.5v-40c0-13.3-10.7-24-24-24s-24 10.7-24 24v40c0 79.5-64.5 144-144 144S48 303.5 48 224v-40z" />
        </svg>
      )}

      {status === 'done' && (
        <svg className="h-5 w-5 animate-[scaleIn_200ms_ease-out]" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round">
          <path d="M5 13l4 4L19 7" />
        </svg>
      )}
    </button>
    {error && (
      <span className="absolute left-0 top-full mt-1 rounded bg-red-50 border border-red-200 px-2 py-0.5 text-[10px] text-red-600 whitespace-nowrap z-10">
        {error}
      </span>
    )}
    </div>
  );
}
