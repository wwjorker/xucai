package com.xucai.api.client.trade;
import com.xucai.api.dto.mall.SaleBatchDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
@FeignClient(name="xucai-trade", contextId="sale")
public interface SaleClient {
    @GetMapping("/sale-items/batch")
    SaleBatchDTO batch(@RequestParam("ids") List<Long> ids);
}
