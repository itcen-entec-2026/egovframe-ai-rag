package com.example.chat.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 한국어 문장 경계를 결정론적으로 분리하는 유틸리티.
 *
 * <p>경계 판정은 보수적이다. 빈 줄과 마크다운 블록 시작 줄을 문단·블록 경계로 보고,
 * 마침표는 한국어 종결어미 뒤에 오고 다음이 공백·개행·문서 끝일 때만 문장 끝으로 본다.
 * 그 밖의 마침표(날짜 표기·소수점·라틴 약어·목록 번호·법령 조항 등)는 경계로 보지 않는다.
 * 물음표·느낌표는 표기 모호성이 없어 공백·개행·문서 끝이 이어지면 경계로 본다.</p>
 *
 * <p>경계를 놓치면 두 문장이 한 구간으로 남을 뿐이고, 청크는 원문 offset을 그대로 잘라내므로
 * 내용이 훼손되지 않는다. 반대로 경계를 잘못 만들면 문장이 중간에서 끊어지므로, 확신이 없는
 * 마침표는 자르지 않는다. LLM·외부 사전 없이 결정론적으로 동작한다.</p>
 *
 * <p>마침표 경계 판정은 한국어 종결어미 뒤에서만 동작하므로 영어 문장 끝 마침표는 경계로 보지 않는다.
 * 영어 문장과 뒤따르는 한국어 문장은 한 구간으로 남고, 영어 비중이 높은 문서는 구간이 길어질 수 있다.
 * 이는 약어·소수점 오분리를 피하기 위한 의도된 제약이며, 이런 문서에는 기존 splitter가 더 적합하다.</p>
 */
public final class EgovKoreanSentenceSupport {

    private EgovKoreanSentenceSupport() {
    }

    /**
     * 원문 내 문장 구간. {@code start} 포함, {@code end} 제외이며 앞뒤 공백은 제외한다.
     */
    public record SentenceSpan(int start, int end) {
    }

    /**
     * 입력 텍스트의 한국어 문장 구간을 원문 offset 기준으로 분리한다.
     *
     * @param text 분리할 텍스트
     * @return 오름차순·비중첩 문장 구간 목록
     */
    public static List<SentenceSpan> splitSentenceSpans(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<SentenceSpan> spans = new ArrayList<>();
        int sentenceStart = 0;
        int index = 0;

        while (index < text.length()) {
            CodeFence fence = codeFenceAt(text, index);
            if (fence != null) {
                // 코드펜스 내부의 마침표는 코드 조각일 수 있으므로 블록 전체를 하나의 구간으로 고정한다.
                addSentenceSpan(spans, text, sentenceStart, index);
                int fenceEnd = consumeCodeFenceBlock(text, index, fence);
                addSentenceSpan(spans, text, index, fenceEnd);
                sentenceStart = fenceEnd;
                index = fenceEnd;
                continue;
            }

            char current = text.charAt(index);

            if (isLineBreak(current)) {
                LineBreakRun lineBreakRun = consumeLineBreakRun(text, index);
                if (lineBreakRun.count() >= 2) {
                    // 빈 줄은 문단이 바뀐 신호이므로 종결부호가 없어도 강제 경계로 본다.
                    addSentenceSpan(spans, text, sentenceStart, index);
                    sentenceStart = lineBreakRun.endIndex();
                } else if (isMarkdownBlockStart(text, lineStart(text, index))
                        || isMarkdownBlockStart(text, lineBreakRun.endIndex())) {
                    // 단일 개행은 보통 하드랩이지만, 다음 줄이 마크다운 블록이면 문단 섞임을 막기 위해 경계로 본다.
                    addSentenceSpan(spans, text, sentenceStart, index);
                    sentenceStart = lineBreakRun.endIndex();
                }
                index = lineBreakRun.endIndex();
                continue;
            }

            if (isTerminator(current)) {
                SentenceBoundary boundary = evaluateTerminatorBoundary(text, index);
                if (boundary.boundary()) {
                    addSentenceSpan(spans, text, sentenceStart, boundary.endIndex());
                    sentenceStart = boundary.endIndex();
                }
                index = boundary.endIndex();
                continue;
            }

            index++;
        }

        addSentenceSpan(spans, text, sentenceStart, text.length());
        return List.copyOf(spans);
    }

    /**
     * 입력 텍스트를 한국어 문장 경계 기준으로 분리한다.
     *
     * @param text 분리할 텍스트
     * @return 앞뒤 공백이 제거된 문장 목록
     */
    public static List<String> splitSentences(String text) {
        List<SentenceSpan> spans = splitSentenceSpans(text);
        if (spans.isEmpty()) {
            return List.of();
        }

        List<String> sentences = new ArrayList<>(spans.size());
        for (SentenceSpan span : spans) {
            sentences.add(text.substring(span.start(), span.end()));
        }
        return List.copyOf(sentences);
    }

