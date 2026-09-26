package com.xucai.aigc.procurement.retrieval;

import com.xucai.common.exceptions.BusinessException;
import com.xucai.common.utils.MallIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 商家显式构建和资料检索；客户候选另走只读检索链，不自动发布或下单。 */
@RestController @Validated @Profile("xucai") @RequestMapping("/admin/retrieval")
public class RetrievalController {
    static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final ObjectProvider<RetrievalService> service;
    public RetrievalController(ObjectProvider<RetrievalService> service){this.service=service;}
    public record Build(@NotBlank @Pattern(regexp=UUID) String requestId,@Positive long productId) { }
    public record Search(@NotBlank @Pattern(regexp=UUID) String requestId,@NotEmpty @Size(max=20) List<@NotNull @Positive Long> productIds,@NotBlank @Size(max=1000) String query,@Min(1) @Max(20) int topK) { }
    private RetrievalService enabled(){var enabled=service.getIfAvailable();if(enabled==null)throw new BusinessException(503,"采购向量检索未启用，原有商品与采购流程仍可使用");return enabled;}
    @GetMapping("/status") public Map<String,Object> status(){MallIdentity.merchant();return Map.of("enabled",service.getIfAvailable()!=null,"model",EmbeddingClient.MODEL,"dimensions",EmbeddingClient.DIMENSIONS,"automaticCandidateIntegration",service.getIfAvailable()!=null);}
    @PostMapping("/build") public Map<String,Object> build(@RequestBody @Valid Build input){long user=MallIdentity.merchant();return enabled().build(input.requestId(),input.productId(),user);}
    @PostMapping("/search") public Map<String,Object> search(@RequestBody @Valid Search input){long user=MallIdentity.merchant();return enabled().search(input.requestId(),input.productIds(),input.query(),input.topK(),user);}
    @GetMapping("/operations/{id}") public Map<String,Object> operation(@PathVariable @Pattern(regexp=UUID) String id){long user=MallIdentity.merchant();return enabled().operation(id,user);}
}
