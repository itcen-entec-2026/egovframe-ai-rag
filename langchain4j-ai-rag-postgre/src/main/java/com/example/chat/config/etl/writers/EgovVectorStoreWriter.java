package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 벡터 저장소 Writer
 * 문서를 임베딩하여 PGVector에 저장
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EgovVectorStoreWriter {

    private static final int DELETE_BATCH_SIZE = 200;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    /**
     * 문서를 임베딩하여 벡터 저장소에 저장
     */
    public void write(List<Document> documents) {
        log.info("벡터 저장소에 {}개 문서 저장 시작", documents.size());

        if (documents.isEmpty()) {
            log.warn("저장할 문서가 없습니다.");
            return;
        }

        // 문서 정보 로깅
        for (int i = 0; i < Math.min(documents.size(), 3); i++) {
            Document doc = documents.get(i);
            log.debug("문서 {}: ID={}, 크기={}바이트",
                    i, doc.metadata().getString("id"), doc.text().length());
        }

        try {
            // 문서를 TextSegment로 변환하고 배치 임베딩 생성
            List<TextSegment> segments = new ArrayList<>();

            for (Document doc : documents) {
                segments.add(TextSegment.from(doc.text(), doc.metadata()));
            }

            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 같은 문서 id의 기존 벡터를 먼저 삭제하여 재색인 시 stale 청크 누적을 방지
            List<String> documentIds = segments.stream()
                    .map(segment -> segment.metadata().getString("id"))
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();

            if (documentIds.isEmpty()) {
                log.warn("삭제 대상 문서 id가 없어 기존 벡터 삭제를 건너뜁니다. 저장은 계속 진행합니다.");
            } else {
                log.info("기존 벡터 삭제 시작: 대상 문서 id {}건, 입력 문서 {}개", documentIds.size(), documents.size());
                for (int from = 0; from < documentIds.size(); from += DELETE_BATCH_SIZE) {
                    int to = Math.min(from + DELETE_BATCH_SIZE, documentIds.size());
                    List<String> idBatch = documentIds.subList(from, to);
                    embeddingStore.removeAll(metadataKey("id").isIn(idBatch));
                }
                log.info("기존 벡터 삭제 완료: 대상 문서 id {}건, 입력 문서 {}개", documentIds.size(), documents.size());
            }

            // 벡터 저장소에 저장
            embeddingStore.addAll(embeddings, segments);

            log.info("벡터 저장소에 {}개 문서 저장 완료", documents.size());
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }
}
