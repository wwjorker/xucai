package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.xucai.api.dto.mall.CandidateCatalogDTO.Product;
import java.util.*;

/** Render catalogue facts without asking a language model to invent missing differences. */
final class AssistantComparison {
    private AssistantComparison() {}
    private static final Map<String,String> LABELS = Map.ofEntries(
            Map.entry("source_region", "资料地区"),
            Map.entry("screen_inches", "屏幕尺寸（英寸）"),
            Map.entry("resolution", "分辨率"),
            Map.entry("refresh_rate_hz", "刷新率（Hz）"),
            Map.entry("usb_c_role", "USB-C用途"),
            Map.entry("printing_technology", "打印技术"),
            Map.entry("print_color", "打印颜色"),
            Map.entry("automatic_duplex", "自动双面打印"),
            Map.entry("power_input", "输入电源"),
            Map.entry("functions", "功能"),
            Map.entry("consumable", "耗材类型"),
            Map.entry("privacy_shutter", "隐私遮挡盖"),
            Map.entry("focus", "对焦方式"),
            Map.entry("earcup_controls", "耳罩控制"),
            Map.entry("receiver_protocol", "接收器协议"),
            Map.entry("scrolling", "滚轮模式"),
            Map.entry("hand_orientation", "手型"),
            Map.entry("connections", "连接方式"),
            Map.entry("max_switchable_devices", "可切换设备数"),
            Map.entry("shortcut_key_count", "快捷键数量"),
            Map.entry("keyboard_layout_region", "键位地区"),
            Map.entry("hardware_model", "硬件型号"),
            Map.entry("connection", "连接方式"),
            Map.entry("sensor", "传感器"),
            Map.entry("dpi_nominal", "标称 DPI"),
            Map.entry("battery", "电池"),
            Map.entry("cable_length_m", "线长（米）"),
            Map.entry("button_count", "按键数量"),
            Map.entry("layout_size", "键盘尺寸"),
            Map.entry("number_pad", "数字键区"),
            Map.entry("plug_and_play", "即插即用"),
            Map.entry("adjustable_tilt_legs", "可调支脚"),
            Map.entry("video_resolution", "视频分辨率"),
            Map.entry("video_fps", "帧率（fps）"),
            Map.entry("microphone", "麦克风"),
            Map.entry("light_correction", "光线校正"),
            Map.entry("mount", "固定方式"),
            Map.entry("driver_diameter_mm", "驱动单元（毫米）"),
            Map.entry("noise_reducing_microphone", "降噪麦克风"),
            Map.entry("inline_controls", "线控功能"));

    static String render(List<Product> products) {
        var answer = new StringBuilder("以下按当前商品规格对照；未提供的参数保留未知，不能据此认定不支持。\n\n**当前单价**\n");
        for (var p : products) answer.append(p.name()).append("：")
                .append(p.sale() == null || p.sale().price() == null ? "暂不可用"
                        : "¥" + java.math.BigDecimal.valueOf(p.sale().price(),2).toPlainString())
                .append(" / 件\n");
        var keys = new TreeSet<String>();
        for (var p : products) if (p.specJson() != null && p.specJson().isObject())
            p.specJson().fieldNames().forEachRemaining(keys::add);
        keys.remove("source_region");
        for (var key : keys) {
            answer.append("\n**").append(LABELS.getOrDefault(key,key)).append("**\n");
            for (var p : products) {
                JsonNode value = p.specJson() == null ? null : p.specJson().get(key);
                answer.append(p.name()).append("：").append(value(value)).append('\n');
            }
        }
        answer.append("\n以上是规格对照，不表示都满足你的预算或采购条件；采购清单未修改。未提供的参数仍需进一步核实。");
        return answer.toString();
    }

    private static String value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "资料未提供";
        if (node.isBoolean()) return node.asBoolean() ? "支持" : "不支持";
        if (node.isArray()) {
            var values = new ArrayList<String>();
            node.forEach(v -> values.add(value(v)));
            return values.isEmpty() ? "资料未提供" : String.join("、",values);
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
