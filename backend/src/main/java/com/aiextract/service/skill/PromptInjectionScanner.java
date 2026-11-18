package com.aiextract.service.skill;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 技能内容注入扫描（纵深防御，不是唯一边界）。
 *
 * <p>与 QueryGate 使用同一组正则，但**不受** {@code app.query-gate.enabled} 开关影响：
 * 关闭运行时门控不能同时关闭技能发布扫描。</p>
 */
@Component
public class PromptInjectionScanner {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("忽略.{0,50}(指令|上文|提示|规则|设定|限制)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(ignore|forget|override).{0,30}(instruction|prompt|rule)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(system|user|assistant).{0,20}(prompt|message)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("从现在开始.{0,30}(角色|身份)", Pattern.CASE_INSENSITIVE)
    );

    public Optional<String> scan(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                return Optional.of("内容包含不允许的指令");
            }
        }
        return Optional.empty();
    }

    public void assertClean(String field, String text) {
        scan(text).ifPresent(reason -> {
            throw new IllegalArgumentException(field + "：" + reason);
        });
    }
}
