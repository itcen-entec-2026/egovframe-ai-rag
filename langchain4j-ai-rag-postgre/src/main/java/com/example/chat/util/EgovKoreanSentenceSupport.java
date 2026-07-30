package com.example.chat.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 한국어 문장 경계를 결정론적으로 분리하는 유틸리티.
 *
 * <p>종결부호({@code . ! ? 。 ！ ？})와 닫는 인용/괄호를 함께 문장 끝으로 보고,
 * 소수점·목록 번호·라틴 약어는 문장 경계에서 제외한다. 한국어 종결어미 뒤의 종결부호는
 * 다음 문장과 공백이 없어도 경계로 인정하며, 빈 줄과 일부 마크다운 블록 시작 줄은 문단/블록
 * 경계로 처리한다. LLM·외부 사전 없이 결정론적으로 동작한다.</p>
 */
public final class EgovKoreanSentenceSupport {

    private static final Set<String> ABBREVIATIONS = Set.of(
            "no", "etc", "eg", "ie", "vs", "cf", "fig", "tbl", "ex",
            "mr", "mrs", "ms", "dr", "prof", "inc", "ltd", "co", "corp",
            "dept", "approx", "pp", "vol", "sec",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec",
            "a.m", "a.m.", "p.m", "p.m.", "e.g", "e.g.", "i.e", "i.e.",
            "u.s", "u.s."
    );
    private static final List<String> QUOTE_PARTICLES = List.of(
            "이라면서", "이라며", "이라는", "이라고", "라면서", "라고요",
            "라고", "라며", "라는", "라던", "하고"
    );

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
            if (isCodeFenceLineStart(text, index)) {
                // 코드펜스 내부의 마침표는 코드 조각일 수 있으므로 블록 전체를 하나의 구간으로 고정한다.
                addSentenceSpan(spans, text, sentenceStart, index);
                int fenceEnd = consumeCodeFenceBlock(text, index);
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

        int boundaryEnd = runEnd;
        while (boundaryEnd < text.length() && isClosingQuoteOrBracket(text.charAt(boundaryEnd))) {
            boundaryEnd++;
        }

        char terminator = text.charAt(terminatorStart);
        char previous = previousChar(text, terminatorStart);
        char next = nextChar(text, boundaryEnd);

        // 종결부호 후보 런 전체를 한 번에 소비해 "..."나 "?!"에서 빈 문장이 생기지 않게 한다.
        // 닫는 인용/괄호는 앞 문장의 일부이므로 종결부호 뒤에 붙은 경우 함께 포함한다.
        boolean boundary = false;

        // 기본 경계는 다음 문자가 공백이거나 문자열 끝인 경우로 제한해 URL·확장자·소수 오인을 줄인다.
        if (next == '\0' || Character.isWhitespace(next)) {
            boundary = true;
        }

        if (terminator == '.') {
            // 숫자 사이의 마침표는 소수점 또는 버전 표기일 가능성이 높아 경계로 보지 않는다.
            if (Character.isDigit(previous) && Character.isDigit(next)) {
                boundary = false;
            }

            // 줄 시작의 숫자열과 제+숫자 뒤 마침표만 목록 번호·서수 표기로 보고 문장 경계에서 제외한다.
            if (isNumberedListMarker(text, terminatorStart)) {
                boundary = false;
            }

            // 라틴 약어 뒤 마침표는 문장 내부 표기이므로 대소문자와 내부 마침표를 정규화해 제외한다.
            if (isAbbreviation(text, terminatorStart)) {
                boundary = false;
            }
        }

        // 한국어 종결어미 뒤 마침표는 공백 없이 다음 한글 문장이 이어져도 경계로 인정한다.
        if (!boundary && isKoreanSentenceEnding(previous) && isSafeNoSpaceNext(next)) {
            boundary = true;
        }

        // 닫는 인용 뒤 조사(라고/라며 등)는 앞 인용문을 받는 문장 성분이므로 경계로 자르지 않는다.
        if (boundary && isFollowedByQuoteParticle(text, boundaryEnd)) {
            boundary = false;
        }

        return new SentenceBoundary(boundary, boundaryEnd);
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

    private static boolean isCodeFenceLineStart(String text, int start) {
        if (!isAtLineStart(text, start)) {
            return false;
        }
        return codeFenceMarkerIndex(text, start) >= 0;
    }

    private static int consumeCodeFenceBlock(String text, int fenceLineStart) {
        int nextLineStart = nextLineStart(text, fenceLineStart);
        int search = nextLineStart;
        while (search < text.length()) {
            if (isCodeFenceLineStart(text, search)) {
                // 닫는 펜스 줄 끝까지만 포함하고 뒤 개행은 문장 사이 공백으로 남긴다.
                return lineEnd(text, search);
            }
            search = nextLineStart(text, search);
        }
        return text.length();
    }

    private static int codeFenceMarkerIndex(String text, int lineStart) {
        int index = lineStart;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == ' ' || ch == '\t') {
                index++;
                continue;
            }
            break;
        }
        return startsWith(text, index, "```") ? index : -1;
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

