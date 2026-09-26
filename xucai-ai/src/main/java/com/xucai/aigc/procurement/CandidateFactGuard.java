package com.xucai.aigc.procurement;

import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import com.xucai.api.dto.mall.RequirementDetails;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 有限的协议事实检查；未命中不代表其他必须条件已满足。 */
final class CandidateFactGuard {
    private CandidateFactGuard() { }
    private static final Pattern UNIFYING_REQUIRED = Pattern.compile(
            "(?:必须|需要|要求)?(?:支持|兼容|使用)?(?:logitech|罗技)?unifying(?:(?:usb)?接收器(?:协议)?|协议)?(?:连接)?");

    static void verify(RequirementDetails requirement, Product product) {
        // 自由文本条件无分类归属；首版只处理单分类鼠标或键盘，避免扩大到其他设备。
        if (requirement.categoryIds().size() != 1
                || !requirement.categoryIds().get(0).equals(product.categoryId())
                || (product.categoryId() != 3 && product.categoryId() != 4)) return;
        if (requirement.mustHave().stream().map(CandidateFactGuard::normalized)
                .noneMatch(value -> UNIFYING_REQUIRED.matcher(value).matches())) return;
        var specs = product.specJson();
        String protocol = specs != null && specs.path("receiver_protocol").isTextual()
                ? normalized(specs.path("receiver_protocol").textValue()) : "";
        if (protocol.equals("logitechnon-unifying") || protocol.equals("non-unifying"))
            throw new ModelCallException("CANDIDATE_FACT_CONFLICT",
                    "本地发现接收器协议与必须条件明确冲突，模型候选未采用；请由商家核实或人工编制方案");
        boolean supported = positive(protocol);
        if (specs != null) {
            if (specs.path("connection").isTextual()) supported |= positive(normalized(specs.path("connection").textValue()));
            if (specs.path("connections").isArray())
                for (var connection : specs.path("connections"))
                    if (connection.isTextual()) supported |= positive(normalized(connection.textValue()));
        }
        if (!supported)
            throw new ModelCallException("CANDIDATE_FACT_UNSUPPORTED",
                    "当前商品资料未明确证明所需接收器协议，模型候选未采用；缺少证明不代表商品不支持，请由商家核实");
    }

    // 只认完整已定义枚举，不做含Unifying的子串匹配；明确协议反证优先于连接字段。
    private static boolean positive(String value) {
        return value.equals("logitechunifying") || value.equals("unifying")
                || value.equals("logitechunifyingusb接收器");
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "").replace('‐', '-').replace('‑', '-');
    }
}
