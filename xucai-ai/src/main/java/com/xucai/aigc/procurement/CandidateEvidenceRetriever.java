package com.xucai.aigc.procurement;

import com.xucai.api.dto.mall.*;
import com.xucai.api.dto.mall.CandidateCatalogDTO.*;
import java.util.List;
import java.util.Map;

public interface CandidateEvidenceRetriever {
    record Evidence(Map<Long,List<Chunk>> chunks,CandidateRetrievalDTO trace,boolean partial) { }
    Evidence retrieve(String runId,RequirementDTO requirement,List<Product> products);
}
