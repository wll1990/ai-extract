package com.aiextract;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.utils.Constants;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

public class EmbeddingSdkTest {

    @Test
    public void testEmbeddingWithNewWorkspace() {
        Constants.baseHttpApiUrl = "https://ws-2q3nlw8vormc0y29.cn-beijing.maas.aliyuncs.com/api/v1";

        String[] texts = {"你好世界", "今天天气真好", "销售技巧分享"};
        for (String text : texts) {
            long t0 = System.currentTimeMillis();
            try {
                var param = TextEmbeddingParam.builder()
                        .model("text-embedding-v4")
                        .texts(Collections.singletonList(text))
                        .dimension(1024)
                        .build();
                var result = new TextEmbedding().call(param);
                var vec = result.getOutput().getEmbeddings().get(0).getEmbedding();
                int tokens = result.getUsage().getTotalTokens();
                long ms = System.currentTimeMillis() - t0;
                System.out.println("✅ [" + text + "] dim=" + vec.size() + " tokens=" + tokens + " " + ms + "ms");
                assertTrue(vec.size() > 0);
            } catch (Exception e) {
                System.out.println("❌ [" + text + "] " + e.getMessage());
                fail(e.getMessage());
            }
        }
    }
}
