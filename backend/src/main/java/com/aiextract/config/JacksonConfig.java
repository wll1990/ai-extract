package com.aiextract.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置 —— 提供共享 ObjectMapper Bean
 *
 * <p>避免各处重复 new ObjectMapper()，统一序列化行为。
 * 所有需要 JSON 处理的类应注入此 Bean 而非自行创建。</p>
 *
 * @since 2026-07-01
  * @author AI Extract Team
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper sharedObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
