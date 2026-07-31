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
 * <p>{@link Binder#bind}를 직접 호출하는 방식은 prefix를 Map에 곧바로 바인딩하므로,
 * {@code @ConfigurationProperties}를 클래스 필드에 붙였을 때 실제로 발생하는
 * {@code egov.prompt-templates.<필드명>.*} 경로 불일치를 잡아내지 못한다. 이 테스트는
 * {@link ApplicationContextRunner}로 실제 컨텍스트를 띄워 그 문제를 재현 가능하게 만든다.
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

            assertThat(manager.size()).isGreaterThanOrEqualTo(15);
            assertThat(manager.get("zero-shot")).contains("helpful AI assistant");
            assertThat(manager.format("code-generation",
                    java.util.Map.of("language", "Java", "requirement", "REST API")))
                    .contains("Java").contains("REST API");
            assertThat(manager.get("chain-of-thought")).contains("[Example 2]").contains("microservices");
        });
    }
}
