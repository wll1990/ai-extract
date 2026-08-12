package com.aiextract.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.utils.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

/**
 * DashScope Paraformer 实时语音识别 — 使用官方 SDK。
 */
@Slf4j
@Service
public class SttService {

    @Value("${ai.dashscope.stt.ws-url}")
    public void setWsUrl(String url) {
        Constants.baseWebsocketApiUrl = url;
    }

    @Value("${ai.dashscope.stt.model}")
    private String model;

    @Value("${ai.dashscope.stt.format}")
    private String format;

    @Value("${ai.dashscope.stt.sample-rate}")
    private int sampleRate;

    public SttSession createSession(Listener listener) {
        Recognition recognizer = new Recognition();
        RecognitionParam param = RecognitionParam.builder()
                .model(model)
                .format(format)
                .sampleRate(sampleRate)
                .build();

        ResultCallback<RecognitionResult> callback = new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result.getSentence() != null && result.getSentence().getText() != null) {
                    listener.onTranscription(result.getSentence().getText(), result.isSentenceEnd());
                }
            }

            @Override
            public void onComplete() {
                log.info("SDK STT 识别完成");
                listener.onClosed();
            }

            @Override
            public void onError(Exception e) {
                log.error("SDK STT 异常: {}", e.getMessage());
                listener.onError(e.getMessage());
            }
        };

        recognizer.call(param, callback);
        log.info("SDK STT 识别已启动 model={}", model);
        return new SttSessionImpl(recognizer);
    }

    public interface Listener {
        void onTranscription(String text, boolean isFinal);
        void onError(String message);
        void onClosed();
    }

    public interface SttSession {
        void sendAudio(byte[] pcmData);
        void finish();
        void close();
    }

    private static class SttSessionImpl implements SttSession {
        private final Recognition recognizer;
        private volatile boolean finished;

        SttSessionImpl(Recognition recognizer) {
            this.recognizer = recognizer;
        }

        @Override
        public void sendAudio(byte[] pcmData) {
            if (finished) return;
            try {
                recognizer.sendAudioFrame(ByteBuffer.wrap(pcmData));
            } catch (Exception e) {
                log.warn("SDK sendAudioFrame 失败: {}", e.getMessage());
            }
        }

        @Override
        public void finish() {
            if (finished) return;
            finished = true;
            try {
                recognizer.stop();
            } catch (Exception e) {
                log.warn("SDK stop 失败: {}", e.getMessage());
            }
        }

        @Override
        public void close() {
            finish();
            try {
                recognizer.getDuplexApi().close(1000, "bye");
            } catch (Exception ignored) {
            }
        }
    }
}
