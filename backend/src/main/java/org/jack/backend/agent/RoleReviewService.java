package org.jack.backend.agent;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.ContractSchemas;
import org.jack.backend.llm.StructuredModel;
import org.jack.backend.research.*;
import org.jack.backend.workflow.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class RoleReviewService {
    public record Review(String role,String status,String snapshotHash,List<JsonNode> claims,String errorCode,String promptVersion) {
        public Review { claims=List.copyOf(claims); }
    }
    private final ResearchFreezeService snapshots;
    private final StructuredModel model;
    private final ContractSchemas schemas;
    private final ObjectMapper json;
    public RoleReviewService(ResearchFreezeService snapshots,StructuredModel model,ContractSchemas schemas,ObjectMapper json){this.snapshots=snapshots;this.model=model;this.schemas=schemas;this.json=json;}
    public Review review(WorkflowClaimService.Claim claim,boolean enabled) {
        String role=claim.step().stepKey().name();if(!Set.of("BULL","BEAR").contains(role))throw new BusinessException(ErrorCode.INVALID_STATE);
        var frozen=snapshots.get(claim.run().runId());String hash=frozen.snapshot().get("snapshotHash").asString();
        if(!enabled)return new Review(role,"DISABLED",hash,List.of(),"PROVIDER_NOT_CONFIGURED",role.toLowerCase()+"-v1");
        try {
            String prompt=new ClassPathResource("prompts/"+role.toLowerCase()+"-v1.txt").getContentAsString(StandardCharsets.UTF_8);
            var refs=new FrozenReferences(frozen);
            var input=json.valueToTree(Map.of("runId",claim.run().runId(),"snapshotHash",hash,"role",role,"hypotheses",frozen.decision().get("hypotheses"),
                    "evidence",refs.modelEvidence(),"metrics",refs.modelMetrics(),"gaps",frozen.snapshot().get("manifest").get("gaps")));
            return model.complete(claim,prompt,json.writeValueAsString(input),value->parse(value,frozen,role));
        }catch(java.io.IOException missing){throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
        catch(BusinessException failure){
            if(failure.getErrorCode()==ErrorCode.VERSION_CONFLICT)throw failure;
            return new Review(role,"MISSING",hash,List.of(),failure.getErrorCode().name(),role.toLowerCase()+"-v1");
        }
    }
    public Review parse(JsonNode value,ResearchData.Frozen frozen,String role) {
        if(!value.isObject() || value.size()!=5 || !"1.0.0".equals(value.path("schemaVersion").asString()) || !role.equals(value.path("role").asString())
                || !frozen.snapshot().get("runId").equals(value.get("runId")) || !frozen.snapshot().get("snapshotHash").equals(value.get("snapshotHash"))
                || !value.path("claims").isArray() || value.get("claims").size()>12)throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
        var refs=new FrozenReferences(frozen);var seen=new HashSet<String>();var claims=new ArrayList<JsonNode>();
        for(JsonNode claim:value.get("claims")){schemas.require("Claim",claim);refs.claim(claim,role);if(!seen.add(claim.get("claimId").asString()))throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);claims.add(claim);}
        return new Review(role,"AVAILABLE",value.get("snapshotHash").asString(),claims,null,role.toLowerCase()+"-v1");
    }
}
