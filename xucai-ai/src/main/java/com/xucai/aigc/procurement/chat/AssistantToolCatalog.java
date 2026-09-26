package com.xucai.aigc.procurement.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.*;

/** Typed tool contracts. Trusted identity and execution context never enter model parameters. */
public final class AssistantToolCatalog {
    public enum Reason {
        PRODUCT,
        QUANTITY,
        OPERATION,
        UNSUPPORTED
    }

    public record RequirementItem(int categoryId, int quantity) {}

    private final AssistantTools executor;
    private final ObjectMapper mapper;
    private final List<Map<String, Object>> definitions;

    AssistantToolCatalog(AssistantTools executor, ObjectMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
        this.definitions =
                Arrays.stream(ToolCallbacks.from(this))
                        .map(
                                callback -> {
                                    var definition = callback.getToolDefinition();
                                    try {
                                        var schema =
                                                (ObjectNode)
                                                        mapper.readTree(definition.inputSchema());
                                        if (definition.name().equals("prepare_requirement")) {
                                            var properties = schema.withObject("properties");
                                            ((ObjectNode) properties.get("items"))
                                                    .put("maxItems", 6);
                                            ((ObjectNode) properties.get("mustHave"))
                                                    .put("maxItems", 20);
                                            ((ObjectNode) properties.get("optional"))
                                                    .put("maxItems", 20);
                                        }
                                        return Map.<String, Object>of(
                                                "type",
                                                "function",
                                                "function",
                                                Map.of(
                                                        "name",
                                                        definition.name(),
                                                        "description",
                                                        definition.description(),
                                                        "parameters",
                                                        mapper.convertValue(schema, Map.class)));
                                    } catch (Exception e) {
                                        throw new IllegalStateException("Invalid tool schema", e);
                                    }
                                })
                        .sorted(
                                Comparator.comparing(
                                        d ->
                                                ((Map<?, ?>) d.get("function"))
                                                        .get("name")
                                                        .toString()))
                        .toList();
    }

    List<Map<String, Object>> definitions(String agent) {
        return definitions.stream()
                .filter(
                        d ->
                                AssistantTools.ALLOWED
                                        .getOrDefault(agent, Set.of())
                                        .contains(((Map<?, ?>) d.get("function")).get("name")))
                .toList();
    }

    private com.fasterxml.jackson.databind.JsonNode dispatch(
            String name, Map<String, Object> arguments, ToolContext trusted) {
        if (trusted == null
                || !(trusted.getContext().get("xucai.context")
                        instanceof AssistantTools.Context context)
                || !(trusted.getContext().get("xucai.agent") instanceof String agent))
            throw new IllegalStateException("Trusted server tool context required");
        return mapper.valueToTree(
                executor.execute(agent, name, mapper.valueToTree(arguments), context));
    }

    @Tool(
            name = "ask_clarification",
            description =
                    "条件不清或操作不支持时结束本轮并向客户说明，不修改业务。reason只允许PRODUCT（不清楚哪款）、QUANTITY（件数未知）、OPERATION（不清楚设置还是增减）、UNSUPPORTED（支付/取消等不支持的操作）。")
    public com.fasterxml.jackson.databind.JsonNode ask_clarification(
            @ToolParam(description = "PRODUCT商品不明、QUANTITY数量不明、OPERATION操作不明、UNSUPPORTED不支持")
                    Reason reason,
            ToolContext context) {
        return dispatch("ask_clarification", Map.of("reason", reason), context);
    }

    @Tool(
            name = "adjust_cart_quantity",
            description =
                    "按明确指令给最近商品卡中的商品增加或减少件数，由服务端计算最终数量，无需先查询清单。delta为本次增减量，正数增加、负数减少；不能把最终数量当增减量。指代不清先ask_clarification。")
    public com.fasterxml.jackson.databind.JsonNode adjust_cart_quantity(
            @ToolParam(description = "最近商品卡第几款，从1开始，不是清单行号") int position,
            @ToolParam(description = "本次增减量，-999到999，不能为0") int delta,
            ToolContext context) {
        return dispatch(
                "adjust_cart_quantity", Map.of("position", position, "delta", delta), context);
    }

