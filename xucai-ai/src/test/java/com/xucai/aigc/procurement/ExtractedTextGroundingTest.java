package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractedTextGroundingTest {
    final ObjectMapper mapper=new ObjectMapper();
    RequirementDetails details(String usage,List<String> must,List<String> optional) {
        return new RequirementDetails(null,null,List.of(),usage,must,optional);
    }
    RequirementDTO input(String text,RequirementDetails confirmed) {
        return new RequirementDTO(1L,1L,"标题不是依据",text,confirmed,1L,"OPEN","now","now");
    }
    @Test void actualSavedBadFieldsAreRejectedAndPositiveExamplesRemain() throws Exception {
        var f=mapper.readTree(getClass().getResourceAsStream("/development-grounding-replay.json"));
        var errors=Map.of("DEV-E09","MODEL_SOURCE_UNSUPPORTED","DEV-E04","MODEL_PREFERENCE_UNSUPPORTED","DEV-E18","MODEL_PREFERENCE_UNSUPPORTED");
        for(var row:f.path("cases")) {
            if(!row.path("id").asText().startsWith("DEV-E"))continue;
            var req=mapper.treeToValue(row.path("requirement"),RequirementDTO.class);
            var result=mapper.treeToValue(row.path("result"),AnalysisResult.class);
            if(errors.containsKey(row.path("id").asText()))assertEquals(errors.get(row.path("id").asText()),
                    assertThrows(ModelCallException.class,()->ExtractedTextGrounding.verify(req,result)).code());
            else assertDoesNotThrow(()->ExtractedTextGrounding.verify(req,result));
        }
    }
    @Test void savedConditionsNeedNoNewSourceAndAreNeverRewritten() {
        var confirmed=details("已确认用途",List.of("USB"),List.of("有线"));
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(input("补充描述",confirmed),new AnalysisResult(confirmed,List.of())));
    }
    @Test void newQuotedPreferencesRetainCueAndMissingSourceFails() {
        var req=input("用于日常办公，优先有线，便宜点就行。",details("",List.of(),List.of()));
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(req,new AnalysisResult(details("日常办公",List.of(),List.of("优先有线","便宜点")),List.of())));
        assertEquals("MODEL_SOURCE_UNSUPPORTED",assertThrows(ModelCallException.class,()->ExtractedTextGrounding.verify(req,
                new AnalysisResult(details("多人会议",List.of(),List.of()),List.of()))).code());
        assertEquals("MODEL_PREFERENCE_UNSUPPORTED",assertThrows(ModelCallException.class,()->ExtractedTextGrounding.verify(req,
                new AnalysisResult(details("日常办公",List.of(),List.of("有线")),List.of()))).code());
    }
    @Test void literalPurchaseSentenceStillIsNotExplicitUsage() {
        var req=input("给10人的团队买显示器，实际要几台还没决定。",details("",List.of(),List.of()));
        var result=new AnalysisResult(details("给10人的团队买显示器",List.of(),List.of()),List.of());
        assertEquals("MODEL_USAGE_UNSUPPORTED",assertThrows(ModelCallException.class,()->ExtractedTextGrounding.verify(req,result)).code());
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(req,new AnalysisResult(details("",List.of(),List.of()),List.of())));
    }
    @Test void purposeMustComeFromItsOwnAffirmativeClause() {
        for(String raw:List.of("买两只鼠标用于日常办公。","用途：日常办公。","设备用来日常办公。","使用场景:日常办公。"))
            assertTrue(ExtractedTextGrounding.explicitUsage(raw,"日常办公"));
        for(String raw:List.of("给团队买显示器。","办公室买设备。","不是用于日常办公。","不能用于日常办公。","用于会议，日常办公暂不考虑。"))
            assertFalse(ExtractedTextGrounding.explicitUsage(raw,"日常办公"));
    }
    @Test void affirmativeMarkerCannotBorrowLaterNegatedPurpose() {
        assertFalse(ExtractedTextGrounding.explicitUsage("用于会议不是用于日常办公。","日常办公"));
        assertTrue(ExtractedTextGrounding.explicitUsage("用于会议不是用于日常办公。","会议"));
        assertFalse(ExtractedTextGrounding.explicitUsage("不适用于日常办公。","日常办公"));
        assertTrue(ExtractedTextGrounding.explicitUsage("用于会议也用来文档录入。","文档录入"));
        assertTrue(ExtractedTextGrounding.explicitUsage("不是用于会议而是用来文档录入。","文档录入"));
    }

    @Test void explicitUnknownBundleCannotBecomeDeviceCount() {
        var empty=details("",List.of(),List.of());
        for(String raw:List.of("采购7套键鼠；每套里面鼠标和键盘各有几个还没确定。",
                "订购12套设备，每套组成待确认。")) {
            var req=input(raw,empty);
            var invented=new RequirementDetails(null,7,List.of(3,4),"",List.of(),List.of());
            assertEquals("MODEL_BUNDLE_QUANTITY_UNSUPPORTED",assertThrows(ModelCallException.class,
                    ()->ExtractedTextGrounding.verify(req,new AnalysisResult(invented,List.of()))).code());
            assertDoesNotThrow(()->ExtractedTextGrounding.verify(req,new AnalysisResult(empty,List.of())));
        }
        var known=new RequirementDetails(null,14,List.of(3,4),"",List.of(),List.of());
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(input("每套组成待确认。",known),new AnalysisResult(known,List.of())));
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(input("采购7套键鼠，每套键盘鼠标各1件。",empty),new AnalysisResult(known,List.of())));
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(input("每套组成待确认，总件数为14件。",empty),new AnalysisResult(known,List.of())));
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(input("总计14件，每套组成待确认。",empty),new AnalysisResult(known,List.of())));
        assertEquals("MODEL_BUNDLE_QUANTITY_UNSUPPORTED",assertThrows(ModelCallException.class,
                ()->ExtractedTextGrounding.verify(input("不是总计14件，每套组成待确认。",empty),new AnalysisResult(known,List.of()))).code());
        var mixed=new RequirementDetails(null,null,List.of(1,3,4),"",List.of(),List.of(),
                List.of(new RequirementItem(1,2),new RequirementItem(3,null),new RequirementItem(4,null)));
        assertDoesNotThrow(()->ExtractedTextGrounding.verify(input("采购2台显示器，另买7套键鼠；每套里面鼠标和键盘各有几个还没确定。",empty),new AnalysisResult(mixed,List.of())));
    }

    @Test void independentExplicitDeviceExclusionMustNotDisappear() {
        for(String excluded:List.of("不要无线鼠标","不接受蓝牙键盘","不允许无线键盘")) {
            var req=input(excluded+"，必须使用USB有线连接。",details("",List.of(),List.of()));
            var missing=details("",List.of("必须使用USB有线连接"),List.of());
            assertEquals("MODEL_CONDITION_OMITTED",assertThrows(ModelCallException.class,
                    ()->ExtractedTextGrounding.verify(req,new AnalysisResult(missing,List.of()))).code());
            var retained=details("",List.of(excluded,"必须使用USB有线连接"),List.of());
            assertDoesNotThrow(()->ExtractedTextGrounding.verify(req,new AnalysisResult(retained,List.of())));
        }
        for(String raw:List.of("不是不要无线鼠标。","不要无线鼠标也可以。","不要紧。"))
            assertDoesNotThrow(()->ExtractedTextGrounding.verify(input(raw,details("",List.of(),List.of())),
                    new AnalysisResult(details("",List.of(),List.of()),List.of())));
        var confirmed=details("",List.of("USB"),List.of());
        assertEquals("MODEL_CONDITION_OMITTED",assertThrows(ModelCallException.class,
                ()->ExtractedTextGrounding.verify(input("不要无线鼠标。",confirmed),new AnalysisResult(confirmed,List.of()))).code());
    }
}
