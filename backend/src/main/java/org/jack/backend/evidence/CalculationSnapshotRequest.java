package org.jack.backend.evidence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import static org.jack.backend.common.validation.ContractPatterns.ID;

public record CalculationSnapshotRequest(@NotBlank @Pattern(regexp = "1\\.0\\.0") String schemaVersion,
                                         @NotBlank @Pattern(regexp = ID) String portfolioSnapshotId,
                                         @NotNull @Size(max = 3) List<@NotNull @Valid MarketWindow> marketInputs) {
    public record MarketWindow(@NotBlank @Pattern(regexp = ID) String instrumentId,
                               @NotNull LocalDate start, @NotNull LocalDate end) { }
}
