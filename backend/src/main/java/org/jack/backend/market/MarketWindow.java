package org.jack.backend.market;

import org.jack.backend.common.api.ApiError;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import static org.jack.backend.market.MarketData.*;

public final class MarketWindow {
    private MarketWindow() { }
    public static void validate(Query query, Clock clock) {
        if (query == null || query.start() == null || query.end() == null || query.start().getYear() != 2026
                || query.end().getYear() != 2026 || query.end().isBefore(query.start())
                || ChronoUnit.DAYS.between(query.start(), query.end()) > 119
                || query.end().isAfter(LocalDate.now(clock.withZone(ZoneId.of("America/New_York"))))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, List.of(ApiError.invalid("window")));
        }
    }
    public static void requireMatching(Query query, History history, Candidate candidate) {
        if (history == null || history.instrument() == null || !query.instrumentId().equals(history.instrument().instrumentId())
                || !query.start().equals(history.start()) || !query.end().equals(history.end())
                || !candidate.instrument().equals(history.instrument())) {
            throw new BusinessException(ErrorCode.DATA_UNUSABLE, List.of(
                    new ApiError("market", "METADATA_MISMATCH", "行情标的、口径或窗口与请求不一致")));
        }
    }
}
