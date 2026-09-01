package com.example.chat.config.etl;

import com.example.chat.config.etl.transformers.EgovEnhancedDocumentTransformer;
import com.example.chat.config.etl.transformers.EgovKoreanSentenceSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code document.chunking.korean-sentence.enabled} 토글에 따른 빈 등록 동작을 검증한다.
 */
class EgovKoreanChunkingToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EgovKoreanChunkingConfig.class);

    @Test
    @DisplayName("토글 off(기본): 한국어 문장경계 splitter 빈을 등록하지 않는다")
    void koreanSentenceSplitterAbsentWhenDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("koreanSentenceDocumentSplitter");
            assertThat(context.getBeanNamesForType(EgovKoreanSentenceSplitter.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("토글 on: 한국어 문장경계 splitter 빈을 등록한다")
    void koreanSentenceSplitterPresentWhenEnabled() {
        runner.withPropertyValues("document.chunking.korean-sentence.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("koreanSentenceDocumentSplitter");
            assertThat(context.getBean("koreanSentenceDocumentSplitter"))
                    .isInstanceOf(EgovKoreanSentenceSplitter.class);
        });
    }

    @Test
    @DisplayName("splitter 빈 부재 시 EnhancedDocumentTransformer 는 기존 recursive 로 폴백한다")
    void enhancedTransformerFallsBackToRecursiveWhenSplitterAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(TransformerBeans.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EgovEnhancedDocumentTransformer transformer = context.getBean(EgovEnhancedDocumentTransformer.class);
                    Document document = Document.from("""
                            첫 문장입니다.둘째 문장입니다.
                            셋째 문장은 조금 더 길게 작성해서 recursive 분할 결과를 직접 비교합니다.
                            넷째 문장입니다.""");

                    List<String> transformedTexts = transformer.transformAll(List.of(document))
                            .stream()
                            .map(Document::text)
                            .toList();
                    List<String> recursiveTexts = DocumentSplitters.recursive(500, 50)
                            .split(document)
                            .stream()
                            .map(TextSegment::text)
                            .toList();

                    assertThat(transformedTexts).isNotEmpty();
                    assertThat(transformedTexts).isEqualTo(recursiveTexts);

                    DocumentSplitter documentSplitter = (DocumentSplitter) ReflectionTestUtils.getField(
                            transformer, "documentSplitter");
                    assertThat(documentSplitter).isNotNull();
                    assertThat(documentSplitter).isNotInstanceOf(EgovKoreanSentenceSplitter.class);
                });
    }

    @Configuration
    static class TransformerBeans {

        @Bean
        EgovEnhancedDocumentTransformer egovEnhancedDocumentTransformer(
                ObjectProvider<EgovKoreanSentenceSplitter> koreanSentenceSplitterProvider) {
            // 청크 한도는 이 테스트의 관심사가 아니므로 아무것도 걸러내지 않는 값을 쓴다.
            return new EgovEnhancedDocumentTransformer(500, 0, Integer.MAX_VALUE, koreanSentenceSplitterProvider);
        }
    }
}
