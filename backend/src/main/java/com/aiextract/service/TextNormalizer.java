package com.aiextract.service;

import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * 第三层清洗：文本归一化标准化
 *
 * 对过滤后的纯净文本做全局标准化规整，保障后续语义切片、向量化编码、相似度计算的精度一致性。
  * @author AI Extract Team
 */
@Service
public class TextNormalizer {

    /** 录音识别常见错别字 → 正确写法（行业特化词库） */
    private static final Map<String, String> CORRECTIONS = Map.ofEntries(
        Map.entry("销售一团", "销售一部"),
        Map.entry("似有化", "私有化"),
        Map.entry("脚夫", "交付"),
        Map.entry("超标", "招标")
    );

    /**
     * 执行第三层清洗：全局标准化规整
     */
    public String normalize(String filteredText) {
        String text = filteredText;

        // 1. 清除不可见字符
        text = text.replaceAll("[^\\x00-\\x7F\\u4e00-\\u9fa5\\u3000-\\u303F\\uFF00-\\uFFEF]", "");

        // 2. 合并连续换行
        text = text.replaceAll("\\n{3,}", "\n\n");

        // 3. 合并连续空格
        text = text.replaceAll("[ \\t]{2,}", " ");

        // 4. 去除首尾空白
        text = text.replaceAll("^[\\s\\n]+|[\\s\\n]+$", "");

        // 5. 错别字修正
        text = correctTranscriptionErrors(text);

        // 6. 统一段落格式
        text = normalizeParagraphs(text);

        // 7. 中文标点统一
        text = text
            .replace(",", "，")
            .replace(";", "；")
            .replace(":", "：")
            .replace("?", "？")
            .replace("!", "！")
            .replace("(", "（")
            .replace(")", "）");

        return text;
    }

    private String correctTranscriptionErrors(String text) {
        String corrected = text;
        for (var entry : CORRECTIONS.entrySet()) {
            corrected = corrected.replace(entry.getKey(), entry.getValue());
        }
        return corrected;
    }

    private String normalizeParagraphs(String text) {
        String[] paragraphs = text.split("\n\n");
        StringBuilder result = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) { continue; }
            if (!trimmed.matches(".*[。！？\\.\\!\\?]$")) {
                trimmed += "。";
            }
            result.append(trimmed).append("\n\n");
        }
        return result.toString().trim();
    }
}
