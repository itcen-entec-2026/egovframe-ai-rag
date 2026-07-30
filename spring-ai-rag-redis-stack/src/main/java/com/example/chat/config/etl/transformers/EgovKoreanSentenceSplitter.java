package com.example.chat.config.etl.transformers;

import java.util.ArrayList;
import java.util.List;

import com.example.chat.util.EgovKoreanSentenceSupport;
import com.example.chat.util.EgovKoreanSentenceSupport.SentenceSpan;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * 한국어 문장 경계를 우선 보존하는 Spring AI 문서 분할기.
 *
 * <p>{@link EgovKoreanSentenceSupport} 로 문장 경계를 먼저 나눈 뒤, Spring AI
 * {@link TokenTextSplitter} 와 동일한 CL100K_BASE 토크나이저로 청크 크기를 판정한다.
 * 청크는 원문 부분문자열이며 개행·표 구조가 보존된다.
 * 단일 문장이 청크 한도를 넘으면 {@code super.splitText(...)} 로 기존 분할기 동작에
 * 위임하여 모델 입력 한도를 보장한다. 기존 {@link TokenTextSplitter} 와의 정합을 위해
 * 오버랩은 두지 않는다.</p>
 */
public class EgovKoreanSentenceSplitter extends TokenTextSplitter {

    private final Encoding encoding;
    private final int chunkSize;
    private final int minChunkLengthToEmbed;
    private final int maxNumChunks;

    public EgovKoreanSentenceSplitter(int chunkSize, int minChunkSizeChars,
            int minChunkLengthToEmbed, int maxNumChunks, boolean keepSeparator) {
        super(chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator);
        this.encoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
        this.chunkSize = chunkSize;
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
        this.maxNumChunks = maxNumChunks;
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<SentenceSpan> spans = EgovKoreanSentenceSupport.splitSentenceSpans(text);
        List<String> chunks = new ArrayList<>();
        int bufferStart = -1;
        int bufferEnd = -1;

        for (int i = 0; i < spans.size(); i++) {
            SentenceSpan span = spans.get(i);
            String sentence = text.substring(span.start(), span.end());
            if (countTokens(sentence) > chunkSize) {
                if (bufferStart >= 0) {
                    addSpanChunk(chunks, text, spans, bufferStart, bufferEnd);
                    bufferStart = -1;
                    bufferEnd = -1;
                }
                if (chunks.size() >= maxNumChunks) {
                    break;
                }
                addFallbackChunks(chunks, sentence);
                if (chunks.size() >= maxNumChunks) {
                    break;
                }
                continue;
            }

            if (bufferStart < 0) {
                bufferStart = i;
                bufferEnd = i;
                continue;
            }

            String candidate = text.substring(spans.get(bufferStart).start(), span.end());
            if (countTokens(candidate) <= chunkSize) {
                bufferEnd = i;
                continue;
            }

            addSpanChunk(chunks, text, spans, bufferStart, bufferEnd);
            if (chunks.size() >= maxNumChunks) {
                break;
            }
            bufferStart = i;
            bufferEnd = i;
        }

        if (chunks.size() < maxNumChunks && bufferStart >= 0) {
            addSpanChunk(chunks, text, spans, bufferStart, bufferEnd);
        }
        return List.copyOf(chunks);
    }

    private void addFallbackChunks(List<String> chunks, String sentence) {
        for (String fallbackChunk : super.splitText(sentence)) {
            addChunk(chunks, fallbackChunk);
            if (chunks.size() >= maxNumChunks) {
                return;
            }
        }
    }

    private void addSpanChunk(List<String> chunks, String text, List<SentenceSpan> spans,
            int startIndex, int endIndex) {
        addChunk(chunks, text.substring(spans.get(startIndex).start(), spans.get(endIndex).end()));
    }

    private void addChunk(List<String> chunks, String chunk) {
        if (chunks.size() >= maxNumChunks || chunk == null) {
            return;
        }

        String trimmed = chunk.trim();
        if (trimmed.length() > minChunkLengthToEmbed) {
            chunks.add(trimmed);
        }
    }

    private int countTokens(String text) {
        return encoding.countTokens(text);
    }
}
