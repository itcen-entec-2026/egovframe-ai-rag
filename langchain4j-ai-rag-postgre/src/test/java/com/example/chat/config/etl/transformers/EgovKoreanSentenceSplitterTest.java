package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EgovKoreanSentenceSplitter} 단위 테스트.
 */
class EgovKoreanSentenceSplitterTest {

    @Test
    @DisplayName("세그먼트 길이는 최대 문자 수를 넘지 않는다")
    void splitSegmentsDoNotExceedMaxSize() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(25, 0);
        Document document = Document.from("첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다. 네 번째 문장입니다.");

        List<TextSegment> segments = splitter.split(document);

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text()).hasSizeLessThanOrEqualTo(25));
    }

    @Test
    @DisplayName("일반 세그먼트는 문장 중간에서 끝나지 않는다")
    void regularSegmentsEndAtSentenceBoundary() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(25, 0);
        Document document = Document.from("첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다. 네 번째 문장입니다.");

        List<TextSegment> segments = splitter.split(document);

        assertThat(segments).allSatisfy(segment -> assertThat(endsAtSentenceBoundary(segment.text())).isTrue());
    }

    @Test
    @DisplayName("최대 크기를 넘는 단일 초장문 문장은 폴백 분할기로 나눈다")
    void longSingleSentenceUsesFallbackSplitter() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(40, 0);
        String longSentence = "가나다라마바사 ".repeat(20) + "처리를 완료합니다.";
        Document document = Document.from(longSentence);

        List<TextSegment> segments = splitter.split(document);

        assertThat(segments).hasSizeGreaterThan(1);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text()).hasSizeLessThanOrEqualTo(40));
    }

    @Test
    @DisplayName("원문 metadata 를 보존하고 index 를 0부터 순서대로 부여한다")
    void preserveMetadataAndAssignIndex() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(25, 0);
        Metadata metadata = Metadata.from("id", "doc-1");
        metadata.put("file_name", "sample.md");
        Document document = Document.from("첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다.", metadata);

        List<TextSegment> segments = splitter.split(document);

        for (int i = 0; i < segments.size(); i++) {
            Metadata segmentMetadata = segments.get(i).metadata();
            assertThat(segmentMetadata.getString("id")).isEqualTo("doc-1");
            assertThat(segmentMetadata.getString("file_name")).isEqualTo("sample.md");
            assertThat(segmentMetadata.getString("index")).isEqualTo(String.valueOf(i));
        }
    }

    @Test
    @DisplayName("같은 입력은 항상 같은 결과를 반환한다")
    void splitIsDeterministic() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(25, 5);
        Metadata metadata = Metadata.from("id", "doc-1");
        Document document = Document.from("첫 문장입니다. 두 번째 문장입니다. 세 번째 문장입니다.", metadata);

        List<TextSegment> first = splitter.split(document);
        List<TextSegment> second = splitter.split(document);

        assertThat(first).extracting(TextSegment::text).isEqualTo(second.stream().map(TextSegment::text).toList());
        assertThat(first).extracting(segment -> segment.metadata().toMap())
                .isEqualTo(second.stream().map(segment -> segment.metadata().toMap()).toList());
    }

    @Test
    @DisplayName("빈 문서는 빈 세그먼트 목록을 반환한다")
    void returnEmptyListForBlankDocument() {
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(25, 0);

        assertThat(splitter.split(documentWithText(null))).isEmpty();
        assertThat(splitter.split(documentWithText(" \n\t "))).isEmpty();
    }

    @Test
    @DisplayName("큰 chunkSize 에서는 마크다운 원문 substring 을 그대로 보존한다")
    void preserveOriginalMarkdownBytesWithLargeChunkSize() {
        String text = markdownFixture();
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(5000, 0);

        List<TextSegment> segments = splitter.split(Document.from(text));

        assertThat(segments).isNotEmpty();
        assertOrderedSubstrings(text, segments);
        assertThat(segments).extracting(TextSegment::text)
                .anySatisfy(segmentText -> {
                    assertThat(segmentText).contains("\n| 항목 | 기본값 |");
                    assertThat(segmentText).contains("""
                            ```java
                            // 주석. 끝
                            int value = 1;
                            ```""");
                    assertThat(segmentText).contains("\"즉시 조치한다.\"라고");
                });
    }

    @Test
    @DisplayName("작은 chunkSize 에서도 폴백 없는 청크는 원문 substring 으로 만든다")
    void preserveOriginalMarkdownSubstringsWithSmallChunkSize() {
        String text = markdownFixture();
        EgovKoreanSentenceSplitter splitter = new EgovKoreanSentenceSplitter(300, 0);

        List<TextSegment> segments = splitter.split(Document.from(text));

        assertThat(segments).hasSizeGreaterThan(1);
        assertOrderedSubstrings(text, segments);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text()).hasSizeLessThanOrEqualTo(300));
    }

    private boolean endsAtSentenceBoundary(String text) {
        if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")
                || text.endsWith("。") || text.endsWith("！") || text.endsWith("？")) {
            return true;
        }

        char last = text.charAt(text.length() - 1);
        return last == '다' || last == '요' || last == '까' || last == '죠' || last == '음'
                || last == '임' || last == '함' || last == '네' || last == '오';
    }

    private Document documentWithText(String text) {
        return new Document() {
            @Override
            public String text() {
                return text;
            }

            @Override
            public Metadata metadata() {
                return new Metadata();
            }
        };
    }

    private void assertOrderedSubstrings(String source, List<TextSegment> segments) {
        int previousIndex = 0;
        for (TextSegment segment : segments) {
            String segmentText = segment.text();
            assertThat(source).contains(segmentText);
            int foundIndex = source.indexOf(segmentText, previousIndex);
            assertThat(foundIndex).isGreaterThanOrEqualTo(previousIndex);
            previousIndex = foundIndex;
        }
    }

    private String markdownFixture() {
        return """
                # 처리 기준

                담당자는 "즉시 조치한다."라고 말했다. 이 규정은 제3조제1항. 다음 조항을 따른다.

                | 항목 | 기본값 |
                | --- | --- |
                | chunk | 500 |
                | overlap | 50 |
                | parser | markdown |

                ```java
                // 주석. 끝
                int value = 1;
                ```

                날짜는 2026. 7. 30. 기준입니다. 담당자는 A. B. 홍길동입니다. 마지막 문장입니다.
                추가 설명은 표와 코드펜스 뒤에서도 원문 순서를 유지하는지 확인한다. 또 다른 문장은 작은 chunkSize 에서 다음 청크로 이동한다.""";
    }

    @Test
    @DisplayName("청크 한도를 넘는 구간도 원문 부분문자열로 잘라 나눈다")
    void keepsOriginalSubstringForOversizedSpan() {
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 60; i++) {
            code.append("    public void method").append(i).append("() {\n")
                    .append("        int value = ").append(i).append("; // 주석\n    }\n");
        }
        code.append("```\n");
        String text = code.toString();

        List<TextSegment> segments = new EgovKoreanSentenceSplitter(300, 50)
                .split(Document.from(text, new Metadata()));

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment -> {
            assertThat(text).contains(segment.text());
            assertThat(segment.text().length()).isLessThanOrEqualTo(300);
        });
    }

    @Test
    @DisplayName("오버랩이 청크 크기 이상이면 생성 시점에 거부한다")
    void rejectsOverlapNotSmallerThanChunkSize() {
        assertThatThrownBy(() -> new EgovKoreanSentenceSplitter(100, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgovKoreanSentenceSplitter(100, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgovKoreanSentenceSplitter(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("공백 없는 긴 문서도 청크 수가 문서 길이에 비례해 늘지 않는다")
    void doesNotExplodeChunkCountForTextWithoutWhitespace() {
        String text = "가".repeat(1000);

        List<TextSegment> segments = new EgovKoreanSentenceSplitter(100, 30)
                .split(Document.from(text, new Metadata()));

        assertThat(segments).hasSizeLessThanOrEqualTo(20);
        assertThat(segments).allSatisfy(segment -> assertThat(text).contains(segment.text()));
    }

    @Test
    @DisplayName("보충면 문자를 코드포인트 경계에서 잘라 짝 잃은 문자를 남기지 않는다")
    void doesNotSplitSurrogatePairs() {
        String text = "\uD840\uDC0B\uD842\uDF9F".repeat(120);

        List<TextSegment> segments = new EgovKoreanSentenceSplitter(99, 9)
                .split(Document.from(text, new Metadata()));

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment -> {
            assertThat(text).contains(segment.text());
            assertThat(hasUnpairedSurrogate(segment.text())).isFalse();
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
        String text = "\uD840\uDC0B".repeat(500);

        for (int chunkSize : new int[] {1, 2, 3, 100}) {
            int overlap = chunkSize / 2;
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                List<TextSegment> segments = new EgovKoreanSentenceSplitter(chunkSize, overlap)
                        .split(Document.from(text, new Metadata()));
                assertThat(segments).isNotEmpty();
                assertThat(segments).hasSizeLessThanOrEqualTo(text.length());
                assertThat(segments).allSatisfy(segment -> assertThat(text).contains(segment.text()));
            });
        }
    }

    @Test
    @DisplayName("오버랩이 청크 크기의 절반을 넘으면 생성 시점에 거부한다")
    void rejectsOverlapLargerThanHalfChunkSize() {
        assertThatThrownBy(() -> new EgovKoreanSentenceSplitter(100, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgovKoreanSentenceSplitter(100, 90))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
