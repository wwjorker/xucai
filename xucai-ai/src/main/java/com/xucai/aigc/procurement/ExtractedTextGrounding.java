package com.xucai.aigc.procurement;

import com.xucai.api.dto.mall.RequirementDTO;
import java.util.List;
import java.util.regex.Pattern;

/** 新增文本必须能在原文找到；这只是来源门槛，不是完整自然语言语义证明。 */
final class ExtractedTextGrounding {
    private ExtractedTextGrounding() { }
    private static final Pattern PREFERENCE = Pattern.compile("优先|尽量|最好|希望|偏好|倾向|便宜|低价|价格低|越.+越好");
    private static final Pattern USAGE_MARKER = Pattern.compile("用于|用来|用途\\s*[:：]|使用场景\\s*[:：]");
    private static final Pattern NEGATED_MARKER = Pattern.compile("(?:不|不能|不要|不是|并非|无需|不再|不适|不适合|不打算|不计划)\\s*$");

    static void verify(RequirementDTO input, AnalysisResult result) {
        var before = input.structured();
        var after = result.conditions();
        String raw = input.rawText() == null ? "" : input.rawText();
        if (before.usage().isBlank() && !after.usage().isBlank()) {
            requireSource(raw, after.usage());
            if (!explicitUsage(raw, after.usage()))
                throw new ModelCallException("MODEL_USAGE_UNSUPPORTED",
                        "模型新增用途缺少明确的用途表达，结果未采用；请填写用途或保持待确认");
        }
        verifyTexts(raw, before.mustHave(), after.mustHave(), false);
        verifyTexts(raw, before.optional(), after.optional(), true);
        verifyExplicitLimits(input, result);
    }

    // 有限中文合同：只检查独立的设备排除句，以及明确表示套内数量未定的句子。
    // 复杂复合句交给人工，不把相似词当等价条件，也不自动修补模型输出。
    private static void verifyExplicitLimits(RequirementDTO input, AnalysisResult result) {
        var before = input.structured();
        var after = result.conditions();
        String raw = input.rawText() == null ? "" : input.rawText();
        for (String clause : raw.split("[，,；;。！？!?\\r\\n]")) {
            String value = clause.trim();
            if (value.matches("(?:不要|不接受|不允许)(?:无线鼠标|无线键盘|蓝牙鼠标|蓝牙键盘)")) {
                // 不因其他已保存条件存在而漏掉新排除句；冲突也不能静默丢弃原文。
                if (after.mustHave().stream().noneMatch(v -> v.contains(value)))
                    throw new ModelCallException("MODEL_CONDITION_OMITTED",
                            "模型遗漏了原文明示的设备排除条件，结果未采用；请核对必须条件");
            }
            boolean unknownBundle = value.matches("每套[^，,；;。！？!?\\r\\n]*(?:几个|多少|数量|件数|组成)[^，,；;。！？!?\\r\\n]*(?:没确定|未确定|未定|待确认|不确定|未知)[^，,；;。！？!?\\r\\n]*");
            // 只拦新增总数；其他独立设备明细可以已知，不能一并拒绝。
            if (unknownBundle && before.quantity() == null && before.items() == null
                    && after.quantity() != null
                    && !explicitTotal(raw, after.quantity()))
                throw new ModelCallException("MODEL_BUNDLE_QUANTITY_UNSUPPORTED",
                        "套内设备件数尚未确定，不能用套数代替件数；结果未采用，请确认每类设备数量");
        }
    }

    private static boolean explicitTotal(String raw, int quantity) {
        for (String clause : raw.split("[，,；;。！？!?\\r\\n]"))
            if (clause.trim().matches("(?:总件数\\s*(?:为|是|[:：])?|总计|合计|总共)\\s*" + quantity + "\\s*件")) return true;
        return false;
    }

    // 只接受显式用途句中的摘录；不把采购对象、人数或商品类型作为用途。
    // 这是有限的中文输入合同，不代表能理解所有同义表达或任意否定作用范围。
    static boolean explicitUsage(String raw, String value) {
        for (String clause : raw.split("[，,；;。！？!?\\r\\n]")) {
            var marker = USAGE_MARKER.matcher(clause);
            var spans = new java.util.ArrayList<int[]>();
            while (marker.find()) spans.add(new int[]{marker.start(), marker.end()});
            for (int i = 0; i < spans.size(); i++) {
                int[] span = spans.get(i);
                if (NEGATED_MARKER.matcher(clause.substring(0, span[0])).find()) continue;
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : clause.length();
                String purpose = clause.substring(span[1], end).trim();
                if (!purpose.isEmpty() && purpose.contains(value)) return true;
            }
        }
        return false;
    }

    private static void verifyTexts(String raw, List<String> confirmed, List<String> extracted, boolean preference) {
        for (String value : extracted) {
            if (confirmed.contains(value)) continue;
            requireSource(raw, value);
            if (preference && !PREFERENCE.matcher(value).find())
                throw new ModelCallException("MODEL_PREFERENCE_UNSUPPORTED",
                        "模型新增偏好未保留明确的偏好表达，结果未采用；许可或待补信息请人工核对");
        }
    }

    private static void requireSource(String raw, String value) {
        if (value.isBlank() || !raw.contains(value))
            throw new ModelCallException("MODEL_SOURCE_UNSUPPORTED",
                    "模型新增用途或条件无法逐字对应需求原文，结果未采用，请人工核对");
    }
}
