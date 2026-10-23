package org.jack.backend.portfolio;

import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Input identities only. G09 must register qualified real instruments; no ID-prefix guessing. */
@Component
public class PortfolioInstrumentCatalog {
    public enum Kind { STOCK, ETF, CASH }
    public record Identity(Kind kind, String currency, boolean syntheticOnly) { }
    private static final Map<String, Identity> IDENTITIES = Map.of(
            "CASH:USD", new Identity(Kind.CASH, "USD", false),
            "ETF:SYNTHETIC:DEMO_GOLD:USD", new Identity(Kind.ETF, "USD", true),
            "ETF:SYNTHETIC:DEMO_SILVER:USD", new Identity(Kind.ETF, "USD", true),
            "STOCK:SYNTHETIC:DEMO_STOCK:USD", new Identity(Kind.STOCK, "USD", true));

    public Identity require(String id, PortfolioDtos.DataMode mode, String field) {
        Identity identity = IDENTITIES.get(id);
        if (identity == null || (identity.syntheticOnly() && mode != PortfolioDtos.DataMode.SYNTHETIC)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_INSTRUMENT, java.util.List.of(ApiError.invalid(field)));
        }
        return identity;
    }
}
