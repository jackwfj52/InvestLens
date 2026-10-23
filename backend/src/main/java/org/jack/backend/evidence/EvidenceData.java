package org.jack.backend.evidence;

import org.jack.backend.market.MarketData.*;
import org.jack.backend.market.MarketQuality.*;
import org.jack.backend.portfolio.PortfolioDtos.DataMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** G11 independent calculation inputs. Run/display IDs are assigned by the later workflow. */
public final class EvidenceData {
    private EvidenceData() { }
    public static final String ENGINE_VERSION = "financial-engine-v1";
    public enum StoragePolicy { PAYLOAD_ALLOWED, METADATA_ONLY, NO_STORE }
    public record Gap(String code, String ref, String message) { }
    public record Evidence(String schemaVersion, String evidenceId, String displayId, String runId,
                           String type, String provider, String sourceUrl, String instrumentId,
                           String asOf, String retrievedAt, Quality quality, DataMode dataMode,
                           String payloadHash, StoragePolicy storagePolicy, String payloadRef,
                           boolean modelInputAllowed, List<Issue> issues, List<LocalDate> missingSessions) {
        public Evidence { issues = List.copyOf(issues); missingSessions = List.copyOf(missingSessions); }
    }
    /** Only quality-approved normalized prices; never the upstream raw response. */
    public record MarketContent(String schemaVersion, Instrument instrument, String qualityPolicyVersion,
                                ReturnBasis returnBasis, String interval, LocalDate start, LocalDate end,
                                String asOf, List<Bar> bars) {
        public MarketContent { bars = List.copyOf(bars); }
    }
    public record MarketInput(String instrumentId, String evidenceRef, Quality quality,
                              String inputHash, String payloadRef) { }
    public record PortfolioRef(String snapshotId, int version, String contentHash) { }
    public record Snapshot(String schemaVersion, String snapshotId, String hashAlgorithm, String engineVersion,
                           PortfolioRef portfolioRef, DataMode dataMode, String createdAt, String inputHash,
                           boolean replayable, List<MarketInput> marketInputs, List<Gap> gaps) {
        public Snapshot { marketInputs = List.copyOf(marketInputs); gaps = List.copyOf(gaps); }
    }
    public record Replay(Snapshot snapshot, org.jack.backend.portfolio.PortfolioDtos.Snapshot portfolio,
                         List<MarketContent> marketInputs) {
        public Replay { marketInputs = List.copyOf(marketInputs); }
    }
    /** Exact G01 hash envelope. IDs and retrieval timestamps are deliberately outside this content. */
    public record InputContent(String schemaVersion, String engineVersion, String portfolioContentHash,
                               Object action, List<Object> shocks, List<MarketContent> marketInputs,
                               Map<String, Object> parameters) { }
}