    @Tool(
            name = "prepare_requirement",
            description =
                    "为客户明确要求商家核实的复杂采购整理可编辑草稿，不会提交。只整理客户已说的条件，不加入模型推荐作为客户要求；保留否定和待核实事项。未知预算/数量填0，未知用途空字符串；预算单位人民币分，类别1显示器2打印机3鼠标4键盘5摄像头6耳机。")
    public com.fasterxml.jackson.databind.JsonNode prepare_requirement(
            @ToolParam(description = "简短标题，最多100字") String title,
            @ToolParam(description = "原始采购条件，保留未知和否定，最多4000字") String rawText,
            @ToolParam(description = "客户明确用途，未知空字符串") String usage,
            @ToolParam(description = "总预算人民币分，未知0，最大100000000") long budgetCents,
            @ToolParam(description = "最多6个品类，categoryId为1至6，quantity未知0")
                    List<RequirementItem> items,
            @ToolParam(description = "必须条件，最多20条") List<String> mustHave,
            @ToolParam(description = "优先条件，最多20条") List<String> optional,
            ToolContext context) {
        return dispatch(
                "prepare_requirement",
                Map.of(
                        "title",
                        title,
                        "rawText",
                        rawText,
                        "usage",
                        usage,
                        "budgetCents",
                        budgetCents,
                        "items",
                        items,
                        "mustHave",
                        mustHave,
                        "optional",
                        optional),
                context);
    }

    @Tool(
            name = "search_products",
            description =
                    "搜索当前公开商品。category为0全部/1显示器/2打印机/3鼠标/4键盘/5摄像头/6耳机；keyword是商品名或规格中的连续字面子串，只用于用户明确指定的品牌或型号（如M100），不是语义检索。按品类浏览或补充预算/数量/用途时keyword必须为空字符串；不要把品类名、预算、用途、连接要求组合成keyword。连接条件由服务端单独筛选。返回有限商品卡片，顺序就是客户看到的顺序。")
    public com.fasterxml.jackson.databind.JsonNode search_products(
            @ToolParam(description = "0全部，1显示器2打印机3鼠标4键盘5摄像头6耳机") int category,
            @ToolParam(description = "型号或短关键词") String keyword,
            ToolContext context) {
        return dispatch(
                "search_products", Map.of("category", category, "keyword", keyword), context);
    }

    @Tool(name = "read_product", description = "重新读取最近展示卡片的商品详情。position从1开始；不要使用商品ID。")
    public com.fasterxml.jackson.databind.JsonNode read_product(
            @ToolParam(description = "最近展示的第几款，从1开始") int position, ToolContext context) {
        return dispatch("read_product", Map.of("position", position), context);
    }

    @Tool(name = "compare_products", description = "一次查找并读取两到三款商品的完整规格与当前资料用于比较，不修改清单。keywords必须是用户提到或最近卡片中的型号，不能猜商品ID。比较时优先使用此工具，不逐款反复搜索。未找到或有重名时先澄清。")
    public com.fasterxml.jackson.databind.JsonNode compare_products(
            @ToolParam(description = "两到三个不同型号或完整商品名，必须来自用户消息或最近商品卡")
                    List<String> keywords, ToolContext context) {
        return dispatch("compare_products", Map.of("keywords", keywords), context);
    }

    @Tool(
            name = "read_evidence",
            description = "按问题检索最近卡片中某款的当前资料片段；保留否定和地区限制，无资料时不能猜测。不传账户、订单或联系人信息。")
    public com.fasterxml.jackson.databind.JsonNode read_evidence(
            @ToolParam(description = "最近展示的第几款，从1开始") int position,
            @ToolParam(description = "仅商品技术问题，最多300字") String query,
            ToolContext context) {
        return dispatch("read_evidence", Map.of("position", position, "query", query), context);
    }

    @Tool(name = "read_cart", description = "查看客户当前采购清单。")
    public com.fasterxml.jackson.databind.JsonNode read_cart(ToolContext context) {
        return dispatch("read_cart", Map.of(), context);
    }

    @Tool(
            name = "set_cart_quantity",
            description = "按用户明确要求设置最近展示卡片中某款在清单里的最终数量。0表示移除。不得自行添加或更换商品；不确定指代或数量时先问客户。")
    public com.fasterxml.jackson.databind.JsonNode set_cart_quantity(
            @ToolParam(description = "最近展示的第几款，从1开始") int position,
            @ToolParam(description = "最终件数0到999，0移除，不是增加量") int quantity,
            ToolContext context) {
        return dispatch(
                "set_cart_quantity", Map.of("position", position, "quantity", quantity), context);
    }

    @Tool(name = "list_orders", description = "查询当前登录客户自己的最近订单，不返回收货地址或电话。")
    public com.fasterxml.jackson.databind.JsonNode list_orders(ToolContext context) {
        return dispatch("list_orders", Map.of(), context);
    }

    @Tool(name = "open_checkout", description = "展示采购清单入口供客户填写收货信息并确认，不会创建订单。")
    public com.fasterxml.jackson.databind.JsonNode open_checkout(ToolContext context) {
        return dispatch("open_checkout", Map.of(), context);
    }
}
