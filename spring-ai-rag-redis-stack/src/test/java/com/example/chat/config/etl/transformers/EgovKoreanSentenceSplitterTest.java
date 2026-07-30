package com.example.chat.config.etl.transformers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * {@link EgovKoreanSentenceSplitter} 단위 테스트.
 */
class EgovKoreanSentenceSplitterTest {

    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);
    private static final Pattern SENTENCE_ENDING = Pattern.compile(
            ".*([.!?。！？][\"'」』)\\]》]?|다|요|니다|습니다|한다|된다)$");

    @Test
    @DisplayName("청크의 토큰 수가 chunkSize 이하다")
    void chunksDoNotExceedTokenLimit() {
        int chunkSize = 30;
        EgovKoreanSentenceSplitter splitter = splitter(chunkSize);
        String text = "첫 문장입니다. 둘째 문장입니다. 셋째 문장입니다. 넷째 문장입니다. 다섯째 문장입니다.";

        List<String> chunks = splitter.splitText(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(countTokens(chunk)).isLessThanOrEqualTo(chunkSize));
    }

    @Test
    @DisplayName("문장 중간에서 청크가 끝나지 않는다")
    void chunksEndAtSentenceBoundary() {
        EgovKoreanSentenceSplitter splitter = splitter(30);
        String text = "첫 번째 문장입니다. 두 번째 문장입니다! 세 번째 문장입니다? 마지막 문장입니다.";

        List<String> chunks = splitter.splitText(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(SENTENCE_ENDING.matcher(chunk).matches()).isTrue());
    }

    @Test
    @DisplayName("초장문 단일 문장은 기존 TokenTextSplitter 폴백으로 분할한다")
    void longSingleSentenceFallsBackToSuperSplitter() {
        int chunkSize = 30;
        EgovKoreanSentenceSplitter splitter = splitter(chunkSize);
        String text = "전자정부표준프레임워크문서청킹검증".repeat(80);

        List<String> chunks = splitter.splitText(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(countTokens(chunk)).isLessThanOrEqualTo(chunkSize));
    }

    @Test
    @DisplayName("apply 경로에서 Document metadata를 보존한다")
    void applyPreservesDocumentMetadata() {
        EgovKoreanSentenceSplitter splitter = splitter(30);
        Document document = new Document("문서 첫 문장입니다. 문서 두 번째 문장입니다.",
                Map.of("id", "doc-1", "file_name", "guide.md"));

        List<Document> chunks = splitter.apply(List.of(document));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("id", "doc-1")
                .containsEntry("file_name", "guide.md"));
    }

    @Test
    @DisplayName("같은 입력은 항상 같은 결과를 반환한다")
    void deterministicForSameInput() {
        EgovKoreanSentenceSplitter splitter = splitter(30);
        String text = "결정론을 검증합니다. 같은 입력은 같은 청크가 됩니다. 순서도 유지됩니다.";

        List<String> first = splitter.splitText(text);
        List<String> second = splitter.splitText(text);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("빈 텍스트 입력은 빈 결과를 반환한다")
    void blankTextReturnsEmptyResult() {
        EgovKoreanSentenceSplitter splitter = splitter(30);

        assertThat(splitter.splitText(null)).isEmpty();
        assertThat(splitter.splitText(" \n\t ")).isEmpty();
        assertThat(splitter.apply(List.of(new Document(" \n\t ")))).isEmpty();
    }

    @Test
    @DisplayName("minChunkLengthToEmbed 미만 청크는 버린다")
    void discardsChunksBelowMinimumLengthToEmbed() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(30, 10, 50, 100, true);

        List<String> chunks = splitter.splitText("짧습니다. 작습니다.");

        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("큰 chunkSize 에서는 마크다운 원문 byte 구조를 그대로 보존한다")
    void preservesOriginalMarkdownBytesWithLargeChunkSize() {
        String text = markdownFixture();
        EgovKoreanSentenceSplitter splitter = splitter(5000);

        List<String> chunks = splitter.splitText(text);

        assertThat(chunks).isNotEmpty();
        assertOriginalSubstrings(text, chunks);
        assertThat(chunks)
                .anySatisfy(chunk -> {
                    assertThat(chunk).contains("# 처리 기준");
                    assertThat(chunk).contains("## 상세 기준");
                    assertThat(chunk).contains("\n\n");
                    assertThat(chunk).contains("\n| 항목 | 기본값 |");
                    assertThat(chunk).contains("""
                            ```java
                            // 주석. 끝
                            int value = 1;
                            ```""");
                    assertThat(chunk).contains("\"즉시 조치한다.\"라고 말했다.");
                });
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).doesNotStartWith("라고");
            assertThat(chunk).doesNotStartWith("라며");
        });
    }

    @Test
    @DisplayName("작은 chunkSize 에서도 폴백 없는 청크는 원문 substring 으로 만든다")
    void preservesOriginalSubstringsWithSmallChunkSize() {
        String text = markdownFixture();
        EgovKoreanSentenceSplitter splitter = splitter(90);

        List<String> chunks = splitter.splitText(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertOriginalSubstrings(text, chunks);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).doesNotStartWith("라고");
            assertThat(chunk).doesNotStartWith("라며");
        });
    }

    private EgovKoreanSentenceSplitter splitter(int chunkSize) {
        return new EgovKoreanSentenceSplitter(chunkSize, 10, 0, 100, true);
    }

    private static int countTokens(String text) {
        return ENCODING.countTokens(text);
    }

    private void assertOriginalSubstrings(String source, List<String> chunks) {
        for (String chunk : chunks) {
            assertThat(source).contains(chunk);
        }
    }

    private String markdownFixture() {
        return """
                # 처리 기준

                ## 상세 기준

                담당자는 "즉시 조치한다."라고 말했다. 이 규정은 제3조제1항. 다음 조항을 따른다.

                1. 신청서 제출
                2. 접수 확인

                | 항목 | 기본값 |
                | --- | --- |
                | chunk | 500 |
                | overlap | 50 |
                | parser | markdown |

                ```java
                // 주석. 끝
                int value = 1;
                ```

                날짜는 2026. 7. 30. 기준입니다. 담당자는 A. B. 홍길동입니다.
                수치는 3.14이고 No. 1 양식을 따른다. 마지막 문장입니다.""";
    }

    @Test
    @DisplayName("토큰 한도를 넘는 구간도 원문 부분문자열로 잘라 나눈다")
    void keepsOriginalSubstringForOversizedSpan() {
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 60; i++) {
            code.append("    public void method").append(i).append("() {\n")
                    .append("        int value = ").append(i).append("; // 주석\n    }\n");
        }
        code.append("```\n");
        String text = code.toString();
        int chunkSize = 60;

        List<String> chunks = splitter(chunkSize).split(List.of(new Document(text))).stream()
                .map(Document::getText)
                .toList();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(text).contains(chunk);
            assertThat(countTokens(chunk)).isLessThanOrEqualTo(chunkSize);
        });
    }

    @Test
    @DisplayName("보충면 문자를 코드포인트 경계에서 잘라 짝 잃은 문자를 남기지 않는다")
    void doesNotSplitSurrogatePairs() {
        String text = "\uD840\uDC0B\uD842\uDF9F".repeat(120);
        int chunkSize = 40;

        List<String> chunks = splitter(chunkSize).split(List.of(new Document(text))).stream()
                .map(Document::getText)
                .toList();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(text).contains(chunk);
            assertThat(hasUnpairedSurrogate(chunk)).isFalse();
            assertThat(countTokens(chunk)).isLessThanOrEqualTo(chunkSize);
        });
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("보충면 문자와 작은 청크 크기 조합에서도 유한 시간에 끝난다")
    void terminatesForSupplementaryCharactersWithTinyChunkSize() {
        String text = "\uD840\uDC0B\uD842\uDF9F".repeat(120);

        for (int chunkSize : new int[] {1, 2, 3, 20}) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                List<String> chunks = splitter(chunkSize).split(List.of(new Document(text))).stream()
                        .map(Document::getText)
                        .toList();
                assertThat(chunks).isNotEmpty();
                assertThat(chunks).hasSizeLessThanOrEqualTo(text.length());
                assertThat(chunks).allSatisfy(chunk -> assertThat(text).contains(chunk));
            });
        }
    }
}
