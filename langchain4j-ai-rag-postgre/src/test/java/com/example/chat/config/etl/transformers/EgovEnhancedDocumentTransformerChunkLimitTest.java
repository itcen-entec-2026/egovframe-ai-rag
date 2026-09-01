package com.example.chat.config.etl.transformers;

import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * application.yml 이 문서화한 청크 한도(document.max-num-chunks,
 * document.min-chunk-length-to-embed)가 실제 분할 결과에 적용되는지 검증한다.
 */
class EgovEnhancedDocumentTransformerChunkLimitTest {

    private static final int CHUNK_SIZE = 500;

    @SuppressWarnings("unchecked")
    private EgovEnhancedDocumentTransformer transformer(int minChunkLengthToEmbed, int maxNumChunks) {
        ObjectProvider<EgovKoreanSentenceSplitter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new EgovEnhancedDocumentTransformer(CHUNK_SIZE, minChunkLengthToEmbed, maxNumChunks, provider);
    }

    private Document longDocument() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("이것은 ").append(i).append("번째 문장입니다. ");
        }
        return Document.from(sb.toString());
    }

    @Test
    @DisplayName("max-num-chunks 를 넘는 청크는 만들지 않는다")
    void limitsNumberOfChunks() {
        int unlimited = transformer(0, Integer.MAX_VALUE).transformAll(List.of(longDocument())).size();
        assertThat(unlimited).isGreaterThan(3);

        List<Document> capped = transformer(0, 3).transformAll(List.of(longDocument()));
        assertThat(capped).hasSize(3);
    }

    @Test
    @DisplayName("min-chunk-length-to-embed 보다 짧은 청크는 임베딩 대상에서 제외한다")
    void dropsChunksShorterThanMinLength() {
        List<Document> chunks = transformer(100_000, Integer.MAX_VALUE)
                .transformAll(List.of(longDocument()));
        assertThat(chunks).isEmpty();
    }
}
