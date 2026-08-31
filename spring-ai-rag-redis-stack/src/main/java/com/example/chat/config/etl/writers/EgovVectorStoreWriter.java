package com.example.chat.config.etl.writers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentWriter;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.stereotype.Component;

import com.example.chat.config.EgovVectorStoreConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class EgovVectorStoreWriter implements DocumentWriter {

    /** 삭제 필터 하나에 담는 원본 문서 id 최대 개수. */
    private static final int DELETE_BATCH_SIZE = 200;

    private final RedisVectorStore redisVectorStore;

    @Override
    public void accept(List<Document> documents) {
        accept(documents, List.of());
    }

    /**
     * 청크를 벡터 저장소에 저장한다.
     *
     * @param documents          저장할 청크. 비어 있어도 삭제는 수행한다.
     * @param reindexedSourceIds 이번 재색인 대상 원본 문서 id 목록
     */
    public void accept(List<Document> documents, Collection<String> reindexedSourceIds) {
        log.info("벡터 저장소에 {}개 문서 저장 시작", documents.size());

        // 삭제 대상 = 재색인 대상 원본 ∪ 이번에 저장할 청크의 원본
        Set<String> deleteTargetIds = new LinkedHashSet<>();
        if (reindexedSourceIds != null) {
            reindexedSourceIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(deleteTargetIds::add);
        }
        documents.stream()
                .map(document -> document.getMetadata().get(EgovVectorStoreConfig.ORIGINAL_ID_FIELD))
                .filter(value -> value instanceof String && !((String) value).isBlank())
                .map(String.class::cast)
                .forEach(deleteTargetIds::add);

        if (documents.isEmpty()) {
            // 저장할 청크가 없어도 옛 청크는 지워야 한다.
            log.warn("저장할 청크가 없습니다. 기존 청크 삭제만 수행합니다.");
            removeStaleChunks(deleteTargetIds, 0);
            return;
        }

        // 문서 정보 로깅
        for (int i = 0; i < Math.min(documents.size(), 3); i++) {
            Document doc = documents.get(i);
            log.debug("문서 {}: ID={}, 크기={}바이트, 메타데이터={}",
                    i, doc.getId(), doc.getText().length(), doc.getMetadata());
        }

        try {
            removeStaleChunks(deleteTargetIds, documents.size());

            redisVectorStore.add(documents);
            log.info("벡터 저장소에 {}개 문서 저장 완료", documents.size());
        } catch (Exception e) {
            log.error("벡터 저장소 저장 중 오류 발생", e);
            throw new RuntimeException("벡터 저장소 저장 중 오류 발생", e);
        }
    }

    /**
     * RediSearch TAG 필터 값에 쓸 수 있도록 구분자로 해석되는 문자를 이스케이프한다.
     *
     * <p>spring-ai의 {@code RedisFilterExpressionConverter}는 TAG 값을 그대로 질의문에 넣는다.
     * 문서 id에는 항상 하이픈이 들어가는데(<code>doc-</code>, <code>pdf-</code> 등) RediSearch는
     * 이스케이프하지 않은 하이픈을 구분자로 보고 질의 파싱에 실패한다. 마침표는 파싱은 통과하지만
     * 토큰이 갈려 값이 일치하지 않는다. 두 경우 모두 문자 앞에 백슬래시를 붙이면 해소된다.</p>
     *
     * <p>한글·영숫자·밑줄은 이스케이프 대상이 아니다.</p>
     *
     * @param value 원본 TAG 값
     * @return 이스케이프된 TAG 값
     */
    static String escapeTagValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    /**
     * 이번 배치에 들어온 원본 문서의 기존 청크를 지운다.
     *
     * <p>벡터 저장소는 새 청크를 추가만 하므로, 같은 문서를 다시 색인하면 이전 청크가 그대로
     * 남아 현재 본문과 검색 결과를 두고 경쟁한다. 원본 문서 id 기준으로 먼저 지운 뒤 저장한다.</p>
     *
     * <p>삭제 키는 파일명(source)이 아니라 원본 문서 id다. PDF는 페이지마다 문서를 만들고
     * 변경 판정도 페이지 단위이므로, 파일명으로 지우면 이번 배치에 없는 페이지까지 사라진다.
     * 그 페이지는 해시가 그대로라 다시 색인되지 않아 영구히 유실된다.</p>
     */
    private void removeStaleChunks(Set<String> deleteTargetIds, int inputChunkCount) {
        List<String> originalIds = new ArrayList<>(deleteTargetIds);

        if (originalIds.isEmpty()) {
            log.warn("원본 문서 id가 없어 기존 청크 삭제를 건너뜁니다. 저장은 계속 진행합니다.");
            return;
        }

        for (int from = 0; from < originalIds.size(); from += DELETE_BATCH_SIZE) {
            int to = Math.min(from + DELETE_BATCH_SIZE, originalIds.size());
            List<String> idBatch = originalIds.subList(from, to);
            Object[] escapedIds = idBatch.stream().map(EgovVectorStoreWriter::escapeTagValue).toArray();
            Filter.Expression expression = new FilterExpressionBuilder()
                    .in(EgovVectorStoreConfig.ORIGINAL_ID_FIELD, escapedIds)
                    .build();
            redisVectorStore.delete(expression);
        }

        log.info("기존 청크 삭제 완료: 원본 문서 {}건, 입력 청크 {}개", originalIds.size(), inputChunkCount);
    }
} 