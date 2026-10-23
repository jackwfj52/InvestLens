package org.jack.backend.llm;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.json.ContractSchemas;
import org.jack.backend.workflow.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.function.Function;
import static org.jack.backend.workflow.CallBudgetData.Purpose;

@Service
public class StructuredModel {
    private final BudgetedLlm llm;
    private final ContractSchemas schemas;
    public StructuredModel(BudgetedLlm llm,ContractSchemas schemas){this.llm=llm;this.schemas=schemas;}
    public <T> T complete(WorkflowClaimService.Claim claim,String prompt,String input,Function<JsonNode,T> validate) {
        for(int attempt=0;attempt<2;attempt++)try {
            var reply=llm.complete(claim,attempt==0?Purpose.STANDARD:Purpose.SCHEMA_REPAIR,
                    new LlmClient.Request(prompt+(attempt==0?"":"\nRepair the JSON/schema using the same fixed inputs. Do not invent missing evidence."),input,2048));
            return validate.apply(schemas.parse(reply.content()));
        }catch(BusinessException failure){if(failure.getErrorCode()!=ErrorCode.MODEL_OUTPUT_INVALID || attempt==1)throw failure;}
        throw new BusinessException(ErrorCode.MODEL_OUTPUT_INVALID);
    }
}
