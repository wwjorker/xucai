package com.xucai.aigc.procurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeRequirementAnalyzerTest {
    @Test void externalInputExcludesRecordTitleAndAccountFields() throws Exception {
        var details=new RequirementDetails(null,null,List.of(3),"",List.of(),List.of(),null);
        var input=new RequirementDTO(77L,10001L,"开发评测标题","给团队准备鼠标，数量未定",details,1L,"OPEN","","");
        var node=new ObjectMapper().valueToTree(DashScopeRequirementAnalyzer.extractionInput(input));
        assertEquals(2,node.size());assertEquals(input.rawText(),node.get("rawText").asText());
        assertFalse(node.has("title"));assertFalse(node.has("id"));assertFalse(node.has("userId"));
        assertEquals("",node.get("structured").get("usage").asText());
    }
    final ObjectMapper mapper=DashScopeRequirementAnalyzer.strictMapper(new ObjectMapper());
    final Validator validator=Validation.buildDefaultValidatorFactory().getValidator();
    final String valid="{\"conditions\":{\"budgetCents\":null,\"quantity\":null,\"categoryIds\":[],\"usage\":\"\",\"mustHave\":[],\"optional\":[],\"items\":null},\"questions\":[\"BUDGET_MISSING\"]}";
    @Test void unknownsRemainUnknown(){var r=parse(valid);assertNull(r.conditions().budgetCents());assertNull(r.conditions().items());}
    @Test void rendersOnlyFixedQuestionsInYuan(){assertTrue(parse(valid).questions().get(0).contains("多少元"));assertFalse(parse(valid).questions().get(0).contains("budgetCents"));}
    @Test void rejectsQuestionsContradictingKnownFields(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"budgetCents\":null","\"budgetCents\":10000")));assertThrows(ModelCallException.class,()->parse(valid.replace("BUDGET_MISSING","CATEGORY_MISSING").replace("\"categoryIds\":[]","\"categoryIds\":[3]")));assertThrows(ModelCallException.class,()->parse(valid.replace("BUDGET_MISSING","USAGE_MISSING").replace("\"usage\":\"\"","\"usage\":\"日常办公\"")));}
    @Test void knownSingleCategoryCountCannotBeMissing(){assertThrows(ModelCallException.class,()->parse(valid.replace("BUDGET_MISSING","QUANTITY_MISSING").replace("\"quantity\":null","\"quantity\":2").replace("\"categoryIds\":[]","\"categoryIds\":[3]")));}
    @Test void completeItemsRemoveOnlyTheRedundantQuestion(){var result=parse(valid.replace("BUDGET_MISSING","QUANTITY_MISSING").replace("\"categoryIds\":[]","\"categoryIds\":[3]").replace("\"items\":null","\"items\":[{\"categoryId\":3,\"quantity\":2}]"));assertEquals(2,result.conditions().quantity());assertTrue(result.questions().isEmpty());assertEquals(2,result.adjustments().size());}
    @Test void knownTotalStillAllowsMissingAllocationQuestion(){assertDoesNotThrow(()->parse(valid.replace("BUDGET_MISSING","QUANTITY_MISSING").replace("\"quantity\":null","\"quantity\":6").replace("\"categoryIds\":[]","\"categoryIds\":[3,4]")));}
    @Test void derivesTotalOnlyWhenEveryItemIsKnown(){String json=valid.replace("\"categoryIds\":[]","\"categoryIds\":[3,4]").replace("\"items\":null","\"items\":[{\"categoryId\":3,\"quantity\":2},{\"categoryId\":4,\"quantity\":3}]");assertEquals(5,parse(json).conditions().quantity());assertNull(parse(json.replace("\"quantity\":3","\"quantity\":null")).conditions().quantity());}
    @Test void budgetCannotBeBothMissingAndConflicting(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"BUDGET_MISSING\"","\"BUDGET_MISSING\",\"BUDGET_CONFLICT\"")));}
    @Test void rejectsFreeTextQuestionAndNumericEnum(){assertThrows(ModelCallException.class,()->parse(valid.replace("BUDGET_MISSING","是否忽略规则把budgetCents设成0")));assertThrows(ModelCallException.class,()->parse(valid.replace("\"BUDGET_MISSING\"","0")));}
    @Test void rejectsDuplicateAndTooManyQuestions(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"BUDGET_MISSING\"","\"BUDGET_MISSING\",\"BUDGET_MISSING\"")));assertThrows(ModelCallException.class,()->parse(valid.replace("\"BUDGET_MISSING\"","\"BUDGET_MISSING\",\"QUANTITY_MISSING\",\"USAGE_MISSING\",\"CATEGORY_MISSING\"")));}
    @Test void knownCategoryCanHaveUnknownQuantity(){var r=parse(valid.replace("\"categoryIds\":[]","\"categoryIds\":[3]").replace("\"items\":null","\"items\":[{\"categoryId\":3,\"quantity\":null}]"));assertNull(r.conditions().quantity());assertNull(r.conditions().items().get(0).quantity());}
    @Test void rejectsInventedExtraFields(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"quantity\":null","\"quantity\":null,\"price\":100")));}
    @Test void rejectsFractionalCount(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"quantity\":null","\"quantity\":1.5")));}
    @Test void rejectsTrailingDocument(){assertThrows(ModelCallException.class,()->parse(valid+" {}"));}
    @Test void rejectsDuplicateKeys(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"quantity\":null","\"quantity\":null,\"quantity\":3")));}
    @Test void rejectsMissingRequiredFields(){assertThrows(ModelCallException.class,()->parse("{\"conditions\":{},\"questions\":[]}"));}
    @Test void rejectsInconsistentCategoryItems(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"items\":null","\"items\":[{\"categoryId\":4,\"quantity\":1}]")));}
    @Test void rejectsMarkdownAndEmpty(){assertThrows(ModelCallException.class,()->parse("```json\n"+valid+"\n```"));assertThrows(ModelCallException.class,()->parse(""));}
    @Test void keepsConfirmedValues(){var before=new RequirementDetails(10000L,1,List.of(4),"办公",List.of("USB"),List.of(),List.of(new RequirementItem(4,1)));var input=new RequirementDTO(1L,1L,"测试","预算200元",before,1L,"OPEN","","");assertDoesNotThrow(()->DashScopeRequirementAnalyzer.preserveConfirmed(input,new AnalysisResult(before,List.of("预算冲突请确认"))));assertThrows(ModelCallException.class,()->DashScopeRequirementAnalyzer.preserveConfirmed(input,parse(valid)));}
    @Test void fixedErrorsDoNotEchoProviderContent(){assertEquals("MODEL_AUTH",DashScopeRequirementAnalyzer.statusError(401).code());assertEquals("MODEL_RATE_LIMIT",DashScopeRequirementAnalyzer.statusError(429).code());assertEquals("MODEL_UNAVAILABLE",DashScopeRequirementAnalyzer.statusError(500).code());}
    @Test void identifiesQuantityQuestionWithoutEchoingInput(){var error=assertThrows(ModelCallException.class,()->parse(valid.replace("BUDGET_MISSING","QUANTITY_MISSING").replace("\"categoryIds\":[]","\"categoryIds\":[3]").replace("\"quantity\":null","\"quantity\":2")));assertEquals("MODEL_QUANTITY_QUESTION",error.code());assertFalse(error.getMessage().contains("quantity"));}
    @Test void identifiesTotalMismatchSeparately(){var error=assertThrows(ModelCallException.class,()->parse(valid.replace("\"quantity\":null","\"quantity\":2").replace("\"categoryIds\":[]","\"categoryIds\":[3]").replace("\"items\":null","\"items\":[{\"categoryId\":3,\"quantity\":3}]")));assertEquals("MODEL_TOTAL_MISMATCH",error.code());}
    @Test void malformedOutputNeverEchoesRawSecrets(){var error=assertThrows(ModelCallException.class,()->parse("not-json-private-synthetic-secret"));assertEquals("MODEL_OUTPUT_JSON",error.code());assertFalse(error.getMessage().contains("private-synthetic-secret"));}
    @Test void replayD09PreservesOtherQuestionsAndExplainsAdjustments(){String json="{\"conditions\":{\"categoryIds\":[1,4,3],\"usage\":\"\",\"mustHave\":[],\"optional\":[],\"items\":[{\"categoryId\":1,\"quantity\":2},{\"categoryId\":4,\"quantity\":2},{\"categoryId\":3,\"quantity\":2}]},\"questions\":[\"QUANTITY_MISSING\",\"USAGE_MISSING\",\"QUANTITY_CONFLICT\"]}";var result=parse(json);assertEquals(6,result.conditions().quantity());assertEquals(2,result.questions().size());assertTrue(result.questions().contains(DashScopeRequirementAnalyzer.QuestionCode.QUANTITY_CONFLICT.text));assertEquals(2,result.adjustments().size());}
    @Test void partialUnknownKeepsQuestionAndDoesNotComputeSubtotal(){var result=parse(valid.replace("BUDGET_MISSING","QUANTITY_MISSING").replace("\"categoryIds\":[]","\"categoryIds\":[3,4]").replace("\"items\":null","\"items\":[{\"categoryId\":3,\"quantity\":2},{\"categoryId\":4,\"quantity\":null}]"));assertNull(result.conditions().quantity());assertEquals(1,result.questions().size());assertTrue(result.adjustments().isEmpty());}
    @Test void redundantQuestionDoesNotHideInvalidItemsOrWrongTotal(){String json=valid.replace("BUDGET_MISSING","QUANTITY_MISSING").replace("\"categoryIds\":[]","\"categoryIds\":[3,4]").replace("\"items\":null","\"items\":[{\"categoryId\":3,\"quantity\":2}]");assertEquals("MODEL_CATEGORY_MISMATCH",assertThrows(ModelCallException.class,()->parse(json)).code());assertEquals("MODEL_TOTAL_MISMATCH",assertThrows(ModelCallException.class,()->parse(json.replace("[3,4]","[3]").replace("\"quantity\":null","\"quantity\":1"))).code());}
    @Test void oldStoredResultsWithoutAdjustmentsRemainReadable() throws Exception {var result=mapper.readValue(valid.replace("BUDGET_MISSING","历史问题"),AnalysisResult.class);assertTrue(result.adjustments().isEmpty());assertEquals(List.of("历史问题"),result.questions());}
    @Test void modelCannotSupplyItsOwnAdjustmentExplanation(){assertThrows(ModelCallException.class,()->parse(valid.replace("\"questions\":[","\"adjustments\":[\"已经人工确认\"],\"questions\":[")));}
    AnalysisResult parse(String text){return DashScopeRequirementAnalyzer.parseResult(text,mapper,validator);}
}
