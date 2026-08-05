package com.example.chat.eval;

import com.example.chat.config.etl.transformers.EgovKoreanSentenceSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 행정문서 반례에서 한국어 문장 경계 splitter가 원문 구조와 인용 조사 경계를 보존하는지 확인한다.
 *
 * <p>recursive 수치는 비교를 위한 관측값이며 단언 대상이 아니다.</p>
 */
class EgovKoreanSentenceChunkingQualityTest {

    private static final Logger log = LoggerFactory.getLogger(EgovKoreanSentenceChunkingQualityTest.class);

    private static final int CHUNK_SIZE = 300;
    private static final String PIPE_TABLE = """
            | 항목 | 기본값 | 설명 |
            | --- | --- | --- |
            | 보관기간 | 30일 | 신청 로그를 보존합니다. |
            | 재시도 | 3회 | 장애 시 다시 수행합니다. |
            """;
    private static final String CODE_FENCE = """
            ```java
            // 주석. 끝
            String ticket = "No. 5";
            ```
            """;
    private static final String COUNTEREXAMPLE_DOCUMENT = """
            # 민원 처리 기준

            담당자는 "즉시 조치한다."라고 말했다. 접수자는 2026. 7. 30. 이전 신청 내역을 확인합니다.
            이 규정은 제3조제1항. 다음 조항을 따른다. A. B. 홍길동 검토관은 비율 3.14와 No. 5, etc. 표기를 보존합니다.

            ## 처리 옵션

            """
            + PIPE_TABLE
            + """

            """
            + CODE_FENCE
            + """

            1. 신청서 제출
            2. 담당 부서 검토

            인용문은 "자료를 보완한다."라며 끝나지 않고 다음 설명과 함께 유지됩니다.
            본문은 표와 코드펜스 뒤에서도 행정 문서의 원문 개행을 그대로 포함해야 합니다.
            제목 다음 문단은 고아 제목을 과대 해석하지 않도록 실제 마지막 비공백 줄만 측정합니다.
            처리 결과 통지는 신청인의 연락처와 전자문서 수신 동의 여부를 함께 확인한 뒤 발송합니다.
            담당 부서는 보완 요청 사유, 제출 기한, 미제출 시 처리 기준을 같은 문서 안에 명확히 적습니다.
            감사 기록은 업무 담당자, 승인자, 변경 일시를 포함하며 추후 이의신청 검토 자료로 활용합니다.
            """;

    @Test
    @DisplayName("행정문서 반례에서 원문 substring, 표 개행, 코드펜스, 인용 조사 경계를 보존한다")
    void preservesOriginalMarkdownStructuresInCounterexample() {
        List<TextSegment> recursiveSegments = split(recursiveSplitter());
        List<TextSegment> koreanSegments = split(koreanSplitter());

        ComparisonMetrics recursive = measure("recursive", recursiveSegments);
        ComparisonMetrics korean = measure("korean", koreanSegments);

        log.info("[반례] chunkSize={} strategy={} 청크수={} 평균길이={} 충전율={} 제목고아율={} 원문일치율={}",
                CHUNK_SIZE, recursive.strategy(), recursive.chunkCount(), formatInteger(recursive.averageChunkLength()),
                format(recursive.fillRatio()), format(recursive.headingOnlyTailRatio()),
                format(recursive.originalSubstringRatio()));
        log.info("[반례] chunkSize={} strategy={} 청크수={} 평균길이={} 충전율={} 제목고아율={} 원문일치율={}",
                CHUNK_SIZE, korean.strategy(), korean.chunkCount(), formatInteger(korean.averageChunkLength()),
                format(korean.fillRatio()), format(korean.headingOnlyTailRatio()),
                format(korean.originalSubstringRatio()));

        assertThat(recursiveSegments)
                .allMatch(segment -> segment.text().length() <= CHUNK_SIZE);

        assertThat(koreanSegments)
                .hasSizeGreaterThanOrEqualTo(3)
                .allMatch(segment -> segment.text().length() <= CHUNK_SIZE)
                .allMatch(segment -> COUNTEREXAMPLE_DOCUMENT.contains(segment.text()))
                .anyMatch(segment -> segment.text().contains(PIPE_TABLE))
                .anyMatch(segment -> segment.text().contains(CODE_FENCE))
                .noneMatch(this::startsWithQuotationParticle);
    }

    private List<TextSegment> split(DocumentSplitter splitter) {
        Metadata metadata = new Metadata();
        metadata.put("id", "counterexample.md");
        metadata.put("source", "counterexample.md");
        metadata.put("file_name", "counterexample.md");
        return splitter.split(Document.from(COUNTEREXAMPLE_DOCUMENT, metadata));
    }

    private ComparisonMetrics measure(String strategy, List<TextSegment> segments) {
        double averageChunkLength = averageChunkLength(segments);
        return new ComparisonMetrics(strategy, segments.size(), averageChunkLength, averageChunkLength / CHUNK_SIZE,
                headingOnlyTailRatio(segments), originalSubstringRatio(segments));
    }

    private double averageChunkLength(List<TextSegment> segments) {
        return segments.stream()
                .mapToInt(segment -> segment.text().length())
                .average()
                .orElse(0.0);
    }

    private double headingOnlyTailRatio(List<TextSegment> segments) {
        long headingTailCount = segments.stream()
                .map(TextSegment::text)
                .filter(this::hasHeadingAsLastNonBlankLine)
                .count();
        return (double) headingTailCount / segments.size();
    }

    private boolean hasHeadingAsLastNonBlankLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line.startsWith("#");
            }
        }
        return false;
    }

    private double originalSubstringRatio(List<TextSegment> segments) {
        long originalSubstringCount = segments.stream()
                .filter(segment -> COUNTEREXAMPLE_DOCUMENT.contains(segment.text()))
                .count();
        return (double) originalSubstringCount / segments.size();
    }

    private boolean startsWithQuotationParticle(TextSegment segment) {
        String text = segment.text().stripLeading();
        return text.startsWith("라고") || text.startsWith("라며");
    }

    private DocumentSplitter recursiveSplitter() {
        return DocumentSplitters.recursive(CHUNK_SIZE, overlapSize());
    }

    private DocumentSplitter koreanSplitter() {
        return new EgovKoreanSentenceSplitter(CHUNK_SIZE, overlapSize());
    }

    private int overlapSize() {
        return Math.max(CHUNK_SIZE / 10, 50);
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String formatInteger(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private record ComparisonMetrics(
            String strategy,
            int chunkCount,
            double averageChunkLength,
            double fillRatio,
            double headingOnlyTailRatio,
            double originalSubstringRatio) {
    }
}
