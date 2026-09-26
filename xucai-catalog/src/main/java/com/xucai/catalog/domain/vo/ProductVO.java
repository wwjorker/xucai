package com.xucai.catalog.domain.vo;
import com.fasterxml.jackson.databind.JsonNode;
import com.xucai.api.dto.mall.SaleItemDTO;
import lombok.Data;
@Data
public class ProductVO {
    private Long id;
    private String name;
    private String brand;
    private String series;
    private Integer categoryId;
    private String imageUrl;
    private String description;
    private JsonNode specJson;
    private Long specVersion;
    private String status;
    private SaleItemDTO sale;
    private boolean priceAvailable;
    private boolean purchasable;
    private String purchaseMessage;
}
