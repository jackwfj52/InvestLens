package org.jack.backend.evidence;

import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.portfolio.PortfolioDtos.*;
import org.jack.backend.support.CalculationIntegrationSupport;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplayModeTests extends CalculationIntegrationSupport {
    private Snapshot cash(DataMode mode) {
        var saved=portfolios.create(new Create("1.0.0","G36 mode fixture","USD",ValuationBasis.NORMALIZED,"100","2026-10-23T00:00:00.000Z",mode,List.of(new Position(CASH,"USD","1"))));
        portfolioIds.add(saved.portfolioId());return saved;
    }
    @Test void explicitReplayUsesOnlyOwnedFixedInputsAndPreservesPortfolioSourceTime() throws Exception {
        var live=cash(DataMode.LIVE);var source=snapshot(live,List.of());var target=cash(DataMode.REPLAY);
        var reused=evidence.reuse(source.snapshotId(),target.snapshotId());track(reused);
        assertThat(reused.dataMode()).isEqualTo(DataMode.REPLAY);assertThat(evidence.replay(reused.snapshotId()).portfolio().asOf()).isEqualTo(live.asOf());
        assertThat(evidence.snapshot(reused.snapshotId())).isEqualTo(reused);assertThat(reused.inputHash()).isNotEqualTo(source.inputHash());verifyNoInteractions(provider);
        assertThatThrownBy(()->evidence.reuse(source.snapshotId(),live.snapshotId())).isInstanceOf(BusinessException.class);
    }
    @Test void syntheticCannotBeRelabeledReplayAndReplayCannotRequestFreshPrices() throws Exception {
        var synthetic=cash(DataMode.SYNTHETIC);var source=snapshot(synthetic,List.of());var target=cash(DataMode.REPLAY);
        assertThatThrownBy(()->evidence.reuse(source.snapshotId(),target.snapshotId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->evidence.freeze(target.snapshotId(),List.of(new org.jack.backend.market.MarketData.Query(GOLD,java.time.LocalDate.parse("2026-06-01"),java.time.LocalDate.parse("2026-06-03"))),DataMode.REPLAY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->evidence.reuse("unknown",target.snapshotId())).isInstanceOf(BusinessException.class);verifyNoInteractions(provider);
    }
}
