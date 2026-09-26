package com.xucai.api.dto.mall;
import java.util.List;
/** 已完成候选的只读交接。商家选择采用后仍须逐项核实。 */
public record CandidateRunDTO(String runId,Long requirementId,Long requirementRevision,Long userId,
        String model,String promptVersion,Candidate candidate) {
    public record Candidate(String status,List<Item> items,long totalAmount,int consideredProducts,
                            boolean partialPool,boolean semantic,String notice,com.xucai.api.dto.mall.CandidateRetrievalDTO retrieval) {
        public Candidate(String status,List<Item> items,long totalAmount,int consideredProducts,boolean partialPool,boolean semantic,String notice) {
            this(status,items,totalAmount,consideredProducts,partialPool,semantic,notice,null);
        } }
    public record Item(long productId,String name,int categoryId,long specVersion,long saleVersion,
                       long unitPrice,int quantity,List<CandidateCatalogDTO.Chunk> evidence,String verdict) { }
}
