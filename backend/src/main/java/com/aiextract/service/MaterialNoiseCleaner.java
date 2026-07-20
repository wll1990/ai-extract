package com.aiextract.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 第一层清洗：格式噪声正则清洗
 *
 * 针对聊天记录和录音转文本两类素材，内置专用正则规则集。
 * 批量清除全量非业务类格式杂质，为后续业务过滤提供纯净输入。
 */
@Service
public class MaterialNoiseCleaner {

    // ==========================================
    // 规则集 A: 聊天记录素材
    // ==========================================
    private static final List<Pattern> CHAT_NOISE_PATTERNS = List.of(
            Pattern.compile("\\[?\\d{2,4}[-/]\\d{2}[-/]\\d{2}[\\sT]\\d{2}:\\d{2}(:\\d{2})?\\]?"),
            Pattern.compile("^[\\w\\u4e00-\\u9fa5]+[\\(（].*?[\\)）]?\\s*[:：]\\s*", Pattern.MULTILINE),
            Pattern.compile("\\[系统.*?\\]|【.*?提示.*?】|@所有人|xxx.*?加入.*?(群|频道|对话)"),
            Pattern.compile("\\[图片\\]|\\[表情\\]|\\[语音\\]|\\[文件\\]|\\[视频\\]|<msg.*?</msg>|:\\w+:"),
            Pattern.compile("[─\\-\\*\\=#]{8,}"),
            Pattern.compile("\\n{3,}"),
            Pattern.compile("^[ \\t]+|[ \\t]+$", Pattern.MULTILINE));

    // ==========================================
    // 规则集 B: 录音转文本素材
    // ==========================================
    private static final List<Pattern> VOICE_NOISE_PATTERNS = List.of(
            Pattern.compile("\\b[嗯啊呃哦嘛呢呐哇哈嘿哎哟]\\b"),
            Pattern.compile("\\b(就是|就是说|那个|这个|然后呢|反正|怎么说呢|说白了)\\b"),
            Pattern.compile("[\\(（]\\s*(停顿|沉默|思考|\\d+秒)\\s*[\\)）]|\\[\\d+秒\\]|<silence[^>]*>|\\.{4,}"),
            Pattern.compile("([\\u4e00-\\u9fa5]{2,4})\\1{2,}"),
            Pattern.compile("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s，。！？、；：\"\"''【】《》（）\\.\\!\\?,\\s]{3,}"),
            Pattern.compile("[\\(（]\\s*(无内容|静音|无声|听不清)\\s*[\\)）]|\\[inaudible\\]"),
            Pattern.compile("(?m)^[^\\n]{1,10}$"));

    // ==========================================
    // 规则集 C: 文档素材（PDF/Word/PPT）
    // ==========================================
    private static final List<Pattern> DOCUMENT_NOISE_PATTERNS = List.of(
            Pattern.compile("^[\\d\\s\\.\\-\\|/]+$"),
            Pattern.compile("^.*\\.{5,}\\s*\\d+$"),
            Pattern.compile("^(图|表|Figure|Table)\\s*\\d+"),
            Pattern.compile("第[一二三四五六七八九十\\d]+页"),
            Pattern.compile("版权所有.*|Confidential.*|内部资料.*"));

    /**
     * 根据文本内容和文件扩展名自动推断素材类型。
     *
     * <p>
     * 素材的 fileType 存的是 HTTP Content-Type（如 text/plain），
     * 无法直接匹配噪声规则集。此方法用启发式检测真实类型。
     * </p>
     *
     * @param text     素材文本前 500 行
     * @param fileName 文件名（取扩展名辅助判断）
     * @return chat_log / voice_transcript / document
     */
    public static String detectMaterialType(String text, String fileName) {
        {
            if (text == null || text.isBlank())
                return "document";
        }

        String ext = fileName != null ? fileName.toLowerCase() : "";

        // 音频/语音扩展名 → 优先判为转写文本
        if (ext.endsWith(".mp3") || ext.endsWith(".wav") || ext.endsWith(".m4a")
                || ext.endsWith(".ogg") || ext.endsWith(".flac")) {
            return "voice_transcript";
        }

        // 统计前 500 行的特征信号
        int totalLines = 0, timestampLines = 0, senderLines = 0;
        int fillerCount = 0;
        String[] lines = text.split("\n");
        int checkLines = Math.min(lines.length, 500);
        java.util.regex.Pattern tsPattern = java.util.regex.Pattern.compile(
                "\\d{2,4}[-/]\\d{2}[-/]\\d{2}[\\sT]\\d{2}:\\d{2}");
        java.util.regex.Pattern senderPattern = java.util.regex.Pattern.compile(
                "^[\\w\\u4e00-\\u9fa5]{2,12}[\\s:：].{2,}");
        java.util.regex.Pattern fillerPattern = java.util.regex.Pattern.compile(
                "\\b(嗯|啊|呃|哦|嘛|呢|呐|哇|哈|哎)\\b|(就是|就是说|那个|这个|然后|反正|怎么说呢|说白了)");

        for (int i = 0; i < checkLines; i++) {
            String line = lines[i];
            if (line.isBlank())
                continue;
            totalLines++;
            if (tsPattern.matcher(line).find())
                timestampLines++;
            if (senderPattern.matcher(line).find())
                senderLines++;
            java.util.regex.Matcher fm = fillerPattern.matcher(line);
            while (fm.find())
                fillerCount++;
        }

        // 聊天记录：超过 30% 行含时间戳且超过 20% 行含发送者模式
        if (totalLines > 0) {
            double tsRatio = (double) timestampLines / totalLines;
            double senderRatio = (double) senderLines / totalLines;
            if (tsRatio > 0.3 && senderRatio > 0.2) {
                return "chat_log";
            }
            // 语音转写：填充词密度高（每行平均 > 0.5 个）
            if ((double) fillerCount / totalLines > 0.5) {
                return "voice_transcript";
            }
        }

        // 默认：文档
        return "document";
    }

    /**
     * 执行第一层清洗：根据素材类型选择规则集。
     *
     * @param rawText      原始文本
     * @param materialType 素材类型，来自 {@link #detectMaterialType} 或数据库 material_type 字段
     */
    public String cleanFormatNoise(String rawText, String materialType) {
        String text = rawText;
        text = applyCommonRules(text);

        if (materialType == null) {
            return text;
        }

        switch (materialType.toLowerCase()) {
            case "chat_log":
            case "chat":
                text = applyRules(text, CHAT_NOISE_PATTERNS);
                break;
            case "voice_transcript":
            case "transcript":
                text = applyRules(text, VOICE_NOISE_PATTERNS);
                break;
            case "pdf":
            case "word":
            case "ppt":
            case "document":
                text = applyRules(text, DOCUMENT_NOISE_PATTERNS);
                break;
            default:
                // 不认识的类型也尝试文档规则（兜底）
                text = applyRules(text, DOCUMENT_NOISE_PATTERNS);
                break;
        }

        return text;
    }

    private String applyCommonRules(String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        return text;
    }

    private String applyRules(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {

            text = p.matcher(text).replaceAll("");
        }
        return text;
    }
}