    private static SentenceBoundary evaluateTerminatorBoundary(String text, int terminatorStart) {
        int runEnd = terminatorStart + 1;
        while (runEnd < text.length() && isTerminator(text.charAt(runEnd))) {
            runEnd++;
        }

        // 종결부호 후보 런 전체를 한 번에 소비해 "..."나 "?!"에서 빈 문장이 생기지 않게 한다.
        // 닫는 인용/괄호는 앞 문장의 일부이므로 종결부호 뒤에 붙은 경우 함께 포함한다.
        int boundaryEnd = runEnd;
        while (boundaryEnd < text.length() && isClosingQuoteOrBracket(text.charAt(boundaryEnd))) {
            boundaryEnd++;
        }
        boolean consumedClosingQuote = boundaryEnd > runEnd;

        char previous = previousChar(text, terminatorStart);
        char next = nextChar(text, boundaryEnd);
        boolean followedByBreak = next == '\0' || Character.isWhitespace(next);
        boolean boundary;

        if (text.charAt(terminatorStart) == '.') {
            // 마침표는 날짜 표기·소수점·약어·목록 번호와 구분되지 않으므로 한국어 종결어미 뒤에서만 경계로 본다.
            boundary = isKoreanSentenceEnding(previous) && followedByBreak;
        } else {
            // 물음표·느낌표는 다른 표기로 쓰이지 않아 뒤에 공백·개행·문서 끝이 오면 경계로 본다.
            // 다만 같은 줄에서 아직 닫히지 않은 마크다운 링크·이미지 안이면 토큰 중간이므로 제외한다.
            boundary = followedByBreak && !isInsideUnclosedInlineMarkup(text, terminatorStart);
        }

        // 인용문을 닫은 뒤 같은 줄에 한국어가 이어지면 인용을 받는 문장 성분(라고 말했다)이므로 자르지 않는다.
        if (boundary && consumedClosingQuote && continuesWithHangulOnSameLine(text, boundaryEnd)) {
            boundary = false;
        }

        // 공백 없이 다음 문장이 붙는 표기도 종결어미 뒤라면 경계로 인정한다.
        // 닫는 인용을 소비한 경우는 위와 같은 이유로 제외한다.
        if (!boundary && !consumedClosingQuote && isKoreanSentenceEnding(previous) && isSafeNoSpaceNext(next)) {
            boundary = true;
        }

        return new SentenceBoundary(boundary, boundaryEnd);
    }

    // 닫는 펜스에는 정보문자열이 올 수 없다(CommonMark). 마커 뒤에 수평공백만 있는 줄만 닫는 펜스로 본다.
    private static boolean isBlankAfterFenceMarker(String text, int lineStart, CodeFence fence) {
        int index = skipHorizontalWhitespace(text, lineStart) + fence.length();
        index = skipHorizontalWhitespace(text, index);
        return index >= text.length() || isLineBreak(text.charAt(index));
    }

    // 같은 줄 앞쪽에 닫히지 않은 [ 또는 ( 가 있으면 마크다운 링크·이미지 표기 안이다.
    private static boolean isInsideUnclosedInlineMarkup(String text, int index) {
        int bracket = 0;
        int paren = 0;
        for (int i = lineStart(text, index); i < index; i++) {
            char ch = text.charAt(i);
            if (ch == '[') {
                bracket++;
            } else if (ch == ']' && bracket > 0) {
                bracket--;
            } else if (ch == '(') {
                paren++;
            } else if (ch == ')' && paren > 0) {
                paren--;
            }
        }
        return bracket > 0 || paren > 0;
    }

    private static void addSentenceSpan(List<SentenceSpan> spans, String text, int start, int end) {
        int trimmedStart = start;
        int trimmedEnd = end;
        while (trimmedStart < trimmedEnd && Character.isWhitespace(text.charAt(trimmedStart))) {
            trimmedStart++;
        }
        while (trimmedEnd > trimmedStart && Character.isWhitespace(text.charAt(trimmedEnd - 1))) {
            trimmedEnd--;
        }
        if (trimmedStart < trimmedEnd) {
            spans.add(new SentenceSpan(trimmedStart, trimmedEnd));
        }
    }

    private static boolean isTerminator(char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？';
    }

    private static boolean isClosingQuoteOrBracket(char ch) {
        return ch == '"' || ch == '\'' || ch == '」' || ch == '』' || ch == ')' || ch == ']' || ch == '》';
    }

    private static boolean isLineBreak(char ch) {
        return ch == '\n' || ch == '\r';
    }

    private static LineBreakRun consumeLineBreakRun(String text, int start) {
        int index = start;
        int count = 0;
        while (index < text.length() && isLineBreak(text.charAt(index))) {
            if (text.charAt(index) == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                index += 2;
            } else {
                index++;
            }
            count++;
        }
        return new LineBreakRun(count, index);
    }

