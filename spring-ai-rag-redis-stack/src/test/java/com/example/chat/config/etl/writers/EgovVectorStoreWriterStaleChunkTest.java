package com.example.chat.config.etl.writers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;

import com.example.chat.config.EgovVectorStoreConfig;

/**
 * {@link EgovVectorStoreWriter}가 재색인 시 기존 청크를 먼저 지우는지 검증한다.
 *
 * <p>벡터 저장소는 대역으로 두고 호출 순서·삭제 필터의 내용을 확인한다.</p>
 */
class EgovVectorStoreWriterStaleChunkTest {

    private Document chunk(String originalId, String text) {
        Map<String, Object> metadata = new HashMap<>();
        if (originalId != null) {
            metadata.put(EgovVectorStoreConfig.ORIGINAL_ID_FIELD, originalId);
        }
        metadata.put("source", "sample.md");
        return new Document(text, metadata);
    }

    @Test
    @DisplayName("저장 전에 같은 원본 문서의 기존 청크를 먼저 지운다")
    void deletesBeforeAdding() {
        RedisVectorStore store = mock(RedisVectorStore.class);
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store);

        writer.accept(List.of(chunk("doc-a", "첫 번째 청크"), chunk("doc-a", "두 번째 청크")));

        InOrder order = inOrder(store);
        order.verify(store).delete(any(Filter.Expression.class));
        order.verify(store).add(anyList());
    }

    @Test
    @DisplayName("한 배치에 여러 문서가 있어도 삭제 필터는 한 번에 묶어 보낸다")
    void groupsDistinctOriginalIdsIntoOneFilter() {
        RedisVectorStore store = mock(RedisVectorStore.class);
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store);

        writer.accept(List.of(
                chunk("doc-a", "a1"), chunk("doc-a", "a2"),
                chunk("pdf-b_1", "b1"), chunk("pdf-b_2", "b2")));

        ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(store, times(1)).delete(captor.capture());
        String rendered = captor.getValue().toString();
        // 필터 값은 RediSearch 구분자를 이스케이프한 형태로 담긴다
        assertThat(rendered)
                .contains(EgovVectorStoreConfig.ORIGINAL_ID_FIELD)
                .contains("doc\\-a")
                .contains("pdf\\-b_1")
                .contains("pdf\\-b_2");
    }

    @Test
    @DisplayName("같은 파일의 다른 페이지는 각각 독립된 원본 문서 id로 다뤄진다")
    void treatsPdfPagesAsSeparateDocuments() {
        RedisVectorStore store = mock(RedisVectorStore.class);
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store);

        // 2페이지만 재색인되는 상황. 1페이지 id는 삭제 대상에 들어가면 안 된다.
        writer.accept(List.of(chunk("pdf-guide_2", "2페이지 본문")));

        ArgumentCaptor<Filter.Expression> captor = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(store).delete(captor.capture());
        String rendered = captor.getValue().toString();
        assertThat(rendered).contains("pdf\\-guide_2");
        assertThat(rendered).doesNotContain("pdf\\-guide_1");
    }

    @Test
    @DisplayName("원본 문서 id가 없으면 삭제를 건너뛰고 저장은 진행한다")
    void skipsDeleteWhenOriginalIdMissing() {
        RedisVectorStore store = mock(RedisVectorStore.class);
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store);

        writer.accept(List.of(chunk(null, "메타데이터 없는 청크")));

        verify(store, never()).delete(any(Filter.Expression.class));
        verify(store).add(anyList());
    }

    @Test
    @DisplayName("원본 문서 id가 200건을 넘으면 삭제 필터를 나눠 보낸다")
    void splitsDeleteIntoBatches() {
        RedisVectorStore store = mock(RedisVectorStore.class);
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store);

        List<Document> documents = new ArrayList<>();
        IntStream.range(0, 201).forEach(i -> documents.add(chunk("doc-" + i, "본문 " + i)));

        writer.accept(documents);

        verify(store, times(2)).delete(any(Filter.Expression.class));
    }

    @Test
    @DisplayName("TAG 필터 값의 구분자 문자를 이스케이프한다")
    void escapesTagSeparatorCharacters() {
        // RediSearch는 이스케이프하지 않은 하이픈을 구분자로 보고 질의 파싱에 실패한다.
        assertThat(EgovVectorStoreWriter.escapeTagValue("doc-abc.md")).isEqualTo("doc\\-abc\\.md");
        assertThat(EgovVectorStoreWriter.escapeTagValue("hwp-Spring-AI_시험문제_1"))
                .isEqualTo("hwp\\-Spring\\-AI_시험문제_1");
        // 한글·영숫자·밑줄은 그대로 둔다
        assertThat(EgovVectorStoreWriter.escapeTagValue("가나다_123")).isEqualTo("가나다_123");
    }

    @Test
    @DisplayName("빈 배치는 저장소를 건드리지 않는다")
    void emptyBatchTouchesNothing() {
        RedisVectorStore store = mock(RedisVectorStore.class);
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store);

        writer.accept(List.of());

        verify(store, never()).delete(any(Filter.Expression.class));
        verify(store, never()).add(anyList());
    }
}
