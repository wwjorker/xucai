package com.xucai.trade.service;
import com.xucai.api.client.aigc.CandidateRunClient;
import com.xucai.api.client.catalog.CatalogClient;
import com.xucai.api.dto.mall.*;
import com.xucai.common.exceptions.BusinessException;
import feign.FeignException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
@Service @RequiredArgsConstructor
public class CandidateDraftService {
    private final PlanDraftService drafts;
    private final RequirementService requirements;
    private final CandidateRunClient candidates;
    private final CatalogClient catalog;
    private final Validator validator;
    public Map<String,Object> adopt(long requirementId,long merchant,String runId) {
        var old=drafts.candidateReplay(runId,requirementId,merchant);if(old!=null)return old;
        var req=requirements.get(requirementId,merchant,true);
        try {
            var run=candidates.get(requirementId,runId);
            if(run==null || !runId.equals(run.runId()) || run.candidate()==null)throw new BusinessException(503,"候选服务返回不完整");
            Map<Long,CatalogSnapshotDTO> products=new HashMap<>();Map<String,DocChunkLocationDTO> locations=new HashMap<>();
            for(var item:run.candidate().items()) {
                products.put(item.productId(),catalog.snapshot(item.productId()));
                for(var c:item.evidence())locations.put(c.chunkId(),catalog.locateChunk(c.chunkId()));
            }
            var form=CandidateDraftRules.form(run,req,products,locations);
            if(!validator.validate(form).isEmpty())throw new BusinessException(409,"候选资料不符合草稿保存要求，请人工编制");
            return drafts.createCandidate(requirementId,merchant,form,run);
        }catch(FeignException e){
            var replay=drafts.candidateReplay(runId,requirementId,merchant);if(replay!=null)return replay;
            throw new BusinessException(e.status()==404?404:e.status()==409?409:503,"候选或商品资料暂不可用，未保存新草稿");
        }
    }
}
