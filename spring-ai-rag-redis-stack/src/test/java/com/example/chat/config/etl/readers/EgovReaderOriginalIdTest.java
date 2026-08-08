package com.example.chat.config.etl.readers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;

import com.example.chat.config.EgovVectorStoreConfig;

/**
 * 리더가 남기는 원본 문서 id가 재색인 삭제 키로 쓸 수 있는 값인지 검증한다.
 *
 * <p>메타데이터에 담기는 값은 같은 파일을 다시 읽어도 같아야 한다. 그렇지 않으면 재색인 시
 * 이전 청크를 찾아 지울 수 없다.</p>
 */
class EgovReaderOriginalIdTest {

    /** 파일명을 지정할 수 있는 인메모리 리소스. */
    private ByteArrayResource resource(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private Document readMarkdown(String filename, String content) throws Exception {
        Method method = EgovMarkdownReader.class
                .getDeclaredMethod("processMarkdownResource", org.springframework.core.io.Resource.class);
        method.setAccessible(true);
        return (Document) method.invoke(new EgovMarkdownReader(), resource(filename, content));
    }

    @Test
    @DisplayName("Document의 기본 id는 실행마다 달라 삭제 키로 쓸 수 없다")
    void defaultDocumentIdIsNotStable() {
        Document first = new Document("같은 본문", new HashMap<>());
        Document second = new Document("같은 본문", new HashMap<>());

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    @DisplayName("마크다운 리더는 같은 파일을 다시 읽어도 같은 원본 문서 id를 담는다")
    void markdownReaderStoresStableOriginalId() throws Exception {
        Document first = readMarkdown("표준프레임워크 가이드.md", "# 제목\n\n본문입니다.");
        Document second = readMarkdown("표준프레임워크 가이드.md", "# 제목\n\n본문이 바뀌었습니다.");

        String firstId = (String) first.getMetadata().get(EgovVectorStoreConfig.ORIGINAL_ID_FIELD);
        String secondId = (String) second.getMetadata().get(EgovVectorStoreConfig.ORIGINAL_ID_FIELD);

        assertThat(firstId).isEqualTo("doc-표준프레임워크-가이드.md");
        // 본문이 달라져도 같은 파일이면 같은 값이어야 이전 청크를 지울 수 있다
        assertThat(secondId).isEqualTo(firstId);
        assertThat(firstId).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("서로 다른 파일은 서로 다른 원본 문서 id를 가진다")
    void differentFilesGetDifferentOriginalIds() throws Exception {
        Document a = readMarkdown("가이드.md", "본문");
        Document b = readMarkdown("설명서.md", "본문");

        assertThat(a.getMetadata().get(EgovVectorStoreConfig.ORIGINAL_ID_FIELD))
                .isNotEqualTo(b.getMetadata().get(EgovVectorStoreConfig.ORIGINAL_ID_FIELD));
    }
}