    private static boolean isMarkdownBlockStart(String text, int start) {
        int index = start;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == ' ' || ch == '\t') {
                index++;
                continue;
            }
            break;
        }

        if (index >= text.length()) {
            return false;
        }

        char ch = text.charAt(index);
        if (ch == '#' || ch == '-' || ch == '*' || ch == '>' || ch == '|') {
            return true;
        }

        if (!Character.isDigit(ch)) {
            return false;
        }

        int numberEnd = index + 1;
        while (numberEnd < text.length() && Character.isDigit(text.charAt(numberEnd))) {
            numberEnd++;
        }

        return numberEnd < text.length() && (text.charAt(numberEnd) == '.' || text.charAt(numberEnd) == ')');
    }

    // 코드펜스 여는 줄이면 마커 문자와 길이를 돌려준다. 백틱과 물결표를 모두 인식한다.
    private static CodeFence codeFenceAt(String text, int start) {
        if (!isAtLineStart(text, start)) {
            return null;
        }

        int index = skipHorizontalWhitespace(text, start);
        if (index >= text.length()) {
            return null;
        }

        char marker = text.charAt(index);
        if (marker != '`' && marker != '~') {
            return null;
        }

        int length = 0;
        while (index + length < text.length() && text.charAt(index + length) == marker) {
            length++;
        }
        return length >= 3 ? new CodeFence(marker, length) : null;
    }

    private static int consumeCodeFenceBlock(String text, int fenceLineStart, CodeFence opening) {
        int search = nextLineStart(text, fenceLineStart);
        while (search < text.length()) {
            CodeFence closing = codeFenceAt(text, search);
            // 닫는 펜스는 여는 펜스와 같은 문자이고 길이가 같거나 길어야 한다(네 겹 백틱 블록의 조기 종료 방지).
            if (closing != null && closing.marker() == opening.marker() && closing.length() >= opening.length()
                    && isBlankAfterFenceMarker(text, search, closing)) {
                // 닫는 펜스 줄 끝까지만 포함하고 뒤 개행은 문장 사이 공백으로 남긴다.
                return lineEnd(text, search);
            }
            search = nextLineStart(text, search);
        }
        return text.length();
    }

    private static int lineEnd(String text, int index) {
        int lineEnd = index;
        while (lineEnd < text.length() && !isLineBreak(text.charAt(lineEnd))) {
            lineEnd++;
        }
        return lineEnd;
    }

    private static int nextLineStart(String text, int index) {
        int lineEnd = lineEnd(text, index);
        if (lineEnd >= text.length()) {
            return text.length();
        }
        if (text.charAt(lineEnd) == '\r' && lineEnd + 1 < text.length() && text.charAt(lineEnd + 1) == '\n') {
            return lineEnd + 2;
        }
        return lineEnd + 1;
    }

    private static int lineStart(String text, int index) {
        int lineStart = index;
        while (lineStart > 0 && !isLineBreak(text.charAt(lineStart - 1))) {
            lineStart--;
        }
        return lineStart;
    }

    private static boolean isAtLineStart(String text, int index) {
        return index == 0 || isLineBreak(text.charAt(index - 1));
    }

    private static boolean isKoreanSentenceEnding(char ch) {
        return ch == '다' || ch == '요' || ch == '까' || ch == '죠' || ch == '음'
                || ch == '임' || ch == '함' || ch == '네' || ch == '오';
    }

    private static boolean isSafeNoSpaceNext(char ch) {
        if (ch == '\0' || isLineBreak(ch) || isOpeningQuoteOrBracket(ch)) {
            return true;
        }
        return isHangulSyllable(ch);
    }

    private static boolean isOpeningQuoteOrBracket(char ch) {
        return ch == '"' || ch == '\'' || ch == '「' || ch == '『' || ch == '(' || ch == '[' || ch == '《';
    }

    private static boolean continuesWithHangulOnSameLine(String text, int start) {
        int index = skipHorizontalWhitespace(text, start);
        return index < text.length() && isHangulSyllable(text.charAt(index));
    }

    private static boolean isHangulSyllable(char ch) {
        return ch >= '가' && ch <= '힣';
    }

    private static int skipHorizontalWhitespace(String text, int start) {
        int index = start;
        while (index < text.length() && isHorizontalWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isHorizontalWhitespace(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static char previousChar(String text, int index) {
        return index > 0 ? text.charAt(index - 1) : '\0';
    }

    private static char nextChar(String text, int index) {
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private record CodeFence(char marker, int length) {
    }

    private record SentenceBoundary(boolean boundary, int endIndex) {
    }

    private record LineBreakRun(int count, int endIndex) {
    }
}
