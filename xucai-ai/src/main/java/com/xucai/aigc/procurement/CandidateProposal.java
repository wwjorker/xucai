package com.xucai.aigc.procurement;

import com.xucai.api.dto.mall.CandidateCatalogDTO.Chunk;
import java.util.List;

/** 仅为运行结果中的候选参考；不是交易草稿，不提供发布、核实或下单许可。 */
public record CandidateProposal(String status,List<Item> items,long totalAmount,int consideredProducts,
                                boolean partialPool,boolean semantic,String notice,com.xucai.api.dto.mall.CandidateRetrievalDTO retrieval) {
        public CandidateProposal(String status,List<Item> items,long totalAmount,int consideredProducts,boolean partialPool,boolean semantic,String notice) {
            this(status,items,totalAmount,consideredProducts,partialPool,semantic,notice,null);
        }
    public record Item(long productId,String name,int categoryId,long specVersion,long saleVersion,
                       long unitPrice,int quantity,List<Chunk> evidence,String verdict) { }
}
