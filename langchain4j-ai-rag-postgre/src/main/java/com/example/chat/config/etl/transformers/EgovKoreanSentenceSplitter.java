package com.example.chat.config.etl.transformers;

import com.example.chat.util.EgovKoreanSentenceSupport;
import com.example.chat.util.EgovKoreanSentenceSupport.SentenceSpan;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 한국어 문장 경계를 우선 보존하는 문서 분할기.
 */
public class EgovKoreanSentenceSplitter implements DocumentSplitter {

    private final int maxSegmentSizeInChars;
    private final int maxOverlapSizeInChars;

    public EgovKoreanSentenceSplitter(int maxSegmentSizeInChars, int maxOverlapSizeInChars) {
        if (maxSegmentSizeInChars <= 0) {
            throw new IllegalArgumentException("maxSegmentSizeInChars must be greater than 0");
        }
        if (maxOverlapSizeInChars < 0 || maxOverlapSizeInChars >= maxSegmentSizeInChars) {
            throw new IllegalArgumentException(
                    "maxOverlapSizeInChars must be at least 0 and less than maxSegmentSizeInChars");
        }
        this.maxSegmentSizeInChars = maxSegmentSizeInChars;
        this.maxOverlapSizeInChars = maxOverlapSizeInChars;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> segmentTexts = new ArrayList<>();
        List<SentenceSpan> spans = EgovKoreanSentenceSupport.splitSentenceSpans(text);
        int bufferStart = -1;
        int bufferEnd = -1;
        int lastSegmentStart = -1;
        int lastSegmentEnd = -1;

        for (int i = 0; i < spans.size(); i++) {
            SentenceSpan span = spans.get(i);
            if (span.end() - span.start() > maxSegmentSizeInChars) {
                if (bufferStart >= 0) {
                    segmentTexts.add(segmentText(text, spans, bufferStart, bufferEnd));
                    lastSegmentStart = bufferStart;
                    lastSegmentEnd = bufferEnd;
                    bufferStart = -1;
                    bufferEnd = -1;
                }

                addOversizedSpanChunks(segmentTexts, text, span);
                lastSegmentStart = -1;
                lastSegmentEnd = -1;
                continue;
            }

            if (bufferStart < 0) {
                bufferStart = i;
                bufferEnd = i;
                continue;
            }

            if (canAppend(spans, bufferStart, i)) {
                bufferEnd = i;
                continue;
            }

            segmentTexts.add(segmentText(text, spans, bufferStart, bufferEnd));
            lastSegmentStart = bufferStart;
            lastSegmentEnd = bufferEnd;

            bufferStart = overlapStart(spans, lastSegmentStart, lastSegmentEnd);
            bufferEnd = bufferStart >= 0 ? lastSegmentEnd : -1;
            while (bufferStart >= 0 && spans.get(i).end() - spans.get(bufferStart).start() > maxSegmentSizeInChars) {
                bufferStart++;
                if (bufferStart > bufferEnd) {
                    bufferStart = -1;
                    bufferEnd = -1;
                    break;
                }
            }
            if (bufferStart < 0) {
                bufferStart = i;
            }
            bufferEnd = i;
        }

        if (bufferStart >= 0) {
            segmentTexts.add(segmentText(text, spans, bufferStart, bufferEnd));
        }

        List<TextSegment> segments = new ArrayList<>(segmentTexts.size());
        for (int i = 0; i < segmentTexts.size(); i++) {
            Metadata metadata = document.metadata().copy();
            metadata.put("index", String.valueOf(i));
            segments.add(TextSegment.from(segmentTexts.get(i), metadata));
        }
        return List.copyOf(segments);
    }

    // 한 문장이 청크 한도를 넘으면 원문 offset 창으로 잘라 나눈다. 가능하면 공백 위치로 물러나 자르고,
    // 공백이 없으면 한도에서 끊는다. 어느 경우에도 청크는 원문 부분문자열로 남는다.
    private void addOversizedSpanChunks(List<String> segmentTexts, String text, SentenceSpan span) {
        int cursor = span.start();
        while (cursor < span.end()) {
            int limit = Math.min(cursor + maxSegmentSizeInChars, span.end());
            int cut = limit;
            if (limit < span.end()) {
                int candidate = limit;
                while (candidate > cursor && !Character.isWhitespace(text.charAt(candidate))) {
                    candidate--;
                }
                if (candidate > cursor) {
                    cut = candidate;
                }
            }

            cut = alignToCodePointBoundary(text, cursor, cut);

            String chunk = text.substring(cursor, cut).strip();
            if (!chunk.isEmpty()) {
                segmentTexts.add(chunk);
            }

            if (cut >= span.end()) {
                return;
            }
            int next = maxOverlapSizeInChars > 0
                    ? Math.max(cursor + 1, cut - maxOverlapSizeInChars)
                    : cut;
            // 오버랩 재개 위치도 코드포인트 경계에 맞춘다. 페어 중간에서 시작하면 짝 잃은 문자가 남는다.
            cursor = alignToCodePointBoundary(text, cursor, next);
        }
    }

    // 서로게이트 페어 중간에서 자르면 짝 잃은 문자가 남으므로 코드포인트 경계로 물러난다.
    private static int alignToCodePointBoundary(String text, int start, int cut) {
        if (cut > start && cut < text.length() && Character.isLowSurrogate(text.charAt(cut))
                && Character.isHighSurrogate(text.charAt(cut - 1))) {
            return cut - 1;
        }
        return cut;
    }

    private boolean canAppend(List<SentenceSpan> spans, int startIndex, int nextIndex) {
        return spans.get(nextIndex).end() - spans.get(startIndex).start() <= maxSegmentSizeInChars;
    }

    private int overlapStart(List<SentenceSpan> spans, int segmentStart, int segmentEnd) {
        if (maxOverlapSizeInChars <= 0 || segmentStart < 0 || segmentEnd < segmentStart) {
            return -1;
        }

        int segmentTextEnd = spans.get(segmentEnd).end();
        int overlapStart = -1;
        for (int i = segmentEnd; i >= segmentStart; i--) {
            // 오버랩은 원문 substring 이 되도록 시작 구간의 offset부터 이어 붙인다.
            if (segmentTextEnd - spans.get(i).start() > maxOverlapSizeInChars) {
                break;
            }
            overlapStart = i;
        }
        return overlapStart;
    }

    private String segmentText(String text, List<SentenceSpan> spans, int startIndex, int endIndex) {
        return text.substring(spans.get(startIndex).start(), spans.get(endIndex).end());
    }
}
