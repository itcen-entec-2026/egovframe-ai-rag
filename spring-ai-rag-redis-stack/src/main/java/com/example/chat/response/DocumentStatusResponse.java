package com.example.chat.response;

/**
 * 문서 처리 상태를 나타내는 응답 객체
 *
 */
public record DocumentStatusResponse(
    boolean processing,     // 현재 처리 중인지 여부
    int processedCount,     // 처리를 마친 원본 문서 수 (totalCount와 같은 단위)
    int totalCount,         // 총 원본 문서 수
    int changedCount,       // 변경된 문서 수
    boolean hasDocuments,   // 색인된 문서가 있는지 여부
    int chunkCount          // 마지막 처리에서 생성된 청크 수
) {
}