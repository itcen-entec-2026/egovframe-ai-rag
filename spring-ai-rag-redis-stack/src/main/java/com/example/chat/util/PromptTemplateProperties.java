package com.example.chat.util;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * {@code prompts/prompt-templates.yml}의 {@code egov.prompt-templates} 하위 항목을
 * 평탄한 {@code Map<String, String>} 빈으로 바인딩한다.
 *
 * <p>{@code @ConfigurationProperties}를 클래스 필드에 붙이면 prefix 뒤에 필드명이 한 단계
 * 더 붙어 {@code egov.prompt-templates.<필드명>.*} 경로를 찾으므로, YAML이 prefix 바로 아래
 * 키를 나열하는 이 구조와는 맞지 않는다. {@code @ConfigurationProperties}를 Map을 반환하는
 * {@code @Bean} 메서드에 붙이면 그 반환값 자체가 바인딩 대상이 되어 prefix 경로가 그대로
 * 적용된다.
 */
@Configuration
@PropertySource(value = "classpath:prompts/prompt-templates.yml", factory = YamlPropertySourceFactory.class)
public class PromptTemplateProperties {

    @Bean
    @ConfigurationProperties(prefix = "egov.prompt-templates")
    public Map<String, String> promptTemplates() {
        return new LinkedHashMap<>();
    }
}
