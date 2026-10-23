package org.jack.backend.evidence;

import org.jack.backend.common.exception.*;
import org.jack.backend.common.identity.CurrentOwner;
import org.jack.backend.common.json.CanonicalJson;
import org.jack.backend.market.*;
import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import org.jack.backend.portfolio.PortfolioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.jack.backend.evidence.EvidenceData.*;
import static org.jack.backend.market.MarketData.*;

@Service
public class EvidenceService {
    private final EvidenceMapper mapper;
    private final ObjectMapper json;
    private final CurrentOwner owner;
    private final PortfolioService portfolios;
    private final MarketInstrumentCatalog catalog;
    private final MarketQualityInspection inspection;

    public EvidenceService(EvidenceMapper mapper, ObjectMapper json, CurrentOwner owner, PortfolioService portfolios,
                           MarketInstrumentCatalog catalog, MarketQualityInspection inspection) {
        this.mapper = mapper; this.json = json; this.owner = owner; this.portfolios = portfolios;
        this.catalog = catalog; this.inspection = inspection;
    }

    /** Internal entry point; caller cannot supply permissions, prices, owner, quality or arbitrary URLs. */
    @Transactional
    public Snapshot freeze(String portfolioSnapshotId, List<Query> queries, DataMode mode) {
        var portfolio = portfolios.getSnapshot(portfolioSnapshotId);
        if (mode == null || mode != portfolio.dataMode()) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        if (queries == null || queries.size() > 3 || queries.stream().anyMatch(q -> q == null || q.instrumentId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var sorted = queries.stream().sorted(Comparator.comparing(Query::instrumentId)).toList();
        var seen = new HashSet<String>();
        var inputs = new ArrayList<MarketInput>();
        var contents = new ArrayList<MarketContent>();
        var gaps = new ArrayList<Gap>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime dbNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        // Validate the entire request before any provider call or write.
        for (Query query : sorted) {
            MarketWindow.validate(query, Clock.fixed(now, ZoneOffset.UTC));
            if (!seen.add(query.instrumentId())) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            Candidate candidate = catalog.requireCandidate(query.instrumentId());
            if (!candidate.instrument().quoteCurrency().equals(portfolio.baseCurrency())
                    || portfolio.positions().stream().noneMatch(p -> p.instrumentId().equals(query.instrumentId()))
                    || (mode == DataMode.SYNTHETIC) != "SYNTHETIC".equals(candidate.instrument().provider())) {
                throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            }
        }
        for (Query query : sorted) {
            Candidate candidate = catalog.requireCandidate(query.instrumentId());
            StoragePolicy policy = policy(candidate.permissions().storagePolicy());
            if (policy == StoragePolicy.NO_STORE) {
                // Do not inspect, hash or retain any provider content or identifying metadata.
                gaps.add(new Gap("NO_STORE", null, "来源禁止保存证据，无法完整重放"));
                continue;
            }
            var result = inspection.inspect(query);
            String evidenceId = "evidence_" + UUID.randomUUID();
            MarketContent content = policy == StoragePolicy.PAYLOAD_ALLOWED && result.calculationAllowed()
                    ? new MarketContent("1.0.0", result.instrument(), result.policyVersion(), result.returnBasis(),
                    "1d", result.start(), result.end(), result.asOf(), result.calculationBars()) : null;
            String hash = content == null ? null : CanonicalJson.sha256(json.valueToTree(content));
            String payloadRef = content == null ? null : evidenceId;
            var evidence = new Evidence("1.0.0", evidenceId, null, null, "SOURCE_FACT",
                    candidate.instrument().provider(), safeSource(result.source()), query.instrumentId(),
                    result.asOf(), result.retrievedAt(), result.quality(), mode, hash, policy, payloadRef,
                    result.calculationAllowed() && "ALLOWED".equals(candidate.permissions().modelInput()),
                    result.issues(), result.missingSessions());
            mapper.insertEvidence(evidenceId, owner.id(), policy.name(), json.writeValueAsString(evidence), dbNow);
            if (content != null) {
                if (mapper.insertPayload(evidenceId, json.writeValueAsString(content)) != 1) throw new BusinessException(ErrorCode.INVALID_STATE);
                contents.add(content);
            } else {
                gaps.add(new Gap(policy == StoragePolicy.METADATA_ONLY ? "METADATA_ONLY" : result.quality().name(),
                        evidenceId, "未保存可计算价格输入，无法完整重放"));
            }
            inputs.add(new MarketInput(query.instrumentId(), evidenceId, result.quality(), hash, payloadRef));
        }
        boolean replayable = gaps.isEmpty();
        String hash = replayable ? inputHash(portfolio, mode, contents, ENGINE_VERSION) : null;
        gaps.sort(Comparator.comparing(Gap::code).thenComparing(Gap::ref, Comparator.nullsFirst(Comparator.naturalOrder())));
        var snapshot = new Snapshot("1.0.0", "calculation_snapshot_" + UUID.randomUUID(), CanonicalJson.ALGORITHM,
                ENGINE_VERSION, new PortfolioRef(portfolio.snapshotId(), portfolio.version(), portfolio.contentHash()),
                mode, timestamp(now), hash, replayable, inputs, gaps);
        mapper.insertSnapshot(snapshot.snapshotId(), owner.id(), portfolio.snapshotId(), hash, json.writeValueAsString(snapshot), dbNow);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Evidence evidence(String id) { return decode(mapper.evidence(id, owner.id()), Evidence.class); }

    @Transactional(readOnly = true)
    public Snapshot snapshot(String id) { return decode(mapper.snapshot(id, owner.id()), Snapshot.class); }

    @Transactional(readOnly = true)
    public Replay replay(String id) {
        Snapshot snapshot = snapshot(id);
        if (!snapshot.replayable()) throw new BusinessException(ErrorCode.DATA_UNUSABLE);
        var portfolio = portfolios.getSnapshot(snapshot.portfolioRef().snapshotId());
        if (portfolio.version() != snapshot.portfolioRef().version()
                || !portfolio.contentHash().equals(snapshot.portfolioRef().contentHash())) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        var contents = new ArrayList<MarketContent>();
        for (MarketInput input : snapshot.marketInputs()) {
            MarketContent content = decode(mapper.payload(input.payloadRef(), owner.id()), MarketContent.class);
            if (!CanonicalJson.sha256(json.valueToTree(content)).equals(input.inputHash())) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
            contents.add(content);
        }
        if (!CanonicalJson.ALGORITHM.equals(snapshot.hashAlgorithm())
                || !inputHash(portfolio, snapshot.dataMode(), contents, snapshot.engineVersion()).equals(snapshot.inputHash())) throw new BusinessException(ErrorCode.SNAPSHOT_MISMATCH);
        return new Replay(snapshot, portfolio, contents);
    }

    private String inputHash(org.jack.backend.portfolio.PortfolioDtos.Snapshot portfolio, DataMode mode,
                             List<MarketContent> contents, String engineVersion) {
        return CanonicalJson.sha256(json.valueToTree(new InputContent("1.0.0", engineVersion,
                portfolio.contentHash(), null, List.of(), contents,
                Map.of("dataMode", mode.name(), "valuationBasis", portfolio.valuationBasis().name(), "costsIncluded", false))));
    }

    private <T> T decode(String stored, Class<T> type) {
        if (stored == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return json.readValue(stored, type);
    }
    private static StoragePolicy policy(String value) {
        try { return StoragePolicy.valueOf(value); }
        catch (RuntimeException unknown) { return StoragePolicy.NO_STORE; }
    }
    static String safeSource(String source) {
        if (source == null) return null;
        try {
            URI uri = URI.create(source);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null) return null;
            // Paths may contain credentials too. Retain only the public origin.
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
        } catch (Exception invalid) { return null; }
    }
}
