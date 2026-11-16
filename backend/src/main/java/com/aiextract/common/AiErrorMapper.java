package com.aiextract.common;

import com.aiextract.exception.BusinessException;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型调用异常 → 稳定错误码 + 用户可读文案。
 *
 * <p>Spring AI 把供应商返回的 HTTP 状态包在异常 message 里（如 {@code 401 - {...}}），
 * 各处 catch 后如果只取 {@code e.getMessage()}，前端只能看到一串英文/JSON。
 * 统一在这里映射，保证：
 * <ul>
 *   <li>SSE error 事件带 {@code code}，前端可按码给出下一步动作；</li>
 *   <li>后台任务落库的失败原因可直接展示给用户；</li>
 *   <li>「Key 没钱」「Key 失效」「限流」「超时」不再都显示成「AI服务暂时不可用」。</li>
 * </ul>
 *
 * <p>映射是只读的，不做任何重试或回退决策——回退策略仍在 {@code ChatClientResolver}。</p>
 */
public final class AiErrorMapper {

    public static final String KEY_INVALID = "AI_KEY_INVALID";
    public static final String KEY_FORBIDDEN = "AI_KEY_FORBIDDEN";
    public static final String QUOTA_EXCEEDED = "AI_QUOTA_EXCEEDED";
    public static final String RATE_LIMITED = "AI_RATE_LIMITED";
    public static final String TIMEOUT = "AI_TIMEOUT";
    public static final String UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";
    public static final String SERVICE_UNAVAILABLE = "AI_SERVICE_UNAVAILABLE";

    private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)(401|402|403|404|429|5\\d\\d)(?!\\d)");

    private AiErrorMapper() {}

    /** 统一错误：code 稳定可用于前端分支，message 可直接展示。 */
    public record AiError(String code, String message, boolean retryable) {}

    /**
     * 映射异常。优先使用 {@link BusinessException} 已带的 subCode（如企业 Key 停用/解密失败）。
     */
    public static AiError map(Throwable throwable) {
        BusinessException biz = findBusiness(throwable);
        if (biz != null) {
            // 业务校验失败（文本过长、文件需人工处理等）直接透出原因，别误报成「AI 服务不可用」
            String code = biz.getSubCode() != null && !biz.getSubCode().isBlank()
                    ? biz.getSubCode() : "BUSINESS_ERROR";
            return new AiError(code, biz.getMessage(), false);
        }

        String text = collectMessages(throwable);
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时")) {
            return new AiError(TIMEOUT, "模型响应超时，请稍后重试", true);
        }

        Matcher m = HTTP_STATUS.matcher(text);
        if (m.find()) {
            String status = m.group(1);
            switch (status) {
                case "401":
                    return new AiError(KEY_INVALID, "模型 Key 无效或已过期，请联系管理员检查模型配置", false);
                case "402":
                    return new AiError(QUOTA_EXCEEDED, "模型 Key 余额不足，请联系管理员充值或更换 Key", false);
                case "403":
                    return new AiError(KEY_FORBIDDEN, "模型 Key 无权访问该模型，请联系管理员检查模型配置", false);
                case "404":
                    return new AiError(KEY_FORBIDDEN, "模型或接口地址不存在，请联系管理员检查模型配置", false);
                case "429":
                    return new AiError(RATE_LIMITED, "模型调用过于频繁或触发限额，请稍后重试", true);
                default:
                    return new AiError(UPSTREAM_ERROR, "模型服务暂时不可用，请稍后重试", true);
            }
        }

        return new AiError(SERVICE_UNAVAILABLE, "AI服务暂时不可用，请稍后重试", true);
    }

    /** 落库/接口返回用：中文原因 + 稳定码，便于用户和运维定位。 */
    public static String describe(Throwable throwable) {
        AiError error = map(throwable);
        return error.message() + "（" + error.code() + "）";
    }

    private static BusinessException findBusiness(Throwable throwable) {
        Throwable cur = throwable;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur instanceof BusinessException be) {
                return be;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static String collectMessages(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = throwable;
        int guard = 0;
        while (cur != null && guard++ < 10) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append(' ');
            }
            sb.append(cur.getClass().getSimpleName()).append(' ');
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
