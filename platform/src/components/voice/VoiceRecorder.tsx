'use client';

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { cn } from '@/lib/utils';
import { apiClient } from '@/lib/api/client';
import { useIsMobile } from '@/lib/device';

/** 语音输入状态 */
export type VoiceStatus = 'idle' | 'recording' | 'processing' | 'done';

/** 语音识别组件 Props */
export interface VoiceRecorderProps {
  /** 显式鉴权 token（分享页传游客 token，与聊天保持一致；不传则走 apiClient 自动 Bearer） */
  authToken?: string;
  /** 最终识别文字回调（button 变体用，调用方替换输入框） */
  onTranscription?: (text: string) => void;
  /** bar 变体：确认浮层点「发送」直接发 */
  onSend?: (text: string) => void;
  /** bar 变体：确认浮层点「取消」丢弃 */
  onCancel?: () => void;
  /** 状态变更回调 */
  onStatusChange?: (status: VoiceStatus) => void;
  /** 识别错误回调 */
  onError?: (message: string) => void;
  /** 是否禁用 */
  disabled?: boolean;
  /** 交互模式：click=点击切换（PC） / longpress=按住说话松手转文字+上滑取消（H5）。不传则按 isMobile() 自动分流 */
  mode?: 'click' | 'longpress';
  /** button=圆形麦克风 / bar=全宽「按住说话」条（H5 语音模式）。不传则按 isMobile() 自动分流 */
  variant?: 'button' | 'bar';
  /** 自定义 className */
  className?: string;
}

/* 录音波形 keyframes */
const WAVE_STYLE_ID = 'voice-recorder-wave';
if (typeof document !== 'undefined' && !document.getElementById(WAVE_STYLE_ID)) {
  const s = document.createElement('style');
  s.id = WAVE_STYLE_ID;
  s.textContent = `
    @keyframes voice-wave {
      0%, 100% { height: 12px; opacity: 0.55; }
      50% { height: 44px; opacity: 1; }
    }
  `;
  document.head.appendChild(s);
}

/** 目标 PCM 采样率，与后端 DashScope 保持一致 */
const TARGET_SAMPLE_RATE = 16000;
/** 最大录音时长（微信同款 60s 上限，防内存无限增长） */
const MAX_DURATION_MS = 60000;

