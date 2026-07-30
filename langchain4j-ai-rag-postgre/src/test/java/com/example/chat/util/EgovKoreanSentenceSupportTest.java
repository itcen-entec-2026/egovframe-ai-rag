package com.example.chat.util;

import com.example.chat.util.EgovKoreanSentenceSupport.SentenceSpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EgovKoreanSentenceSupport} 단위 테스트.
 */
class EgovKoreanSentenceSupportTest {

    @Test
    @DisplayName("종결부호로 한국어 문장을 분리한다")
    void splitKoreanSentencesByTerminators() {
        String text = "안녕하세요. 반갑습니다! 무엇일까요?";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly("안녕하세요.", "반갑습니다!", "무엇일까요?");
    }

    @Test
    @DisplayName("날짜 표기·라틴 이니셜은 문장 파편으로 자르지 않는다")
    void keepDateAndLatinInitialInSameSentence() {
        assertThat(EgovKoreanSentenceSupport.splitSentences("회의는 2026. 7. 30. 개최한다. 참석자는 다음과 같다."))
                .containsExactly("회의는 2026. 7. 30. 개최한다.", "참석자는 다음과 같다.");
        assertThat(EgovKoreanSentenceSupport.splitSentences("담당자는 A. B. 홍길동입니다. 확인 바랍니다."))
                .containsExactly("담당자는 A. B. 홍길동입니다.", "확인 바랍니다.");
    }

    @Test
    @DisplayName("종결어미로 끝난 문장은 다음 문장이 종결어미와 같은 글자로 시작해도 분리한다")
    void splitSentenceBeforeWordStartingWithEndingSyllable() {
        assertThat(EgovKoreanSentenceSupport.splitSentences("보고서를 제출한다. 하고자 하는 업무는 별도로 정한다."))
                .containsExactly("보고서를 제출한다.", "하고자 하는 업무는 별도로 정한다.");
    }

    @Test
    @DisplayName("소수점과 버전 표기는 문장 경계로 보지 않는다")
    void keepDecimalAndVersionInSameSentence() {
        String text = "값은 3.14입니다. 버전은 1.0.1입니다.";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly("값은 3.14입니다.", "버전은 1.0.1입니다.");
    }

    @Test
    @DisplayName("라틴 약어 뒤 마침표는 문장 경계로 보지 않는다")
    void keepAbbreviationsInSameSentence() {
        String text = "번호는 No. 1입니다. 기타 etc. 항목입니다. 예: e.g. 형식입니다. 지역은 U.S. 기준입니다.";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly(
                "번호는 No. 1입니다.",
                "기타 etc. 항목입니다.",
                "예: e.g. 형식입니다.",
                "지역은 U.S. 기준입니다."
        );
    }

    @Test
    @DisplayName("줄 시작 목록 번호와 제+숫자 표기만 문장 경계로 보지 않는다")
    void keepNumberedListMarkersInSameSentence() {
        String text = """
                1. 항목입니다.
                  - 2. 하위 항목입니다.
                제1. 서수 항목입니다.
                이 규정은 제3조제1항. 다음 조항을 따른다. 적용 범위는 제2조. 세부는 별표 참조. 총 금액은 100. 이는 부가세 별도다. 제2항. 설명입니다.""";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        // 종결어미가 아닌 글자·숫자 뒤 마침표는 경계로 보지 않는다. 두 문장이 한 구간에 남아도
        // 청크는 원문 offset을 잘라내므로 내용이 훼손되지 않는다.
        assertThat(sentences).containsExactly(
                "1. 항목입니다.",
                "- 2. 하위 항목입니다.",
                "제1. 서수 항목입니다.",
                "이 규정은 제3조제1항. 다음 조항을 따른다.",
                "적용 범위는 제2조. 세부는 별표 참조. 총 금액은 100. 이는 부가세 별도다.",
                "제2항. 설명입니다."
        );
    }

    @Test
    @DisplayName("연속 마침표는 하나의 경계만 만들고 빈 문장을 만들지 않는다")
    void keepTerminatorRunTogether() {
        String text = "끝났다... 다음";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly("끝났다...", "다음");
        assertThat(sentences).doesNotContain("");
    }

    @Test
    @DisplayName("공백 없는 한국어 종결어미 뒤 문장을 분리한다")
    void splitNoSpaceAfterKoreanEnding() {
        String text = "처리를 완료합니다.다음 문장입니다.";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly("처리를 완료합니다.", "다음 문장입니다.");
    }

    @Test
    @DisplayName("빈 줄은 경계이고 하드랩 단일 개행은 경계가 아니다")
    void splitBlankLineButKeepHardWrappedLine() {
        String text = "첫 줄입니다\n이어지는 줄입니다.\n\n새 문단입니다.";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly("첫 줄입니다\n이어지는 줄입니다.", "새 문단입니다.");
    }

