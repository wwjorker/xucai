package com.xucai.aigc.procurement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xucai.api.dto.mall.CandidateRunDTO;
import com.xucai.common.exceptions.BusinessException;
import com.xucai.common.utils.MallIdentity;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @Validated @RequiredArgsConstructor
@RequestMapping("/admin/requirements/{requirementId}/candidates")
public class MerchantCandidateController {
    private final JdbcRunStore store;
    private final ObjectMapper mapper;
    static boolean usable(RunDTO r){return "DONE".equals(r.status()) && r.promptVersion()!=null && r.promptVersion().startsWith("candidate-reference-") && r.result()!=null && r.result().candidate()!=null && "REFERENCE".equals(r.result().candidate().status()) && r.result().candidate().items()!=null && !r.result().candidate().items().isEmpty();}
    private CandidateRunDTO dto(RunDTO r){
        if(!usable(r))throw new BusinessException(404,"此需求没有可采用的已完成候选");
        return new CandidateRunDTO(r.id(),r.requirementId(),r.requirementRevision(),r.userId(),r.model(),r.promptVersion(),mapper.convertValue(r.result().candidate(),CandidateRunDTO.Candidate.class));
    }
    @GetMapping public List<CandidateRunDTO> list(@PathVariable @Positive long requirementId){
        MallIdentity.merchant();return store.merchantCandidates(requirementId).stream().filter(MerchantCandidateController::usable).map(this::dto).toList();
    }
    @GetMapping("/{runId}") public CandidateRunDTO get(@PathVariable @Positive long requirementId,@PathVariable @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String runId){
        MallIdentity.merchant();return dto(store.merchantCandidate(requirementId,runId));
    }
}
