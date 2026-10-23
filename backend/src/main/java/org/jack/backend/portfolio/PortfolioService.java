package org.jack.backend.portfolio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.jack.backend.common.api.ApiResponse;
import org.jack.backend.common.exception.BusinessException;
import org.jack.backend.common.exception.ErrorCode;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.jack.backend.portfolio.PortfolioDtos.*;

@Service
@Validated
public class PortfolioService {
    private final PortfolioMapper mapper;
    private final ObjectMapper json;
    private final CurrentOwner owner;
    private final PortfolioValidator validator;

    public PortfolioService(PortfolioMapper mapper, ObjectMapper json, CurrentOwner owner, PortfolioValidator validator) {
        this.mapper = mapper; this.json = json; this.owner = owner; this.validator = validator;
    }

    @Transactional
    public Snapshot create(@NotNull @Valid Create request) {
        Snapshot snapshot = snapshot("portfolio_" + UUID.randomUUID(), 1, request.baseCurrency(),
                request.valuationBasis(), request.totalValue(), request.asOf(), request.dataMode(), request.positions());
        LocalDateTime now = now();
        mapper.insertHead(snapshot.portfolioId(), owner.id(), request.name(), now);
        save(snapshot, now);
        return snapshot;
    }

    @Transactional
    public Snapshot revise(String id, @NotNull @Valid Revision request) {
        Snapshot previous = get(id, null);
        if (previous.version() != request.expectedVersion()) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if (request.expectedVersion() == Integer.MAX_VALUE) throw new BusinessException(ErrorCode.INVALID_STATE);
        Snapshot snapshot = snapshot(id, request.expectedVersion() + 1, previous.baseCurrency(),
                previous.valuationBasis(), previous.totalValue(), request.asOf(), previous.dataMode(), request.positions());
        LocalDateTime now = now();
        if (mapper.advance(id, owner.id(), request.expectedVersion(), now) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        save(snapshot, now);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Snapshot get(String id, Integer version) { return decode(mapper.find(id, owner.id(), version)); }

    @Transactional(readOnly = true)
    public Snapshot getSnapshot(String snapshotId) { return decode(mapper.findSnapshot(snapshotId, owner.id())); }

    private Snapshot decode(String stored) {
        if (stored == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return json.readValue(stored, Snapshot.class);
    }

    private Snapshot snapshot(String id, int version, String currency, ValuationBasis basis, String total,
                              String asOf, DataMode mode, java.util.List<Position> positions) {
        var sorted = validator.check(currency, basis, total, asOf, mode, positions);
        Content content = new Content(ApiResponse.SCHEMA_VERSION, CanonicalJson.ALGORITHM,
                currency, basis, total, asOf, mode, sorted);
        String hash = CanonicalJson.sha256(json.valueToTree(content));
        return new Snapshot(content.schemaVersion(), "portfolio_snapshot_" + UUID.randomUUID(), id, version,
                content.hashAlgorithm(), currency, basis, total, asOf, mode, sorted, hash);
    }

    private void save(Snapshot snapshot, LocalDateTime now) {
        mapper.insertSnapshot(snapshot.snapshotId(), snapshot.portfolioId(), snapshot.version(), snapshot.contentHash(),
                json.writeValueAsString(snapshot), now);
    }

    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
