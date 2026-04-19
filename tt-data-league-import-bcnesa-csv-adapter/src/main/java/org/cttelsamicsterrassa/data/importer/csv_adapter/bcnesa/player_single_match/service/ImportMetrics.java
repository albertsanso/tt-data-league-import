package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

/**
 * Accumulates counters and timing measurements for one BCNESA results import run.
 * Fields are public to allow access from the delegate sub-package.
 */
public class ImportMetrics {

    // --- Row flow ---
    public long rowsTotal;
    public long rowsProcessed;
    public long rowsSkippedPlayerD;
    public long rowsSkippedInferenceMiss;
    public long rowExceptions;
    public long inferenceMisses;

    // --- Preloaded map lookups (Phase 1) ---
    public long clubLookupHit;
    public long clubLookupMiss;
    public long practicionerLookupHit;
    public long practicionerLookupMiss;

    // --- In-memory cache hits/misses (Phase 1) ---
    public long clubMemberCacheHit;
    public long clubMemberCacheMiss;
    public long seasonPlayerCacheHit;
    public long seasonPlayerCacheMiss;
    public long seasonPlayerResultCacheHit;
    public long seasonPlayerResultCacheMiss;
    public long playersSingleMatchCacheHit;
    public long playersSingleMatchCacheMiss;

    // --- Persistence (Phase 1) ---
    public long clubMemberSaved;
    public long seasonPlayerSaved;
    public long seasonPlayerResultSaved;
    public long playersSingleMatchSaved;
    public long saveSkippedNoChange;

    // --- Timing (Phase 1) ---
    public long preloadMs;
    public long rowLoopMs;
    public long totalMs;

    // --- Phase 2 additions ---
    /** Rows whose REQUIRES_NEW transaction rolled back (exception surfaced to service outer catch). */
    public long transactionRollbacks;
    /** Total explicit EntityManager.flush() calls placed to enforce FK insert ordering. */
    public long entityManagerFlushCount;
    /** Rows skipped via defensive null guard after get-or-create (should be 0 in practice). */
    public long rowsSkippedNullEntity;
}
