package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.DocumentTransformer;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 문서 변환기
 * - 문서 분할 (토큰 기반)
 */
@Slf4j
@Component
public class EgovEnhancedDocumentTransformer implements DocumentTransformer {

    private final DocumentSplitter documentSplitter;
    private final int minChunkLengthToEmbed;
    private final int maxNumChunks;

    public EgovEnhancedDocumentTransformer(
            @Value("${document.chunk-size}") int chunkSize,
            @Value("${document.min-chunk-size-chars}") int minChunkSizeChars,
            @Value("${document.min-chunk-length-to-embed}") int minChunkLengthToEmbed,
            @Value("${document.max-num-chunks}") int maxNumChunks,
            ObjectProvider<EgovKoreanSentenceSplitter> koreanSentenceSplitterProvider) {

        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
        this.maxNumChunks = maxNumChunks;

        // LangChain4j의 DocumentSplitter 생성
        // 문자 기반 분할
        EgovKoreanSentenceSplitter koreanSentenceSplitter = koreanSentenceSplitterProvider.getIfAvailable();
        this.documentSplitter = koreanSentenceSplitter != null ? koreanSentenceSplitter : DocumentSplitters.recursive(
                chunkSize, // 최대 문자 수
                Math.max(chunkSize / 10, 50) // 오버랩 문자 수 (청크 크기의 10%)
        );

        if (koreanSentenceSplitter != null) {
            log.info("문서 분할기 선택 - splitter: {}", this.documentSplitter.getClass().getSimpleName());
        }
        log.info("EnhancedDocumentTransformer 초기화 - chunkSize: {}, minChunkSize: {}, minChunkLengthToEmbed: {}, maxNumChunks: {}",
                chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks);
    }

    @Override
    public Document transform(Document document) {
        // 단일 문서 변환은 transformAll을 호출
        List<Document> result = transformAll(List.of(document));
        return result.isEmpty() ? document : result.get(0);
    }

    @Override
    public List<Document> transformAll(List<Document> documents) {
        log.info("문서 변환 시작: {}개 문서", documents.size());

        // 문서별 크기 로깅
        for (Document doc : documents) {
            String content = doc.text();
            if (content != null) {
                int estimatedTokens = content.length() / 4;
                log.info("문서 ID: {} - 크기: {}바이트, 추정 토큰 수: {}",
                        doc.metadata().getString("id"), content.length(), estimatedTokens);
            }
        }

        // 문서 분할
        log.info("문서 분할 시작...");
        List<Document> splitDocs = new ArrayList<>();
        for (Document doc : documents) {
            List<TextSegment> segments = documentSplitter.split(doc);
            // TextSegment를 Document로 변환하면서 설정된 청크 한도를 적용한다.
            int kept = 0;
            for (TextSegment segment : segments) {
                if (kept >= maxNumChunks) {
                    break;
                }
                String text = segment.text();
                if (text == null || text.trim().length() <= minChunkLengthToEmbed) {
                    continue;
                }
                splitDocs.add(Document.from(text, segment.metadata()));
                kept++;
            }
        }
        log.info("문서 분할 완료: {}개 청크 생성", splitDocs.size());

        // 분할된 청크 크기 로깅
        for (int i = 0; i < splitDocs.size(); i++) {
            Document chunk = splitDocs.get(i);
            String content = chunk.text();
            if (content != null) {
                int estimatedTokens = content.length() / 4;
                log.info("청크 {} - 크기: {}바이트, 추정 토큰 수: {}",
                        i + 1, content.length(), estimatedTokens);
            }
        }

        return splitDocs;
    }
}
