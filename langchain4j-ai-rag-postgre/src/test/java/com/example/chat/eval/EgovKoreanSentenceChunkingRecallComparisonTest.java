package com.example.chat.eval;

import com.example.chat.config.EgovHybridContentRetriever;
import com.example.chat.config.etl.transformers.EgovKoreanSentenceSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 한국어 문장 경계 인지 splitter와 기존 recursive splitter의 청크 충전율, 원문 보존율,
 * 제목 고아율, lexical recall@3를 비교한다.
 *
 * <p>이 테스트는 문장 경계 전략이 recall을 개선한다고 주장하지 않는다. 고정 corpus와 QA 시드에서
 * recall 회귀를 방어하고, 원문 offset 기반 청킹이 원문 substring을 보존하는지 확인한다.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class EgovKoreanSentenceChunkingRecallComparisonTest {

    private static final Logger log =
            LoggerFactory.getLogger(EgovKoreanSentenceChunkingRecallComparisonTest.class);

    private static final double LEXICAL_THRESHOLD = 0.30;
    private static final int[] CHUNK_SIZES = {4000, 1000};
    private static final List<CorpusDocument> CORPUS = List.of(
            new CorpusDocument("doc-reactive-redis.md", "reactive-redis.md",
                    EgovRetrievalRecallPoCTest.REDIS_DOCUMENT),
            new CorpusDocument("doc-pgvector.md", "pgvector.md",
                    EgovRetrievalRecallPoCTest.PGVECTOR_DOCUMENT),
            new CorpusDocument("doc-crypto.md", "crypto.md",
                    EgovRetrievalRecallPoCTest.CRYPTO_DOCUMENT),
            new CorpusDocument("doc-batch.md", "batch.md",
                    EgovRetrievalRecallPoCTest.BATCH_DOCUMENT),
            new CorpusDocument("doc-cache-storage.md", "cache-storage.md",
                    EgovRetrievalRecallPoCTest.CACHE_DISTRACTOR_DOCUMENT));

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager txManager;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        txManager = new DataSourceTransactionManager(ds);

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Test
    @DisplayName("한국어 문장 경계 청킹과 기존 recursive 청킹의 충전율, 원문 보존율, 제목 고아율, recall@3를 비교한다")
    void comparesKoreanSentenceChunkingRecallAndQualityMetrics() {
        for (int chunkSize : CHUNK_SIZES) {
            ComparisonResult recursive = evaluate(chunkSize, "recursive", recursiveSplitter(chunkSize));
            ComparisonResult korean = evaluate(chunkSize, "korean", koreanSplitter(chunkSize));

            assertThat(korean.averageRecallAtThree())
                    .isGreaterThanOrEqualTo(recursive.averageRecallAtThree() - 0.1);
            assertThat(korean.fillRatio())
                    .isGreaterThanOrEqualTo(recursive.fillRatio());
            assertThat(korean.originalSubstringRatio())
                    .isEqualTo(1.0);
        }
    }

    private ComparisonResult evaluate(int chunkSize, String strategy, DocumentSplitter splitter) {
        String tableName = tableName(chunkSize, strategy);
        jdbc.execute("DROP TABLE IF EXISTS " + tableName);
        jdbc.execute("CREATE TABLE " + tableName + " (embedding_id serial primary key, text text, metadata jsonb)");
        jdbc.execute("CREATE INDEX idx_" + tableName + "_trgm ON " + tableName + " USING gin (text gin_trgm_ops)");

        List<TextSegment> segments = splitCorpus(splitter);
        for (TextSegment segment : segments) {
            assertThat(segment.text()).hasSizeLessThanOrEqualTo(chunkSize);
            insert(tableName, segment);
        }

        double averageRecallAtThree = averageRecallAtThree(tableName);
        double averageChunkLength = averageChunkLength(segments);
        double fillRatio = averageChunkLength / chunkSize;
        double headingOnlyTailRatio = headingOnlyTailRatio(segments);
        double originalSubstringRatio = originalSubstringRatio(segments);

        log.info("[비교] chunkSize={} strategy={} 청크수={} 평균길이={} 충전율={} 제목고아율={} 원문일치율={} 평균recall@3={}",
                chunkSize, strategy, segments.size(), formatInteger(averageChunkLength), format(fillRatio),
                format(headingOnlyTailRatio), format(originalSubstringRatio), format(averageRecallAtThree));

        return new ComparisonResult(chunkSize, strategy, segments.size(), averageChunkLength, fillRatio,
                headingOnlyTailRatio, originalSubstringRatio, averageRecallAtThree);
    }

    private List<TextSegment> splitCorpus(DocumentSplitter splitter) {
        List<TextSegment> segments = new ArrayList<>();
        for (CorpusDocument corpusDocument : CORPUS) {
            Metadata metadata = new Metadata();
            metadata.put("id", corpusDocument.id());
            metadata.put("source", corpusDocument.fileName());
            metadata.put("file_name", corpusDocument.fileName());

            Document document = Document.from(corpusDocument.text(), metadata);
            segments.addAll(splitter.split(document));
        }
        return List.copyOf(segments);
    }

    private void insert(String tableName, TextSegment segment) {
        String id = segment.metadata().getString("id");
        String fileName = segment.metadata().getString("file_name");
        String metadata = "{\"id\": \"" + id + "\", \"source\": \"" + fileName
                + "\", \"file_name\": \"" + fileName + "\"}";
        jdbc.update("INSERT INTO " + tableName + "(text, metadata) VALUES (?, ?::jsonb)", segment.text(), metadata);
    }

    private double averageRecallAtThree(String tableName) {
        EgovHybridContentRetriever retriever = retriever(tableName);
        double recallSum = 0.0;

        for (EgovRetrievalRecallPoCTest.SeedQuestion seed : EgovRetrievalRecallPoCTest.QA_SEEDS) {
            List<String> retrievedDocIds = retriever.retrieve(Query.from(seed.question())).stream()
                    .map(Content::textSegment)
                    .map(segment -> segment.metadata().getString("id"))
                    .distinct()
                    .toList();
            assertThat(retrievedDocIds).hasSizeLessThanOrEqualTo(EgovRetrievalRecallPoCTest.TOP_K);
            recallSum += EgovRetrievalRecallPoCTest.recallAtK(
                    retrievedDocIds, List.of(seed.goldDocId()), EgovRetrievalRecallPoCTest.TOP_K);
        }

        return recallSum / EgovRetrievalRecallPoCTest.QA_SEEDS.size();
    }

    private EgovHybridContentRetriever retriever(String tableName) {
        ContentRetriever emptyDense = q -> List.of();
        return new EgovHybridContentRetriever(emptyDense, jdbc, txManager, tableName,
                1.0, 1.0, LEXICAL_THRESHOLD, EgovRetrievalRecallPoCTest.TOP_K);
    }

    private double averageChunkLength(List<TextSegment> segments) {
        return segments.stream()
                .mapToInt(segment -> segment.text().length())
                .average()
                .orElse(0.0);
    }

    private double headingOnlyTailRatio(List<TextSegment> segments) {
        long headingTailCount = segments.stream()
                .map(TextSegment::text)
                .filter(this::hasHeadingAsLastNonBlankLine)
                .count();
        return (double) headingTailCount / segments.size();
    }

    private boolean hasHeadingAsLastNonBlankLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) {
                return line.startsWith("#");
            }
        }
        return false;
    }

    private double originalSubstringRatio(List<TextSegment> segments) {
        long originalSubstringCount = segments.stream()
                .filter(segment -> originalText(segment).contains(segment.text()))
                .count();
        return (double) originalSubstringCount / segments.size();
    }

    private String originalText(TextSegment segment) {
        String id = segment.metadata().getString("id");
        return CORPUS.stream()
                .filter(corpusDocument -> corpusDocument.id().equals(id))
                .map(CorpusDocument::text)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown corpus id: " + id));
    }

    private DocumentSplitter recursiveSplitter(int chunkSize) {
        return DocumentSplitters.recursive(chunkSize, overlapSize(chunkSize));
    }

    private DocumentSplitter koreanSplitter(int chunkSize) {
        return new EgovKoreanSentenceSplitter(chunkSize, overlapSize(chunkSize));
    }

    private int overlapSize(int chunkSize) {
        return Math.max(chunkSize / 10, 50);
    }

    private String tableName(int chunkSize, String strategy) {
        return "chunk_recall_" + strategy + "_" + chunkSize;
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private String formatInteger(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private record CorpusDocument(String id, String fileName, String text) {
    }

    private record ComparisonResult(
            int chunkSize,
            String strategy,
            int chunkCount,
            double averageChunkLength,
            double fillRatio,
            double headingOnlyTailRatio,
            double originalSubstringRatio,
            double averageRecallAtThree) {
    }
}
