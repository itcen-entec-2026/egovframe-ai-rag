package com.example.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code application.yml}이 선언한 설정 키가 실제로 읽히는지 검증한다.
 *
 * <p>읽히지 않는 키는 조용히 무시된다. 값을 바꿔도 아무 일이 일어나지 않으므로 설정을 바꾼
 * 사람은 바뀐 줄 알고, 다음 사람은 그 값을 사실로 읽는다. {@code pgvector.distance-type}과
 * {@code document.pdf.*}가 그런 경우였다.
 *
 * <p>어느 클래스가 읽는지는 미리 정하지 않는다. 모듈의 모든 클래스에서
 * {@code @Value} 자리표시자를 모아 비교하므로, 읽는 자리를 다른 클래스로 옮겨도 통과한다.
 */
class EgovYamlBindingTest {

    /** 검사 대상 — yml 경로와, 그 아래 키가 모두 읽혀야 하는 블록. */
    private static final List<String> WATCHED = List.of("pgvector", "document.pdf", "chat.memory", "session");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9._-]+)");

    @Test
    void everyWatchedKeyInTheMainYamlIsRead() throws Exception {
        assertThat(unreadKeysIn(Path.of("src/main/resources/application.yml"))).isEmpty();
    }

    @Test
    void everyWatchedKeyInTheTestYamlIsRead() throws Exception {
        assertThat(unreadKeysIn(Path.of("src/test/resources/application.yml"))).isEmpty();
    }

    /** yml 에는 있는데 {@code @Value} 로 읽는 곳이 없는 키. */
    private TreeSet<String> unreadKeysIn(Path yaml) throws Exception {
        TreeSet<String> declared = declaredKeys(yaml);
        declared.removeAll(placeholders());
        return declared;
    }

    private TreeSet<String> declaredKeys(Path yaml) throws Exception {
        assertThat(yaml).as("설정 파일을 찾지 못했다").exists();
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yaml)) {
            root = new Yaml().load(in);
        }
        TreeSet<String> keys = new TreeSet<>();
        for (String block : WATCHED) {
            Object node = root;
            for (String step : block.split("\\.")) {
                node = node instanceof Map ? ((Map<?, ?>) node).get(step) : null;
            }
            if (node instanceof Map) {
                ((Map<?, ?>) node).keySet().forEach(k -> keys.add(block + "." + k));
            }
        }
        assertThat(keys).as("%s 에서 검사할 블록을 하나도 찾지 못했다", yaml).isNotEmpty();
        return keys;
    }

    /** 모듈의 모든 클래스가 {@code @Value} 로 참조하는 키. */
    private TreeSet<String> placeholders() throws Exception {
        TreeSet<String> found = new TreeSet<>();
        Resource[] classes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/example/chat/**/*.class");
        for (Resource resource : classes) {
            String path = resource.getURL().getPath();
            int start = path.indexOf("com/example/chat");
            if (start < 0) {
                continue;
            }
            String name = path.substring(start).replace(".class", "").replace('/', '.');
            Class<?> type;
            try {
                type = Class.forName(name, false, getClass().getClassLoader());
            } catch (Throwable ignored) { // 로딩할 수 없는 클래스는 @Value 도 가질 수 없다
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                collect(field.getAnnotation(Value.class), found);
            }
            List<Executable> members = new ArrayList<>(List.of(type.getDeclaredConstructors()));
            members.addAll(List.of(type.getDeclaredMethods()));
            for (Executable member : members) {
                collect(member.getAnnotation(Value.class), found);
                for (Parameter parameter : member.getParameters()) {
                    collect(parameter.getAnnotation(Value.class), found);
                }
            }
        }
        assertThat(found).as("모듈에서 @Value 를 하나도 찾지 못했다 — 스캔이 비었다").isNotEmpty();
        return found;
    }

    private void collect(Value value, TreeSet<String> into) {
        if (value == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(value.value());
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }
}
