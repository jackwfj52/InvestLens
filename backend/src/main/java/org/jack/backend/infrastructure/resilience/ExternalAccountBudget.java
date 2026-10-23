package org.jack.backend.infrastructure.resilience;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;

@Service
public class ExternalAccountBudget {
    private final ExternalBudgetMapper mapper;
    private final CurrentOwner owner;
    private final Clock clock;
    private final ObjectMapper json;
    public ExternalAccountBudget(ExternalBudgetMapper mapper,CurrentOwner owner,Clock clock,ObjectMapper json){this.mapper=mapper;this.owner=owner;this.clock=clock;this.json=json;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void take(String scope,int limit) {
        if(scope==null || limit<1 || limit>10000)throw new BusinessException(ErrorCode.PROVIDER_NOT_CONFIGURED);
        String hash=CanonicalJson.sha256(json.valueToTree(scope));
        var bucket=LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MINUTES),ZoneOffset.UTC);
        mapper.ensure(owner.id(),hash,bucket);
        if(mapper.take(owner.id(),hash,bucket,limit)!=1)throw new BusinessException(ErrorCode.RATE_LIMITED);
        mapper.prune(owner.id(),hash,bucket.minusDays(1));
    }
}
