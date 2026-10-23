package org.jack.backend.portfolio;

import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import static org.jack.backend.portfolio.PortfolioDtos.*;

@Component
public class PortfolioValidator {
    private final PortfolioInstrumentCatalog instruments;
    public PortfolioValidator(PortfolioInstrumentCatalog instruments) { this.instruments = instruments; }

    public List<Position> check(String currency, ValuationBasis basis, String total, String asOf,
                                DataMode mode, List<Position> positions) {
        if (!"USD".equals(currency)) fail(ErrorCode.CURRENCY_MISMATCH, "baseCurrency");
        BigDecimal value = new BigDecimal(total);
        if (value.signum() <= 0 || (basis == ValuationBasis.NORMALIZED && !"100".equals(total))) {
            fail(ErrorCode.VALIDATION_ERROR, "totalValue");
        }
        try {
            Instant instant = Instant.parse(asOf);
            if (asOf.startsWith("0000-")
                    || !new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(instant).equals(asOf)) {
                fail(ErrorCode.VALIDATION_ERROR, "asOf");
            }
        } catch (DateTimeParseException invalid) { fail(ErrorCode.VALIDATION_ERROR, "asOf"); }
        BigDecimal sum = BigDecimal.ZERO;
        var ids = new HashSet<String>();
        int nonCash = 0;
        boolean hasCash = false;
        for (int i = 0; i < positions.size(); i++) {
            Position position = positions.get(i);
            String field = "positions[" + i + "]";
            if (!ids.add(position.instrumentId())) fail(ErrorCode.INVALID_WEIGHTS, field + ".instrumentId");
            if (!currency.equals(position.quoteCurrency())) fail(ErrorCode.CURRENCY_MISMATCH, field + ".quoteCurrency");
            var identity = instruments.require(position.instrumentId(), mode, field + ".instrumentId");
            if (!currency.equals(identity.currency())) fail(ErrorCode.CURRENCY_MISMATCH, field + ".quoteCurrency");
            if (identity.kind() == PortfolioInstrumentCatalog.Kind.CASH) hasCash = true;
            else nonCash++;
            sum = sum.add(new BigDecimal(position.weight()));
        }
        if (sum.compareTo(BigDecimal.ONE) != 0) fail(ErrorCode.INVALID_WEIGHTS, "positions");
        if (nonCash > 3) fail(ErrorCode.UNSUPPORTED_INSTRUMENT, "positions");
        if (!hasCash) fail(ErrorCode.INSUFFICIENT_FUNDING, "positions");
        return positions.stream().sorted(Comparator.comparing(Position::instrumentId)).toList();
    }

    private static void fail(ErrorCode code, String field) {
        throw new BusinessException(code, List.of(ApiError.invalid(field)));
    }
}
