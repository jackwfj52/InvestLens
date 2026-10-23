package org.jack.backend.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

import static org.jack.backend.common.validation.ContractPatterns.*;

public final class PortfolioDtos {
    private PortfolioDtos() { }
    public enum ValuationBasis { NORMALIZED, ABSOLUTE }
    public enum DataMode { LIVE, CACHE, REPLAY, SYNTHETIC }

    public record Position(@NotBlank @Pattern(regexp = ID) String instrumentId,
                           @NotBlank @Pattern(regexp = "[A-Z]{3}") String quoteCurrency,
                           @NotBlank @Pattern(regexp = RATIO) String weight) { }

    public record Create(@NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
                         @NotBlank @Size(max = 200) String name,
                         @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
                         @NotNull ValuationBasis valuationBasis,
                         @NotBlank @Pattern(regexp = DECIMAL) String totalValue,
                         @NotBlank @Pattern(regexp = UTC_MILLIS) String asOf,
                         @NotNull DataMode dataMode,
                         @NotNull @Size(min = 1, max = 4) List<@NotNull @Valid Position> positions) { }

    public record Revision(@NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
                           @NotNull @Positive Integer expectedVersion,
                           @NotNull @Size(min = 1, max = 4) List<@NotNull @Valid Position> positions,
                           @NotBlank @Pattern(regexp = UTC_MILLIS) String asOf) { }

    public record Snapshot(String schemaVersion, String snapshotId, String portfolioId, int version,
                           String hashAlgorithm, String baseCurrency, ValuationBasis valuationBasis,
                           String totalValue, String asOf, DataMode dataMode, List<Position> positions,
                           String contentHash) {
        public Snapshot { positions = List.copyOf(positions); }
    }

    /** Only these fields participate in contentHash. */
    public record Content(String schemaVersion, String hashAlgorithm, String baseCurrency,
                          ValuationBasis valuationBasis, String totalValue, String asOf,
                          DataMode dataMode, List<Position> positions) { }
}
