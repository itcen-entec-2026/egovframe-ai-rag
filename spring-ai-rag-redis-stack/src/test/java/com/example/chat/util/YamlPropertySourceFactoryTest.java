package com.example.chat.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;

/**
 * {@link YamlPropertySourceFactory}가 YAML을 평탄화된 {@link PropertySource}로
 * 올바르게 읽어오는지만 검증한다. {@code egov.prompt-templates} prefix가 실제로
 * {@link EgovPromptTemplateManager}까지 올바르게 바인딩되는지는
 * {@link PromptTemplatePropertiesBindingTest}에서 실제 Spring 컨텍스트로 검증한다.
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
}
