package com.aiextract.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.utils.Constants;
import com.aiextract.util.WavUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;

/**
 * 语音识别 — 录音后一次性识别（one-shot）。
 *
 * <p>前端录完整句后上传 WAV，本服务解析为 PCM，调用 DashScope SDK 的
 * {@link Recognition#call(RecognitionParam, File)} 一次性返回全部文本。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 一次性识别 WAV 音频为文本。
     *
     * @param wavBytes 前端上传的 WAV 字节
     * @return 识别出的完整文本（多句拼接，无分隔符）
     */
    public String recognize(byte[] wavBytes) {
        byte[] pcm = WavUtil.toPcm(wavBytes);

        File tmp = null;
        try {
            tmp = File.createTempFile("stt-", ".pcm");
            Files.write(tmp.toPath(), pcm);

            Recognition recognizer = new Recognition();
            RecognitionParam param = RecognitionParam.builder()
                    .model(model)
                    .format(format)
                    .sampleRate(sampleRate)
                    .build();

            String json = recognizer.call(param, tmp);
            log.info("SDK STT 一次性识别完成, model={}", model);
            return joinSentences(json);
        } catch (Exception e) {
            log.error("SDK STT 一次性识别失败: {}", e.getMessage(), e);
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        } finally {
            if (tmp != null && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    /**
     * 解析 SDK 返回的 {@code {"sentences":[{"text":...},...]}} 并拼接所有句子文本。
     */
    private String joinSentences(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            StringBuilder sb = new StringBuilder();
            JsonNode sentences = root.path("sentences");
            if (sentences.isArray()) {
                for (JsonNode s : sentences) {
                    String text = s.path("text").asText();
                    if (text != null && !text.isEmpty()) {
                        sb.append(text);
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("解析 STT 结果 JSON 失败: {}", e.getMessage());
            return "";
        }
    }
}
