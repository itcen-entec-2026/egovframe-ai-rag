package com.example.chat.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.JedisPooled;

/**
 * RediSearch 인덱스에 원본 문서 id 메타데이터 필드를 등록하는 벡터 저장소 구성.
 *
 * <p>Spring AI 오토컨피그가 만드는 {@code RedisVectorStore}는 메타데이터 필드를 하나도
 * 선언하지 않는다. RediSearch는 인덱스 스키마에 없는 필드로 필터할 수 없으므로, 그 상태로는
 * {@code delete(Filter.Expression)}이 어떤 문서와도 매칭되지 않는다. 재색인 시 이전 청크를
 * 지우려면 원본 문서 id를 인덱스에 담아야 한다.</p>
 *
 * <p>오토컨피그의 빈 정의는 {@code @ConditionalOnMissingBean}이므로 이 빈이 대신 쓰인다.
 * 인덱스 이름·prefix·스키마 초기화 여부는 기존 설정 키를 그대로 읽어 동작이 달라지지 않는다.</p>
 *
 * <p><b>기존 인덱스 주의</b> — {@code initialize-schema}는 인덱스가 없을 때만 스키마를
 * 만든다. 이 변경 이전에 만들어진 인덱스에는 {@code original_id} 필드가 없어 삭제 필터가
 * 0건을 매칭한다. 이 경우 동작은 변경 전과 같고(이전 청크가 남음) 데이터가 유실되지는 않는다.
 * 삭제를 적용하려면 {@code FT.DROPINDEX <index-name>}으로 인덱스만 지운 뒤(문서는 보존됨)
 * 재기동해 스키마를 다시 만들면 된다.</p>
 */
@Slf4j
@Configuration
public class EgovVectorStoreConfig {

    /** 원본 문서 id를 담는 메타데이터 필드명. 리더 5종이 같은 키로 값을 넣는다. */
    public static final String ORIGINAL_ID_FIELD = "original_id";

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.ai.vectorstore.redis.index-name:document-index}")
    private String indexName;

    @Value("${spring.ai.vectorstore.redis.prefix:" + RedisVectorStore.DEFAULT_PREFIX + "}")
    private String prefix;

    @Value("${spring.ai.vectorstore.redis.initialize-schema:false}")
    private boolean initializeSchema;

    @Bean
    public RedisVectorStore vectorStore(EmbeddingModel embeddingModel) {
        log.info("RedisVectorStore 구성 - index: {}, prefix: {}, metadataField: {}",
                indexName, prefix, ORIGINAL_ID_FIELD);

        return RedisVectorStore.builder(new JedisPooled(redisHost, redisPort), embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(initializeSchema)
                .metadataFields(RedisVectorStore.MetadataField.tag(ORIGINAL_ID_FIELD))
                .build();
    }
}