/** Float32 PCM → 16-bit Int16 PCM；采样率不匹配时做线性插值重采样 */
function resampleToInt16(input: Float32Array, fromRate: number, toRate: number): Int16Array {
  if (fromRate === toRate) {
    const out = new Int16Array(input.length);
    for (let i = 0; i < input.length; i++) {
      const s = Math.max(-1, Math.min(1, input[i]));
      out[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
    return out;
  }
  const ratio = fromRate / toRate;
  const outLength = Math.max(1, Math.round(input.length / ratio));
  const out = new Int16Array(outLength);
  for (let i = 0; i < outLength; i++) {
    const srcIdx = i * ratio;
    const idx = Math.floor(srcIdx);
    const frac = srcIdx - idx;
    const s0 = input[idx] ?? 0;
    const s1 = input[idx + 1] ?? s0;
    const sample = s0 + (s1 - s0) * frac;
    const clamped = Math.max(-1, Math.min(1, sample));
    out[i] = clamped < 0 ? clamped * 0x8000 : clamped * 0x7FFF;
  }
  return out;
}

/** 把 Int16 PCM 帧拼成标准 44 字节头的 WAV */
function buildWav(frames: Int16Array[], sampleRate: number): Blob {
  const length = frames.reduce((n, f) => n + f.length, 0);
  const buffer = new ArrayBuffer(44 + length * 2);
  const view = new DataView(buffer);
  const writeStr = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + length * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true); // fmt chunk size
  view.setUint16(20, 1, true);  // PCM
  view.setUint16(22, 1, true);  // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byteRate
  view.setUint16(32, 2, true);  // blockAlign
  view.setUint16(34, 16, true); // bitsPerSample
  writeStr(36, 'data');
  view.setUint32(40, length * 2, true);
  let offset = 44;
  for (const frame of frames) {
    for (let i = 0; i < frame.length; i++) {
      view.setInt16(offset, frame[i], true);
      offset += 2;
    }
  }
  return new Blob([buffer], { type: 'audio/wav' });
}

/**
 * 语音录入 + DashScope 一次性识别组件（one-shot）。
 *
 * <p>架构：录音缓冲 → 停止后拼 WAV → POST /api/v1/stt/recognize → 回填文字</p>
 * <p>降级：不支持麦克风 / 非安全上下文 → 回退 Web Speech API</p>
 *
 * <p>分端交互（未显式传 mode/variant 时按 isMobile() 自动分流）：</p>
 * <ul>
 *   <li>PC（click/button）：点击开始 / 再点结束，结果替换输入框</li>
 *   <li>H5（longpress/bar）：按住说话，上滑取消，松手转文字，浮层确认发送</li>
 * </ul>
 *
 * @since 2026-08-15
 */
export function VoiceRecorder({
  authToken,
  onTranscription,
  onSend,
  onCancel,
  onStatusChange,
  onError,
  disabled = false,
  mode,
  variant,
  className,
}: VoiceRecorderProps) {
  const mobile = useIsMobile();
  const resolvedMode: 'click' | 'longpress' = mode ?? (mobile ? 'longpress' : 'click');
  const resolvedVariant: 'button' | 'bar' = variant ?? (resolvedMode === 'longpress' ? 'bar' : 'button');

  const [status, setStatus] = useState<VoiceStatus>('idle');
  const [hover, setHover] = useState(false);
  const [supported, setSupported] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [cancelHover, setCancelHover] = useState(false);
  const [editing, setEditing] = useState(false);
  const [recognizedText, setRecognizedText] = useState('');

  const audioCtxRef = useRef<AudioContext | null>(null);
  const processorRef = useRef<ScriptProcessorNode | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const pcmChunksRef = useRef<Int16Array[]>([]);
  const statusRef = useRef<VoiceStatus>('idle');
  const touchStartYRef = useRef(0);
  /** 最近一次 touchstart 时间戳，用于忽略其后的合成 mousedown */
  const lastTouchTimeRef = useRef(0);
  /** startRecording 并发守卫，防止 touch+mouse 双触发导致双录音 */
  const startingRef = useRef(false);
  /** 上滑取消的最新值（供 document 兜底闭包读取） */
  const cancelHoverRef = useRef(false);
  /** 松手已处理标记（防 document 兜底 + 按钮 onTouchEnd 双触发） */
  const endHandledRef = useRef(false);
  /** 录音已取消标记（松手后 startRecording 的 async 流程不再继续） */
  const cancelledRef = useRef(false);
  /** 最大时长自动停止句柄 */
  const maxDurationRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  /** 组件是否已挂载（防异步识别回调 setState 到已卸载组件） */
  const mountedRef = useRef(true);

  // 清理资源
  const cleanup = useCallback(() => {
    if (maxDurationRef.current) {
      clearTimeout(maxDurationRef.current);
      maxDurationRef.current = null;
    }
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
    pcmChunksRef.current = [];
    setCancelHover(false);
  }, []);

  // 组件卸载时清理
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      cleanup();
    };
  }, [cleanup]);

  const setStatusAndNotify = useCallback((s: VoiceStatus) => {
    statusRef.current = s;
    setStatus(s);
    onStatusChange?.(s);
  }, [onStatusChange]);

  /** 上传并识别，结果回填 / 进确认浮层 */
  const recognizeAndFinish = useCallback(async (frames: Int16Array[]) => {
    try {
      const wav = buildWav(frames, TARGET_SAMPLE_RATE);
      const form = new FormData();
      form.append('file', wav, 'rec.wav');
      const { text } = await apiClient<{ text: string }>('/stt/recognize', {
        method: 'POST',
        body: form,
        ...(authToken ? { headers: { Authorization: `Bearer ${authToken}` } } : {}),
      });
      if (!mountedRef.current) return;
      const finalText = (text || '').trim();
      if (!finalText) {
        onError?.('未检测到语音，请重试');
        setStatusAndNotify('idle');
        return;
      }
      if (resolvedVariant === 'bar') {
        setRecognizedText(finalText);
        setEditing(true);
      } else {
        onTranscription?.(finalText);
      }
      setStatusAndNotify('done');
      setTimeout(() => {
        if (statusRef.current === 'done') setStatusAndNotify('idle');
      }, 800);
    } catch (e) {
      const msg = e instanceof Error ? e.message : '语音识别失败';
      onError?.(msg);
      setStatusAndNotify('idle');
    }
  }, [authToken, resolvedVariant, onTranscription, onError, setStatusAndNotify]);

  /** 停止录音 → 上传识别 */
  const stopRecording = useCallback(() => {
    if (maxDurationRef.current) {
      clearTimeout(maxDurationRef.current);
      maxDurationRef.current = null;
    }
    // 停采集
    if (processorRef.current) {
      processorRef.current.disconnect();
      processorRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(t => t.stop());
      streamRef.current = null;
    }
    if (audioCtxRef.current) {
      audioCtxRef.current.close().catch(() => {});
      audioCtxRef.current = null;
    }
    const frames = pcmChunksRef.current;
    pcmChunksRef.current = [];
    const total = frames.reduce((n, f) => n + f.length, 0);
    if (total === 0) {
      setStatusAndNotify('idle');
      onError?.('未检测到语音，请重试');
      return;
    }
    setStatusAndNotify('processing');
    recognizeAndFinish(frames);
  }, [recognizeAndFinish, onError, setStatusAndNotify]);

  /** 取消录音（上滑取消，丢弃音频） */
  const cancelRecording = useCallback(() => {
    cancelledRef.current = true;
    cleanup();
    setStatusAndNotify('idle');
  }, [cleanup, setStatusAndNotify]);

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
      setStatusAndNotify('processing');
      const transcript = (event.results[0][0].transcript || '').trim();
      setTimeout(() => {
        if (!mountedRef.current) return;
        if (!transcript) {
          onError?.('未检测到语音，请重试');
          setStatusAndNotify('idle');
          return;
        }
        if (resolvedVariant === 'bar') {
          setRecognizedText(transcript);
          setEditing(true);
        } else {
          onTranscription?.(transcript);
        }
        setStatusAndNotify('done');
        setTimeout(() => setStatusAndNotify('idle'), 800);
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
      if (statusRef.current === 'recording' || statusRef.current === 'processing') setStatusAndNotify('idle');
    };

    recognition.start();
  }, [resolvedVariant, onTranscription, onError, setStatusAndNotify]);

  /**
   * 开始录音 — 获取麦克风权限 + 缓冲 PCM
   */
  const startRecording = useCallback(async () => {
    if (disabled || startingRef.current) return;
    startingRef.current = true;
    cancelledRef.current = false;
    try {
      setError(null);

      if (!window.isSecureContext) {
        fallbackToWebSpeech();
        return;
      }

      // ① 先拿麦克风（可能触发授权弹窗，耗时不定）
      let stream: MediaStream;
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            sampleRate: 16000,
            channelCount: 1,
            echoCancellation: true,
            noiseSuppression: true,
          },
        });
      } catch (e) {
        console.warn('麦克风访问失败，降级到 Web Speech API:', e);
        fallbackToWebSpeech();
        return;
      }
      // 授权期间松手 → 不再继续
      if (cancelledRef.current) {
        stream.getTracks().forEach(t => t.stop());
        return;
      }

      // ② 建立 AudioContext + processor，开始缓冲 PCM
      streamRef.current = stream;
      pcmChunksRef.current = [];
      const audioCtx = new AudioContext({ sampleRate: 16000 });
      await audioCtx.resume();
      if (audioCtx.state !== 'running') {
        throw new Error('AudioContext 无法启动');
      }
      audioCtxRef.current = audioCtx;

      const source = audioCtx.createMediaStreamSource(stream);
      const processor = audioCtx.createScriptProcessor(4096, 1, 1);
      processorRef.current = processor;

      // iOS Safari 不支持 AudioContext 指定采样率，按实际 audioCtx.sampleRate 重采样到 16000
      const fromRate = audioCtx.sampleRate;

      processor.onaudioprocess = (e) => {
        const inputData = e.inputBuffer.getChannelData(0);
        const pcm = resampleToInt16(inputData, fromRate, TARGET_SAMPLE_RATE);
        pcmChunksRef.current.push(pcm);
      };

      source.connect(processor);
      const gainNode = audioCtx.createGain();
      gainNode.gain.value = 0;
      processor.connect(gainNode);
      gainNode.connect(audioCtx.destination);

      setStatusAndNotify('recording');
      // 最大时长自动停止
      maxDurationRef.current = setTimeout(() => stopRecording(), MAX_DURATION_MS);
    } catch (e) {
      console.warn('音频处理初始化失败，降级到 Web Speech API:', e);
      cleanup();
      fallbackToWebSpeech();
    } finally {
      startingRef.current = false;
    }
  }, [disabled, cleanup, fallbackToWebSpeech, stopRecording, setStatusAndNotify]);

  /** 松手停止/取消（document 兜底 + 按钮 onTouchEnd 共用，endHandledRef 去重） */
  const finishPress = useCallback(() => {
    if (endHandledRef.current) return;
    endHandledRef.current = true;
    if (resolvedMode !== 'longpress') return;
    if (cancelHoverRef.current) {
      cancelRecording();
    } else {
      stopRecording();
    }
  }, [resolvedMode, cancelRecording, stopRecording]);

  /** 开始录音 — 长按按下 */
  const handlePressStart = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    if (e.type === 'touchstart') {
      lastTouchTimeRef.current = Date.now();
      endHandledRef.current = false;
      const onGlobalEnd = () => {
        document.removeEventListener('touchend', onGlobalEnd, true);
        document.removeEventListener('touchcancel', onGlobalEnd, true);
        finishPress();
      };
      document.addEventListener('touchend', onGlobalEnd, true);
      document.addEventListener('touchcancel', onGlobalEnd, true);
    } else if (e.type === 'mousedown') {
      if (Date.now() - lastTouchTimeRef.current < 500) return;
    }
    e.preventDefault();
    const y = 'touches' in e ? e.touches[0].clientY : (e as React.MouseEvent).clientY;
    touchStartYRef.current = y;
    cancelHoverRef.current = false;
    setCancelHover(false);
    if (statusRef.current === 'idle') startRecording();
  }, [startRecording, finishPress]);

  /** 长按移动 — 上滑进入取消区 */
  const handlePressMove = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    if (statusRef.current !== 'recording') return;
    const y = 'touches' in e ? e.touches[0].clientY : (e as React.MouseEvent).clientY;
    const cancel = touchStartYRef.current - y > 80;
    cancelHoverRef.current = cancel;
    setCancelHover(cancel);
  }, []);

  /** 长按释放 */
  const handlePressEnd = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    if (e.type === 'mouseup' && Date.now() - lastTouchTimeRef.current < 500) return;
    e.preventDefault();
    finishPress();
  }, [finishPress]);

  /** 点击处理 — click 模式切换录音状态 */
  const handleClick = useCallback(() => {
    if (resolvedMode !== 'click') return;
    if (statusRef.current === 'recording') {
      stopRecording();
    } else if (statusRef.current === 'idle' || statusRef.current === 'done') {
      startRecording();
    }
    // 'processing' 期间忽略点击，避免重复识别
  }, [resolvedMode, startRecording, stopRecording]);

  const isActive = status === 'recording' || status === 'processing';

  /** 长按模式的事件绑定 */
  const pressProps = resolvedMode === 'longpress' ? {
    onMouseDown: handlePressStart,
    onMouseMove: handlePressMove,
    onMouseUp: handlePressEnd,
    onMouseLeave: (e: React.MouseEvent) => { if (statusRef.current === 'recording') handlePressEnd(e); },
    onTouchStart: handlePressStart,
    onTouchMove: handlePressMove,
    onTouchEnd: handlePressEnd,
    onTouchCancel: handlePressEnd,
  } : {};

  /** 确认浮层点「发送」 */
  const handleSend = () => {
    setEditing(false);
    onSend?.(recognizedText);
    setRecognizedText('');
    setStatusAndNotify('idle');
  };

  /** 确认浮层点「取消」 */
  const handleCancel = () => {
    setEditing(false);
    onCancel?.();
    setRecognizedText('');
    setStatusAndNotify('idle');
  };

  /* 全宽「按住 说话」条（H5 语音模式） */
  if (resolvedVariant === 'bar') {
    const showPanel = editing || status === 'recording' || status === 'processing';
    return (
      <>
        {showPanel && (
          <div className="fixed inset-0 z-[110] flex flex-col items-center justify-center px-8"
            style={{ background: 'rgba(16,22,47,0.75)', backdropFilter: 'blur(6px)' }}>
            {editing ? (
              <div className="w-full max-w-[520px] rounded-2xl bg-white p-4 shadow-2xl">
                <textarea
                  autoFocus
                  value={recognizedText}
                  onChange={(e) => setRecognizedText(e.target.value)}
                  className="w-full resize-none border-0 bg-transparent text-[15px] leading-relaxed text-[#10162f] outline-none"
                  style={{ minHeight: '48px', maxHeight: '160px' }}
                />
                <div className="mt-3 flex gap-3">
                  <button onClick={handleCancel}
                    className="flex-1 h-11 rounded-full bg-[#f1f3f8] text-[#5b6886] font-semibold">取消</button>
                  <button onClick={handleSend}
                    className="flex-1 h-11 rounded-full text-white font-semibold"
                    style={{ background: 'linear-gradient(135deg,#2147ff,#3b60ff)' }}>发送</button>
                </div>
              </div>
            ) : (
              <>
                {status === 'recording' && (
                  <div className="flex h-11 items-center gap-[3px]">
                    {Array.from({ length: 24 }).map((_, i) => (
                      <span key={i} className="w-[3px] rounded-full bg-white"
                        style={{
                          height: 44,
                          animation: 'voice-wave 1s ease-in-out infinite',
                          animationDelay: `${i * 0.06}s`,
                        }} />
                    ))}
                  </div>
                )}
                <div className="mt-8 max-w-[520px] text-center text-[16px] leading-relaxed text-white whitespace-pre-wrap">
                  {status === 'processing' ? '识别中…' : '正在聆听…'}
                </div>
                <div className="mt-10 px-7 py-3 rounded-2xl text-base font-bold transition-colors"
                  style={{ background: cancelHover ? '#ef4444' : 'rgba(255,255,255,0.15)', color: '#fff' }}>
                  {cancelHover ? '松开 取消' : '松开 发送 · 上滑 取消'}
                </div>
              </>
            )}
          </div>
        )}
        <button
          type="button"
          {...pressProps}
          disabled={disabled || !supported || editing}
          className={cn(
            'flex h-[44px] w-full flex-shrink-0 items-center justify-center rounded-full text-[15px] font-bold transition-colors duration-150 select-none',
            disabled && 'opacity-50 cursor-not-allowed',
            showPanel && 'opacity-0',
            className,
          )}
          style={{ background: '#eef2ff', color: '#2147ff', touchAction: 'none' }}
        >
          按住 说话
        </button>
      </>
    );
  }

  return (
    <>
      <div className="relative flex items-center">
        <button
          type="button"
          onClick={handleClick}
          onMouseEnter={() => setHover(true)}
          onMouseLeave={(e) => { setHover(false); if (resolvedMode === 'longpress') handlePressEnd(e); }}
          disabled={disabled || !supported}
          title={!supported ? '当前浏览器不支持语音输入' : isActive ? '停止录音' : resolvedMode === 'longpress' ? '按住说话，松手转文字' : '点击开始语音输入'}
          {...pressProps}
          className={cn(
            'relative flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full transition-all duration-200',
            'focus:outline-none select-none',
            status === 'idle' && !hover && 'text-[#94a3b8] bg-transparent',
            status === 'idle' && hover && 'text-[#2147ff] bg-[#eef2ff]',
            status === 'recording' && 'text-[#ef4444] bg-[#fef2f2]',
            status === 'processing' && 'text-[#2147ff] bg-[#eef2ff]',
            status === 'done' && 'text-[#22c55e] bg-[#f0fdf4]',
            disabled && 'opacity-50 cursor-not-allowed',
            className,
          )}
          aria-label={isActive ? '停止录音' : '语音输入'}
          style={resolvedMode === 'longpress' ? { touchAction: 'none' } : undefined}
        >
          {status === 'recording' && (
            <div className="absolute inset-0 rounded-full border-2 border-[#ef4444] animate-[pulse_1.5s_ease-in-out_infinite]" />
          )}

          {(status === 'idle' || status === 'recording' || status === 'processing') && (
            <svg className={status === 'processing' ? 'h-5 w-5 animate-spin' : 'h-5 w-5'} viewBox="0 0 384 512" fill="currentColor" aria-hidden="true">
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
    </>
  );
}
