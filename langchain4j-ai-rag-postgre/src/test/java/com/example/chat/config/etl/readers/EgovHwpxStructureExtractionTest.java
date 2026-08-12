package com.example.chat.config.etl.readers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractMethod;
import kr.dogfoot.hwpxlib.tool.textextractor.TextExtractor;
import kr.dogfoot.hwpxlib.tool.textextractor.TextMarks;

/**
 * HWPX 추출이 문단과 표 구조를 남기는지 검증한다.
 *
 * <p>문서를 메모리에서 만들어 리더가 쓰는 표식으로 추출한다. 샘플 파일을 저장소에 두지 않고도
 * 결정적으로 확인할 수 있다.</p>
 */
class EgovHwpxStructureExtractionTest {

    /** 리더가 실제로 쓰는 표식을 그대로 가져온다. */
    private TextMarks readerMarks() throws Exception {
        Field field = EgovHwpxReader.class.getDeclaredField("STRUCTURE_MARKS");
        field.setAccessible(true);
        return (TextMarks) field.get(null);
    }

    private void addText(Para para, String text) {
        Run run = para.addNewRun();
        run.charPrIDRefAnd("0");
        T t = run.addNewT();
        t.addText(text);
    }

    private void addCell(Tr row, String text) {
        Tc cell = row.addNewTc();
        cell.createSubList();
        addText(cell.subList().addNewPara(), text);
    }

    /** 문단 2개와 3×3 표 하나를 담은 문서. */
    private HWPXFile sampleDocument() throws Exception {
        HWPXFile file = BlankFileMaker.make();
        SectionXMLFile section = file.sectionXMLFileList().get(0);
        addText(section.addNewPara(), "제1조(목적) 이 규정은 수수료 기준을 정함을 목적으로 한다");
        addText(section.addNewPara(), "제2조(수수료) 수수료는 별표와 같다");

        Para tableParagraph = section.addNewPara();
        Run run = tableParagraph.addNewRun();
        run.charPrIDRefAnd("0");
        Table table = run.addNewTable();
        String[][] rows = {
                {"민원종류", "수수료", "처리기간"},
                {"주민등록등본", "400원", "즉시"},
                {"가족관계증명서", "1000원", "3일"}};
        for (String[] row : rows) {
            Tr tr = table.addNewTr();
            for (String cell : row) {
                addCell(tr, cell);
            }
        }
        return file;
    }

    private String extract(TextMarks marks) throws Exception {
        return TextExtractor.extract(sampleDocument(),
                TextExtractMethod.InsertControlTextBetweenParagraphText, false, marks);
    }

    @Test
    @DisplayName("문단 사이에 개행이 들어간다")
    void paragraphsAreSeparated() throws Exception {
        String extracted = extract(readerMarks());

        assertThat(extracted).contains("정함을 목적으로 한다\n제2조(수수료)");
    }

    @Test
    @DisplayName("표는 행마다 줄이 나뉘고 셀은 구분자로 이어진다")
    void tableRowsAndCellsAreSeparated() throws Exception {
        String extracted = extract(readerMarks());

        assertThat(extracted)
                .contains("민원종류 | 수수료 | 처리기간")
                .contains("주민등록등본 | 400원 | 즉시")
                .contains("가족관계증명서 | 1000원 | 3일");
    }

    @Test
    @DisplayName("셀 구분자는 행 맨 앞에 붙지 않는다")
    void cellSeparatorDoesNotStartALine() throws Exception {
        String extracted = extract(readerMarks());

        for (String line : extracted.split("\n")) {
            assertThat(line.trim()).doesNotStartWith("|");
        }
    }

    @Test
    @DisplayName("표식이 없으면 구조가 모두 사라진다")
    void withoutMarksEverythingCollapsesIntoOneLine() throws Exception {
        String extracted = extract(null);

        assertThat(extracted).doesNotContain("\n");
        // 표의 행 관계가 남지 않아 셀 값이 그대로 이어 붙는다
        assertThat(extracted).contains("주민등록등본400원즉시");
    }

    @Test
    @DisplayName("본문 글자는 표식 적용 전후가 같다")
    void textContentIsUnchanged() throws Exception {
        String withoutMarks = extract(null);
        String withMarks = extract(readerMarks());

        String stripped = withMarks.replace("\n", "").replace(" | ", "");
        assertThat(stripped).isEqualTo(withoutMarks);
    }
}