    private static boolean isNumberedListMarker(String text, int dotIndex) {
        char afterDot = nextChar(text, dotIndex + 1);
        if (afterDot != '\0' && !Character.isWhitespace(afterDot)) {
            return false;
        }

        int markerStart = listMarkerStart(text, dotIndex);
        if (markerStart < 0) {
            return false;
        }

        if (isKoreanOrdinalMarker(text, markerStart, dotIndex)) {
            return true;
        }

        if (!Character.isDigit(text.charAt(markerStart))) {
            return false;
        }

        for (int i = markerStart; i < dotIndex; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return markerStart < dotIndex;
    }

    private static int listMarkerStart(String text, int dotIndex) {
        int index = lineStart(text, dotIndex);
        index = skipHorizontalWhitespace(text, index);

        while (index < dotIndex && isMarkdownListQuoteMarker(text.charAt(index))) {
            int afterMarker = index + 1;
            if (afterMarker >= dotIndex || !isHorizontalWhitespace(text.charAt(afterMarker))) {
                break;
            }
            // 줄 시작 불릿/인용 접두어 뒤 공백은 목록번호의 시각적 들여쓰기라서 같은 패턴으로 본다.
            index = skipHorizontalWhitespace(text, afterMarker);
        }

        return index < dotIndex ? index : -1;
    }

    private static boolean isKoreanOrdinalMarker(String text, int markerStart, int dotIndex) {
        if (text.charAt(markerStart) != '제' || markerStart + 1 >= dotIndex) {
            return false;
        }

        for (int i = markerStart + 1; i < dotIndex; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMarkdownListQuoteMarker(char ch) {
        return ch == '-' || ch == '*' || ch == '>';
    }

    private static boolean isAbbreviation(String text, int dotIndex) {
        int start = dotIndex - 1;
        while (start >= 0) {
            char ch = text.charAt(start);
            if (isLatinLetter(ch) || ch == '.') {
                start--;
                continue;
            }
            break;
        }

        String token = text.substring(start + 1, dotIndex).toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return false;
        }

        return ABBREVIATIONS.contains(token) || ABBREVIATIONS.contains(token + ".");
    }

    private static boolean isKoreanSentenceEnding(char ch) {
        return ch == '다' || ch == '요' || ch == '까' || ch == '죠' || ch == '음'
                || ch == '임' || ch == '함' || ch == '네' || ch == '오' || ch == '쇼';
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

    private static boolean isHangulSyllable(char ch) {
        return ch >= '가' && ch <= '힣';
    }

    private static boolean isLatinLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isFollowedByQuoteParticle(String text, int start) {
        int index = start;
        while (index < text.length() && isHorizontalWhitespace(text.charAt(index))) {
            index++;
        }

        for (String particle : QUOTE_PARTICLES) {
            if (startsWith(text, index, particle)) {
                return true;
            }
        }
        return false;
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

    private static boolean startsWith(String text, int start, String prefix) {
        return start >= 0 && start + prefix.length() <= text.length()
                && text.regionMatches(start, prefix, 0, prefix.length());
    }

    private static char previousChar(String text, int index) {
        return index > 0 ? text.charAt(index - 1) : '\0';
    }

    private static char nextChar(String text, int index) {
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private record SentenceBoundary(boolean boundary, int endIndex) {
    }

    private record LineBreakRun(int count, int endIndex) {
    }
}
