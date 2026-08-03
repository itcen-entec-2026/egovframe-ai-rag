package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 벡터 재색인 시 기존 청크 삭제와 배치 임베딩 동작을 검증한다.
 */
class EgovVectorStoreWriterStaleChunkTest {

    @Test
    @DisplayName("같은 id를 재색인하면 이전 청크가 누적되지 않는다")
    void reindexingSameIdIsIdempotent() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store, embeddingModel);

        writer.write(List.of(
                document("doc-a", "a.txt", "doc-a v1 첫 번째 청크"),
                document("doc-a", "a.txt", "doc-a v1 두 번째 청크")));
        writer.write(List.of(
                document("doc-a", "a.txt", "doc-a v2 첫 번째 청크"),
                document("doc-a", "a.txt", "doc-a v2 두 번째 청크")));

        List<String> texts = storedTexts(store, embeddingModel);
        assertThat(texts).hasSize(2);
        assertThat(texts).containsExactlyInAnyOrder("doc-a v2 첫 번째 청크", "doc-a v2 두 번째 청크");
        assertThat(texts).noneMatch(text -> text.contains("v1"));
    }

    @Test
    @DisplayName("source가 같아도 배치에 없는 페이지 id는 삭제하지 않는다")
    void reindexingOnePageDoesNotDeleteOtherPageWithSameSource() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store, embeddingModel);

        writer.write(List.of(document("pdf-x_1", "x.pdf", "x.pdf 1페이지 원문")));
        writer.write(List.of(document("pdf-x_2", "x.pdf", "x.pdf 2페이지 원문")));
        writer.write(List.of(document("pdf-x_2", "x.pdf", "x.pdf 2페이지 수정본")));

        List<String> texts = storedTexts(store, embeddingModel);
        assertThat(texts).hasSize(2);
        assertThat(texts).contains("x.pdf 1페이지 원문", "x.pdf 2페이지 수정본");
        assertThat(texts).doesNotContain("x.pdf 2페이지 원문");
    }

    @Test
    @DisplayName("write 1회는 embedAll 1회만 호출하고 개별 embed를 호출하지 않는다")
    void writeUsesSingleBatchEmbeddingCall() {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store, embeddingModel);

        writer.write(List.of(
                document("doc-1", "a.txt", "N3 첫 번째 청크"),
                document("doc-2", "b.txt", "N3 두 번째 청크"),
                document("doc-3", "c.txt", "N3 세 번째 청크")));

        assertThat(embeddingModel.embedAllCalls).isEqualTo(1);
        assertThat(embeddingModel.individualEmbedCalls).isZero();
    }

    @Test
    @DisplayName("id가 없거나 blank인 문서도 삭제 없이 정상 저장한다")
    void documentsWithoutIdAreStoredWithoutDeleteFailure() {
        InMemoryEmbeddingStore<TextSegment> delegate = new InMemoryEmbeddingStore<>();
        SpyEmbeddingStore store = new SpyEmbeddingStore(delegate);
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store, embeddingModel);

        writer.write(List.of(
                document(null, "no-id.txt", "id 없는 청크"),
                document("   ", "blank-id.txt", "blank id 청크")));

        assertThat(store.removeAllFilterCalls).isZero();
        assertThat(storedTexts(store, embeddingModel)).containsExactlyInAnyOrder("id 없는 청크", "blank id 청크");
    }

    @Test
    @DisplayName("삭제 대상 id가 200건을 넘으면 removeAll(Filter)을 분할 호출한다")
    void deleteIdsAreSplitIntoBatchesOfTwoHundred() {
        InMemoryEmbeddingStore<TextSegment> delegate = new InMemoryEmbeddingStore<>();
        SpyEmbeddingStore store = new SpyEmbeddingStore(delegate);
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(store, embeddingModel);
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            documents.add(document("doc-" + i, "bulk.txt", "bulk 청크 " + i));
        }

        writer.write(documents);

        assertThat(store.removeAllFilterCalls).isEqualTo(2);
        assertThat(store.filters).hasSize(2);
        assertThat(storedTexts(store, embeddingModel)).hasSize(250);
    }

    private static Document document(String id, String source, String text) {
        Metadata metadata = new Metadata();
        if (id != null) {
            metadata.put("id", id);
        }
        metadata.put("source", source);
        return Document.from(text, metadata);
    }

    private static List<String> storedTexts(EmbeddingStore<TextSegment> store, DeterministicEmbeddingModel embeddingModel) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embeddingFor("검색 질의"))
                .maxResults(1000)
                .minScore(0.0)
                .build();
        return store.search(request).matches().stream()
                .map(EmbeddingMatch::embedded)
                .map(TextSegment::text)
                .toList();
    }

    private static class DeterministicEmbeddingModel implements EmbeddingModel {

        static final int DIMENSION = 8;

        int embedAllCalls;
        int individualEmbedCalls;

        @Override
        public Response<Embedding> embed(String text) {
            individualEmbedCalls++;
            return Response.from(embeddingFor(text));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            individualEmbedCalls++;
            return Response.from(embeddingFor(textSegment.text()));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            embedAllCalls++;
            return Response.from(textSegments.stream()
                    .map(segment -> embeddingFor(segment.text()))
                    .toList());
        }

        @Override
        public int dimension() {
            return DIMENSION;
        }

        Embedding embeddingFor(String text) {
            float[] vector = new float[DIMENSION];
            int seed = Objects.hashCode(text);
            for (int i = 0; i < vector.length; i++) {
                seed = seed * 31 + i;
                vector[i] = 0.1f + (Math.floorMod(seed, 1000) / 1000.0f);
            }
            return Embedding.from(vector);
        }
    }

    private static class SpyEmbeddingStore implements EmbeddingStore<TextSegment> {

        private final EmbeddingStore<TextSegment> delegate;
        private final List<Filter> filters = new ArrayList<>();
        private int removeAllFilterCalls;

        SpyEmbeddingStore(EmbeddingStore<TextSegment> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String add(Embedding embedding) {
            return delegate.add(embedding);
        }

        @Override
        public void add(String id, Embedding embedding) {
            delegate.add(id, embedding);
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            return delegate.add(embedding, embedded);
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return delegate.addAll(embeddings);
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
            return delegate.addAll(embeddings, embedded);
        }

        @Override
        public List<String> generateIds(int n) {
            return delegate.generateIds(n);
        }

        @Override
        public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
            delegate.addAll(ids, embeddings, embedded);
        }

        @Override
        public void remove(String id) {
            delegate.remove(id);
        }

        @Override
        public void removeAll(Collection<String> ids) {
            delegate.removeAll(ids);
        }

        @Override
        public void removeAll(Filter filter) {
            removeAllFilterCalls++;
            filters.add(filter);
            delegate.removeAll(filter);
        }

        @Override
        public void removeAll() {
            delegate.removeAll();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return delegate.search(request);
        }
    }
}
