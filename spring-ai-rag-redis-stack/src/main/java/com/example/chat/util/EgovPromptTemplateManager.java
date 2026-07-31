package com.example.chat.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * classpath 기반 프롬프트 템플릿 매니저
 *
 * <p>{@code prompts/prompt-templates.yml}의 {@code egov.prompt-templates} 하위 항목을
 * {@link PromptTemplateProperties}가 바인딩한 맵을 주입받아, {@code {변수명}} 형식의
 * 플레이스홀더를 치환하여 최종 프롬프트를 반환합니다.
 *
 * <p>사용 예:
 * <pre>{@code
 * // 단순 조회
 * String prompt = manager.get("zero-shot");
 *
 * // 플레이스홀더 치환
 * String prompt = manager.format("context-based", Map.of("context", contextText));
 * }</pre>
 */
@Component
public class EgovPromptTemplateManager {

    /** egov.prompt-templates 아래 바인딩된 키-값 맵 */
    private final Map<String, String> templates;

    public EgovPromptTemplateManager(@Qualifier("promptTemplates") Map<String, String> promptTemplates) {
        this.templates = promptTemplates == null ? Collections.emptyMap() : new LinkedHashMap<>(promptTemplates);
    }

    /**
     * 키에 해당하는 프롬프트 템플릿 원문을 반환합니다.
     *
     * @param key 템플릿 키 (예: "zero-shot", "context-based")
     * @return 템플릿 문자열, 키가 없으면 빈 문자열
     */
    public String get(String key) {
        return templates.getOrDefault(key, "");
    }

    /**
     * 키에 해당하는 템플릿에서 {@code {변수명}} 플레이스홀더를 치환한 결과를 반환합니다.
     *
     * @param key       템플릿 키
     * @param variables 치환할 변수 맵 (키: 변수명, 값: 치환 문자열)
     * @return 플레이스홀더가 치환된 프롬프트 문자열
     */
    public String format(String key, Map<String, String> variables) {
        String template = get(key);
        if (template.isEmpty() || variables == null || variables.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    /**
     * 로드된 템플릿 키 목록을 반환합니다.
     *
     * @return 읽기 전용 키 집합
     */
    public Set<String> keys() {
        return templates.keySet();
    }

    /**
     * 로드된 템플릿 수를 반환합니다.
     *
     * @return 템플릿 수
     */
    public int size() {
        return templates.size();
    }
}
