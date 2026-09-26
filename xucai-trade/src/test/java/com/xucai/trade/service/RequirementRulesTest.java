package com.xucai.trade.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementRulesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private RequirementDetails details(List<RequirementItem> items) {
        return new RequirementDetails(600000L, null, items.stream().map(RequirementItem::categoryId).toList(),
                "线上会议", List.of(), List.of(), items);
    }
    private RequirementDTO requirement(RequirementDetails details) {
        return new RequirementDTO(1L, 10001L, "测试", "两个人各一套，总共6件，预算9999元", details, 4L, "OPEN", "now", "now");
    }
    @Test void completeItemsDeriveTotalAndCheckHasNoMissingFields() {
        var result = RequirementRules.normalize(details(List.of(new RequirementItem(4, 2), new RequirementItem(3, 2), new RequirementItem(6, 2))));
        assertEquals(6, result.quantity()); assertEquals(List.of(3, 4, 6), result.categoryIds());
        var check = RequirementRules.check(requirement(result));
        assertEquals("RULES", check.source()); assertEquals(4L, check.revision()); assertTrue(check.complete());
    }
    @Test void oldMultipleCategoriesDoNotCopyOrSplitOldTotal() {
        var old = new RequirementDetails(600000L, 6, List.of(3, 4, 6), "办公", List.of(), List.of());
        assertTrue(RequirementRules.itemsForRead(old).stream().allMatch(item -> item.quantity() == null));
        assertEquals(6, old.quantity()); assertNull(old.items());
        assertEquals(3, RequirementRules.check(requirement(old)).missing().size());
    }
    @Test void oldSingleCategoryTotalHasUnambiguousMeaning() {
        var old = new RequirementDetails(600000L, 3, List.of(3), "办公", List.of(), List.of());
        assertEquals(List.of(new RequirementItem(3, 3)), RequirementRules.itemsForRead(old));
        assertTrue(RequirementRules.check(requirement(old)).complete());
    }
    @Test void partialQuantityDoesNotCreateTotalAndOnlyMissingCategoryIsAsked() {
        var details = RequirementRules.normalize(details(Arrays.asList(new RequirementItem(3, 2), new RequirementItem(4, null))));
        assertNull(details.quantity()); var check = RequirementRules.check(requirement(details));
        assertEquals(1, check.missing().size()); assertEquals(4, check.missing().get(0).categoryId());
    }
    @Test void explicitEmptyItemsNeverReviveOldCategories() {
        var empty = RequirementRules.normalize(details(List.of()));
        assertTrue(RequirementRules.itemsForRead(empty).isEmpty()); assertNull(empty.quantity());
        assertEquals("CATEGORY_MISSING", RequirementRules.check(requirement(empty)).missing().get(0).code());
    }
    @Test void duplicateCategoryAndContradictoryTotalOrCategoriesAreRejected() {
        assertThrows(BusinessException.class, () -> RequirementRules.normalize(details(List.of(new RequirementItem(3, 1), new RequirementItem(3, 2)))));
        var wrongCategories = new RequirementDetails(null, null, List.of(4), "", List.of(), List.of(), List.of(new RequirementItem(3, 1)));
        assertThrows(BusinessException.class, () -> RequirementRules.normalize(wrongCategories));
        var wrongTotal = new RequirementDetails(null, 6, List.of(3), "", List.of(), List.of(), List.of(new RequirementItem(3, 2)));
        assertThrows(BusinessException.class, () -> RequirementRules.normalize(wrongTotal));
    }
    @Test void knownSubtotalCannotExceedLimitEvenWithMissingOtherQuantity() {
        assertThrows(BusinessException.class, () -> RequirementRules.normalize(details(Arrays.asList(new RequirementItem(3, 6000), new RequirementItem(4, 6000), new RequirementItem(6, null)))));
    }
    @Test void checkDoesNotInferFactsFromRawText() {
        var unknown = new RequirementDetails(null, null, List.of(3), "", List.of(), List.of(), List.of(new RequirementItem(3, null)));
        assertEquals(List.of("BUDGET_MISSING", "QUANTITY_MISSING", "USAGE_MISSING"), RequirementRules.check(requirement(unknown)).missing().stream().map(RequirementCheckDTO.MissingField::code).toList());
        assertNull(unknown.budgetCents()); assertNull(unknown.items().get(0).quantity());
    }
    @Test void beanValidationRejectsNullItemInvalidCategoryAndNonPositiveCount() {
        assertFalse(validator.validate(new RequirementItem(null, 1)).isEmpty());
        assertFalse(validator.validate(new RequirementItem(7, 1)).isEmpty());
        assertFalse(validator.validate(new RequirementItem(3, 0)).isEmpty());
        assertFalse(validator.validate(new RequirementItem(3, -1)).isEmpty());
        var nullItem = new RequirementDetails(null, null, List.of(), "", List.of(), List.of(), Arrays.asList((RequirementItem)null));
        assertFalse(validator.validate(nullItem).isEmpty());
    }
    @Test void strictJsonDoesNotTruncateDecimalQuantities() {
        var mapper = new ObjectMapper().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        assertThrows(Exception.class, () -> mapper.readValue("{\"categoryId\":3,\"quantity\":1.5}", RequirementItem.class));
    }
}
