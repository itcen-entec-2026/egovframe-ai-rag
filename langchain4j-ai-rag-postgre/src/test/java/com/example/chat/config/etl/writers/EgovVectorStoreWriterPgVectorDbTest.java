package com.example.chat.config.etl.writers;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgVector 실제 엔진에서 metadata id 기반 삭제가 동작하는지 검증한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class EgovVectorStoreWriterPgVectorDbTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    @DisplayName("재색인 시 PgVector의 기존 id 청크가 실제 삭제된다")
    void reindexingDeletesStaleRowsInPgVector() {
        String table = "test_embeddings_reindex";
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(pgVectorStore(table), embeddingModel);

        writer.write(List.of(
                document("doc-a", "a.txt", "pg doc-a v1 첫 번째 청크"),
                document("doc-a", "a.txt", "pg doc-a v1 두 번째 청크")));
        writer.write(List.of(
                document("doc-a", "a.txt", "pg doc-a v2 첫 번째 청크"),
                document("doc-a", "a.txt", "pg doc-a v2 두 번째 청크")));

        JdbcTemplate jdbc = jdbcTemplate();
        assertThat(countRows(jdbc, table)).isEqualTo(2);
        assertThat(texts(jdbc, table)).containsExactlyInAnyOrder("pg doc-a v2 첫 번째 청크", "pg doc-a v2 두 번째 청크");
    }

    @Test
    @DisplayName("source가 같은 다른 id 청크는 PgVector에서 삭제되지 않는다")
    void reindexingOneIdKeepsOtherIdWithSameSourceInPgVector() {
        String table = "test_embeddings_pdf_page";
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        EgovVectorStoreWriter writer = new EgovVectorStoreWriter(pgVectorStore(table), embeddingModel);

        writer.write(List.of(document("pdf-x_1", "x.pdf", "pg x.pdf 1페이지 원문")));
        writer.write(List.of(document("pdf-x_2", "x.pdf", "pg x.pdf 2페이지 원문")));
        writer.write(List.of(document("pdf-x_2", "x.pdf", "pg x.pdf 2페이지 수정본")));

        JdbcTemplate jdbc = jdbcTemplate();
        assertThat(countRows(jdbc, table)).isEqualTo(2);
        assertThat(texts(jdbc, table)).contains("pg x.pdf 1페이지 원문", "pg x.pdf 2페이지 수정본");
        assertThat(texts(jdbc, table)).doesNotContain("pg x.pdf 2페이지 원문");
    }

    private static PgVectorEmbeddingStore pgVectorStore(String table) {
        return PgVectorEmbeddingStore.builder()
                .host(POSTGRES.getHost())
                .port(POSTGRES.getFirstMappedPort())
                .database(POSTGRES.getDatabaseName())
                .user(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .table(table)
                .dimension(DeterministicEmbeddingModel.DIMENSION)
                .createTable(true)
                .build();
    }

    private static JdbcTemplate jdbcTemplate() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    private static int countRows(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static List<String> texts(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT text FROM " + table + " ORDER BY text", String.class);
    }

    private static Document document(String id, String source, String text) {
        Metadata metadata = new Metadata();
        metadata.put("id", id);
        metadata.put("source", source);
        return Document.from(text, metadata);
    }

    private static class DeterministicEmbeddingModel implements EmbeddingModel {

        static final int DIMENSION = 8;

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return Response.from(textSegments.stream()
                    .map(segment -> embeddingFor(segment.text()))
                    .toList());
        }

        @Override
        public int dimension() {
            return DIMENSION;
        }

        private Embedding embeddingFor(String text) {
            float[] vector = new float[DIMENSION];
            int seed = Objects.hashCode(text);
            for (int i = 0; i < vector.length; i++) {
                seed = seed * 31 + i;
                vector[i] = 0.1f + (Math.floorMod(seed, 1000) / 1000.0f);
            }
            return Embedding.from(vector);
        }
    }
}
