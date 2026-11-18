package com.aiextract.service.skill;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单轮流式调用的技能工具预算（工具循环内共享，线程安全）。
 */
public final class SkillBudget {

    public static final int MAX_BODY_CHARS = 8_000;
    public static final int MAX_REF_CHARS = 4_000;
    public static final int MAX_TOTAL_CHARS = 30_000;
    public static final int MAX_USE_CALLS = 3;
    public static final int MAX_REF_CALLS = 6;
    public static final int MAX_LIST_CALLS = 2;

    private final AtomicInteger useCalls = new AtomicInteger();
    private final AtomicInteger refCalls = new AtomicInteger();
    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicLong chars = new AtomicLong();

    /** @return null = 允许；非 null = 给模型看的拒绝原因 */
    public String consumeUse(int outputChars) {
        return consume(useCalls, MAX_USE_CALLS, outputChars, "技能调用次数已达上限");
    }

    public String consumeRef(int outputChars) {
        return consume(refCalls, MAX_REF_CALLS, outputChars, "参考资料读取次数已达上限");
    }

    public String consumeList(int outputChars) {
        return consume(listCalls, MAX_LIST_CALLS, outputChars, "目录查询次数已达上限");
    }

    private String consume(AtomicInteger counter, int maxCalls, int outputChars, String limitMessage) {
        if (counter.incrementAndGet() > maxCalls) {
            return limitMessage + "，请直接回答。";
        }
        if (chars.addAndGet(outputChars) > MAX_TOTAL_CHARS) {
            return "本次技能内容预算已用完，请直接回答。";
        }
        return null;
    }
}
