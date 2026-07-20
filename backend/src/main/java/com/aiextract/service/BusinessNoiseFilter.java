package com.aiextract.service;

import com.aiextract.config.DomainConfig;
import com.aiextract.config.DomainConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 第二层清洗：冗余无效话术过滤。
 *
 * <p>业务白名单从领域配置 YAML 读取，替代硬编码销售专用词。
 * 核心原则：只剔除杂质，不损伤业务关键内容。</p>
  * @author AI Extract Team
 */
@Slf4j
@Service
public class BusinessNoiseFilter {

    private final DomainConfigLoader domainConfigLoader;

    private static final List<String> GREETING_JUNK = List.of(
        "你好", "您好", "早上好", "下午好", "晚上好", "好久不见",
        "最近怎么样", "忙不忙", "吃饭了吗", "今天天气",
        "辛苦了", "麻烦您了", "不好意思打扰了"
    );

    private static final List<String> CHITCHAT_JUNK = List.of(
        "你们公司真大", "这个装修挺好的", "路上堵车",
        "孩子多大了", "周末去哪玩", "最近股市", "世界杯", "年会"
    );

    private static final List<String> ECHO_JUNK = List.of(
        "嗯嗯", "好的好的", "行", "可以", "没问题", "对",
        "是的", "确实", "有道理", "明白了", "了解了",
        "收到", "好嘞", "OK", "ok"
    );

    private static final List<String> CLOSING_JUNK = List.of(
        "感谢您的时间", "下次再聊", "保持联系", "随时联系",
        "不打扰您了", "您先忙", "有问题随时找我", "祝您工作顺利", "期待合作"
    );

    private static final List<String> FILLER_JUNK = List.of(
        "我想说的是", "就是说呢", "怎么说呢", "说白了就是",
        "其实吧", "我觉得吧", "说实话", "讲真的",
        "从某种程度上来说", "可以这么理解"
    );

    private static final String AFFIRMATIVE_PATTERN = "^[嗯对好是行哦可]{1,5}$";
    private static final String PUNCTUATION_REGEX = "[，。！？、；：\"\"''\\s]";

    private final List<String> allJunk;

    public BusinessNoiseFilter(DomainConfigLoader domainConfigLoader) {
        this.domainConfigLoader = domainConfigLoader;
        allJunk = new ArrayList<>();
        allJunk.addAll(GREETING_JUNK);
        allJunk.addAll(CHITCHAT_JUNK);
        allJunk.addAll(ECHO_JUNK);
        allJunk.addAll(CLOSING_JUNK);
        allJunk.addAll(FILLER_JUNK);
    }

    /**
     * 执行第二层清洗：过滤冗余话术。
     *
     * <p>白名单从领域配置读取。含白名单关键词的行永不删除。</p>
     */
    public String filterBusinessNoise(String cleanedText, String domain) {
        // 从领域配置加载白名单
        List<String> whitelist = loadWhitelist(domain);

        String[] lines = cleanedText.split("\n");
        StringBuilder result = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                result.append("\n");
                continue;
            }

            boolean hasWhitelistKeyword = whitelist.stream()
                .anyMatch(trimmed::contains);
            if (hasWhitelistKeyword) {
                result.append(trimmed).append("\n");
                continue;
            }

            if (isJunkLine(trimmed)) {
                continue;
            }

            result.append(trimmed).append("\n");
        }

        return result.toString();
    }

    private List<String> loadWhitelist(String domain) {
        if (domain == null) {

            return List.of();

        }
        try {
            DomainConfig config = domainConfigLoader.load(domain);
            if (config != null && config.getAcceptance() != null
                    && config.getAcceptance().getBusinessWhitelist() != null) {
                return config.getAcceptance().getBusinessWhitelist();
            }
        } catch (Exception e) {
            log.warn("加载领域白名单失败 domain={}, 使用空白名单: {}", domain, e.getMessage());
        }
        return List.of();
    }

    private boolean isJunkLine(String line) {
        String normalized = line.replaceAll(PUNCTUATION_REGEX, "");

        for (String junk : allJunk) {
            String junkNormalized = junk.replaceAll(PUNCTUATION_REGEX, "");
            if (normalized.equals(junkNormalized)) {
                return true;
            }
        }

        if (normalized.matches(AFFIRMATIVE_PATTERN)) {
            return true;
        }

        return false;
    }
}
