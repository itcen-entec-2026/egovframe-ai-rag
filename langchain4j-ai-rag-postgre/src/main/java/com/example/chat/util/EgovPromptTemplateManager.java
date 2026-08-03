package com.example.chat.util;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 외부 YAML 파일 기반 프롬프트 템플릿 관리자.
 * <p>
 * {@link PromptTemplateProperties}가 classpath:prompts/prompt-templates.yml 에서
 * 바인딩한 맵을 주입받으며, {@code {변수명}} 형식의 플레이스홀더 치환을 지원합니다.
 * </p>
 */
@Component
public class EgovPromptTemplateManager {

    private final Map<String, String> templates;

    public EgovPromptTemplateManager(@Qualifier("promptTemplates") Map<String, String> promptTemplates) {
        this.templates = promptTemplates == null ? Collections.emptyMap() : new LinkedHashMap<>(promptTemplates);
    }

    /**
     * 키에 해당하는 템플릿을 반환합니다.
     *
     * @param key prompts.* 하위 키 (예: "zero-shot", "context-based")
     * @return 템플릿 문자열, 키가 없으면 빈 문자열
     */
    public String get(String key) {
        return templates.getOrDefault(key, "");
    }

    /**
     * 키에 해당하는 템플릿에서 플레이스홀더를 치환하여 반환합니다.
     * <p>
     * {@code variables} 맵의 각 엔트리를 {@code {키}} 형식으로 치환합니다.
     * 예: key="context-based", variables=Map.of("context","...")
     * </p>
     *
     * @param key       템플릿 키
     * @param variables 치환할 변수 맵 ({변수명} → 값)
     * @return 치환된 프롬프트 문자열
     */
    public String render(String key, Map<String, String> variables) {
        String template = get(key);
        if (template.isEmpty() || variables == null || variables.isEmpty()) {
            return template;
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return template;
    }
}
