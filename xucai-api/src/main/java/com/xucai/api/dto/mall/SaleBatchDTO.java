package com.xucai.api.dto.mall;
import java.util.List;
public record SaleBatchDTO(boolean available, List<SaleItemDTO> items) { }
