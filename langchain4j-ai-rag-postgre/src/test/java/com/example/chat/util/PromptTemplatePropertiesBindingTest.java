package com.example.chat.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link PromptTemplateProperties}가 실제 Spring 바인딩 경로(
 * {@code ConfigurationPropertiesBindingPostProcessor})를 통해 {@code egov.prompt-templates}
 * YAML을 {@link EgovPromptTemplateManager}까지 올바르게 전달하는지 검증한다.
 *
 * <p>단순히 {@code Binder.bind()}로 prefix를 Map에 직접 바인딩하는 방식은,
 * {@code @ConfigurationProperties}를 클래스 필드에 붙였을 때 prefix 뒤에 필드명이 한 단계
 * 더 붙는 실제 경로 문제(PR #30에서 발견된 사례)를 재현하지 못한다. 이 테스트는
 * {@link ApplicationContextRunner}로 실제 컨텍스트를 띄워 매니저 빈까지 값이 도달하는지
 * 검증한다.
 */
class PromptTemplatePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PromptTemplateProperties.class, EgovPromptTemplateManager.class);

    @Test
    void managerResolvesTemplatesBoundFromYaml() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            EgovPromptTemplateManager manager = context.getBean(EgovPromptTemplateManager.class);

            assertThat(manager.get("zero-shot")).contains("helpful AI assistant");
            assertThat(manager.render("code-generation",
                    java.util.Map.of("language", "Java", "requirement", "REST API")))
                    .contains("Java").contains("REST API");
            assertThat(manager.get("dynamic-few-shot-header")).contains("{context}");
            assertThat(manager.get("dynamic-few-shot-footer")).isNotEmpty();
        });
    }
}
