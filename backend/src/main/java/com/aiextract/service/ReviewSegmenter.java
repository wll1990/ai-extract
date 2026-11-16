package com.aiextract.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把对话文本按行切成段落，识别「客户 / 销售 / 未知」。
 *
 * <p>切段结果是报告引用的定位单位；不追求完美说话人分离，只保证可回看原文。</p>
 */
@Component
public class ReviewSegmenter {

    private static final int MAX_SEGMENTS = 500;
    private static final int MAX_SEGMENT_CHARS = 2000;

    public record SegmentDraft(String speaker, String text) {
    }

    public List<SegmentDraft> split(String transcript) {
        List<SegmentDraft> out = new ArrayList<>();
        boolean truncated = false;
        for (String raw : transcript.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 说话人只认「客户/对方/销售/我 + 冒号」；长行拆段后继承同一说话人
            String speaker = detectSpeaker(line);
            String body = stripPrefix(line);
            for (String piece : splitLongLine(body, MAX_SEGMENT_CHARS)) {
                out.add(new SegmentDraft(speaker, piece));
                if (out.size() >= MAX_SEGMENTS) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                break;
            }
        }
        if (out.isEmpty()) {
            out.add(new SegmentDraft("unknown", transcript.trim()));
        } else if (truncated) {
            // 显式标记截断，避免用户以为报告覆盖了全部对话
            out.add(new SegmentDraft("unknown", "【对话过长，仅分析前 " + MAX_SEGMENTS + " 段】"));
        }
        return out;
    }

    private static final int OVERLAP_CHARS = 50;
    private static final String SENTENCE_ENDS = "。！？；!?;\n";

    /**
     * 超长行按句子边界切分，并保留 50 字重叠。
     * 原来按 2000 字硬切，引用跨切点时会校验失败并拖垮整份报告（R5）。
     */
    private List<String> splitLongLine(String line, int max) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(start + max, line.length());
            if (end < line.length()) {
                // 从 max 往前找最近的句末标点，至少保留一半长度
                for (int i = end; i > start + max / 2; i--) {
                    if (SENTENCE_ENDS.indexOf(line.charAt(i - 1)) >= 0) {
                        end = i;
                        break;
                    }
                }
            }
            parts.add(line.substring(start, end));
            if (end >= line.length()) {
                break;
            }
            start = Math.max(end - OVERLAP_CHARS, start + 1);
        }
        return parts;
    }

    private String detectSpeaker(String line) {
        if (line.matches("^(客户|对方)\\s*[：:].*")) {
            return "customer";
        }
        if (line.matches("^(销售|我)\\s*[：:].*")) {
            return "sales";
        }
        return "unknown";
    }

    private String stripPrefix(String line) {
        String s = line.replaceFirst("^(客户|对方|销售|我)\\s*[：:]\\s*", "");
        return s.isBlank() ? line : s;
    }
}
