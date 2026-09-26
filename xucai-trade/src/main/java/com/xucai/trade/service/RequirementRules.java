package com.xucai.trade.service;

import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.RequirementCheckDTO.MissingField;
import com.xucai.common.exceptions.BusinessException;
import java.util.*;

/** 仅检查人工结构化条件是否填写；不解析原文，不证明商品满足要求。 */
public final class RequirementRules {
    private static final String[] CATEGORIES = {"", "显示器", "打印机", "鼠标", "键盘", "摄像头", "耳机"};
    private RequirementRules() { }

    public static List<RequirementItem> itemsForRead(RequirementDetails details) {
        if (details.items() != null) return details.items();
        // 只有一个分类时，旧总数才能无歧义归给该类；多个分类不能平均分或复制总数。
        return details.categoryIds().stream().map(id -> new RequirementItem(id,
                details.categoryIds().size() == 1 ? details.quantity() : null)).toList();
    }

    public static RequirementDetails normalize(RequirementDetails details) {
        if (new HashSet<>(details.categoryIds()).size() != details.categoryIds().size())
            throw invalid("设备分类不能重复");
        if (details.items() == null) return details;
        List<RequirementItem> items = details.items().stream()
                .sorted(Comparator.comparing(RequirementItem::categoryId)).toList();
        List<Integer> categories = items.stream().map(RequirementItem::categoryId).toList();
        if (new HashSet<>(categories).size() != categories.size()) throw invalid("每类设备只能填写一次");
        if (!new HashSet<>(categories).equals(new HashSet<>(details.categoryIds())))
            throw invalid("采购明细与设备分类不一致，请刷新页面后重试");
        Integer total = items.isEmpty() || items.stream().anyMatch(i -> i.quantity() == null)
                ? null : items.stream().mapToInt(RequirementItem::quantity).sum();
        if (items.stream().filter(i -> i.quantity() != null).mapToInt(RequirementItem::quantity).sum() > 10000)
            throw invalid("设备总件数不能超过10000");
        if (details.quantity() != null && !Objects.equals(details.quantity(), total))
            throw invalid("总件数与分类明细不一致，请按每类设备件数填写");
        return new RequirementDetails(details.budgetCents(), total, categories, details.usage(),
                details.mustHave(), details.optional(), items);
    }

    public static RequirementCheckDTO check(RequirementDTO requirement) {
        RequirementDetails details = requirement.structured();
        List<MissingField> missing = new ArrayList<>();
        if (details.budgetCents() == null)
            missing.add(new MissingField("BUDGET_MISSING", "budgetCents", null, "本次全部设备的总预算是多少？"));
        List<RequirementItem> items = itemsForRead(details);
        if (items.isEmpty())
            missing.add(new MissingField("CATEGORY_MISSING", "items", null, "本次需要采购哪些设备？"));
        for (RequirementItem item : items) {
            if (item.quantity() == null)
                missing.add(new MissingField("QUANTITY_MISSING", "items.quantity", item.categoryId(),
                        "需要多少件" + CATEGORIES[item.categoryId()] + "？请填写设备件数。"));
        }
        if (details.usage().isBlank())
            missing.add(new MissingField("USAGE_MISSING", "usage", null, "这些设备主要用来完成什么工作？"));
        return new RequirementCheckDTO(requirement.id(), requirement.revision(), requirement.status(),
                "RULES", missing.isEmpty(), List.copyOf(missing));
    }

    private static BusinessException invalid(String message) { return new BusinessException(400, message); }
}
