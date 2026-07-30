package com.example.chat.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;

/**
 * EgovPromptTemplateManager가 사용하는 {@link YamlPropertySourceFactory}와
 * Spring Boot {@link Binder}를 통한 {@code egov.prompt-templates} 맵 바인딩을 검증한다.
 * 실제 애플리케이션 컨텍스트(Ollama/DB 등) 없이 로딩·바인딩 결과만 확인한다.
 */
class YamlPropertySourceFactoryTest {

    @Test
    void loadsYamlIntoFlattenedPropertySource() throws IOException {
        YamlPropertySourceFactory factory = new YamlPropertySourceFactory();
        EncodedResource resource = new EncodedResource(new ClassPathResource("prompts/prompt-templates.yml"));

        PropertySource<?> propertySource = factory.createPropertySource("prompt-templates", resource);

        Object zeroShot = propertySource.getProperty("egov.prompt-templates.zero-shot");
        assertThat(zeroShot).isNotNull();
        assertThat(zeroShot.toString()).contains("helpful AI assistant");
    }

    @Test
    void bindsPromptTemplatesMapLikeConfigurationProperties() throws IOException {
        YamlPropertySourceFactory factory = new YamlPropertySourceFactory();
        EncodedResource resource = new EncodedResource(new ClassPathResource("prompts/prompt-templates.yml"));
        PropertySource<?> propertySource = factory.createPropertySource("prompt-templates", resource);

        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addLast(propertySource);
        Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));

        Map<String, String> templates = binder
                .bind("egov.prompt-templates", Bindable.mapOf(String.class, String.class))
                .get();

        assertThat(templates).containsKeys(
                "zero-shot",
                "context-based",
                "few-shot-learning",
                "chain-of-thought",
                "code-generation",
                "zero-shot-code-generation",
                "structured-output",
                "default-structured-format",
                "role-based",
                "zero-shot-role-based",
                "step-by-step",
                "quality-check",
                "dynamic-few-shot-header",
                "dynamic-few-shot-footer",
                "technology-info-json");

        assertThat(templates.get("code-generation")).contains("{language}").contains("{requirement}");
        assertThat(templates.get("dynamic-few-shot-header")).contains("{context}");
        assertThat(templates.get("chain-of-thought")).contains("[Example 2]").contains("microservices");
    }
}
