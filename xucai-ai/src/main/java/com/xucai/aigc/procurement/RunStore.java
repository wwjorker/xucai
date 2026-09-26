package com.xucai.aigc.procurement;
import com.xucai.api.dto.mall.RequirementDTO;
import java.util.List;

public interface RunStore {
    void insert(String id,RequirementDTO input,String model);
    default void insert(String id,RequirementDTO input,String model,String version){insert(id,input,model);}
    RunDTO get(String id,long userId);
    List<RunDTO> list(long requirementId,long userId);
    boolean finish(String id,String status,AnalysisResult result,String errorCode,String errorMessage);
    int recover();
}