    @Test
    @DisplayName("마크다운 제목 줄은 본문과 분리한다")
    void splitMarkdownHeadingLine() {
        String text = "# 제목\n본문입니다.";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly("# 제목", "본문입니다.");
    }

    @Test
    @DisplayName("문장 구간은 오름차순이고 구간 사이에는 공백만 남는다")
    void splitSentenceSpansKeepOrderedWhitespaceOnlyGaps() {
        String text = "  # 제목\n본문입니다.\n\n담당자는 \"즉시 조치한다.\"라고 말했다.\n```java\n// 주석. 끝\n```\n마지막입니다.  ";

        List<SentenceSpan> spans = EgovKoreanSentenceSupport.splitSentenceSpans(text);
        List<String> spanTexts = new ArrayList<>();

        assertThat(spans).isNotEmpty();
        assertThat(text.substring(0, spans.get(0).start()).trim()).isEmpty();
        for (int i = 0; i < spans.size(); i++) {
            SentenceSpan span = spans.get(i);
            assertThat(span.start()).isLessThan(span.end());
            spanTexts.add(text.substring(span.start(), span.end()));
            if (i + 1 < spans.size()) {
                SentenceSpan next = spans.get(i + 1);
                assertThat(span.end()).isLessThanOrEqualTo(next.start());
                assertThat(text.substring(span.end(), next.start()).trim()).isEmpty();
            }
        }
        assertThat(text.substring(spans.get(spans.size() - 1).end()).trim()).isEmpty();
        assertThat(EgovKoreanSentenceSupport.splitSentences(text)).isEqualTo(spanTexts);
    }

    @Test
    @DisplayName("인용문 뒤 조사는 같은 문장으로 유지하고 실제 다음 문장은 분리한다")
    void keepQuoteParticlesWithQuotedSentence() {
        String text = "담당자는 \"즉시 조치한다.\"라고 말했다. 다음 담당자는 \"교체한다.\" 라며 보고했다.";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly(
                "담당자는 \"즉시 조치한다.\"라고 말했다.",
                "다음 담당자는 \"교체한다.\" 라며 보고했다."
        );
    }

    @Test
    @DisplayName("코드펜스 내부 문장부호는 분리하지 않고 앞뒤 산문만 분리한다")
    void keepCodeFenceBlockAsSingleSpan() {
        String text = """
                앞 문장입니다.
                ```java
                // 주석. 끝
                int value = 1;
                ```
                뒤 문장입니다.""";

        List<String> sentences = EgovKoreanSentenceSupport.splitSentences(text);

        assertThat(sentences).containsExactly(
                "앞 문장입니다.",
                "```java\n// 주석. 끝\nint value = 1;\n```",
                "뒤 문장입니다."
        );
    }

    @Test
    @DisplayName("null 또는 공백 입력은 빈 목록을 반환한다")
    void returnEmptyListForBlankInput() {
        assertThat(EgovKoreanSentenceSupport.splitSentences(null)).isEmpty();
        assertThat(EgovKoreanSentenceSupport.splitSentences(" \n\t ")).isEmpty();
    }

    @Test
    @DisplayName("분리한 문장은 공백 제거 기준으로 원문을 무손실 재구성한다")
    void splitSentencesAreLosslessIgnoringWhitespace() {
        List<String> cases = List.of(
                "안녕하세요. 반갑습니다! 무엇일까요?",
                "값은 3.14입니다. 버전은 1.0.1입니다.",
                "번호는 No. 1입니다. 기타 etc. 항목입니다. 예: e.g. 형식입니다. 지역은 U.S. 기준입니다.",
                "1. 항목입니다.\n  - 2. 하위 항목입니다.\n제1. 서수 항목입니다.",
                "이 규정은 제3조제1항. 다음 조항을 따른다. 적용 범위는 제2조. 세부는 별표 참조. 총 금액은 100. 이는 부가세 별도다.",
                "끝났다... 다음",
                "처리를 완료합니다.다음 문장입니다.",
                "첫 줄입니다\n이어지는 줄입니다.\n\n새 문단입니다.",
                "# 제목\n본문입니다.",
                "담당자는 \"즉시 조치한다.\"라고 말했다.",
                "앞 문장입니다.\n```java\n// 주석. 끝\nint value = 1;\n```\n뒤 문장입니다."
        );

        for (String text : cases) {
            String rebuilt = String.join("", EgovKoreanSentenceSupport.splitSentences(text));
            assertThat(removeWhitespace(rebuilt)).isEqualTo(removeWhitespace(text));
        }
    }

    private String removeWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }
}
