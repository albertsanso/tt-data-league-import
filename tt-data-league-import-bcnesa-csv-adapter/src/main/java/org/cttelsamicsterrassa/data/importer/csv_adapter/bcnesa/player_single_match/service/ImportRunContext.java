package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.ClubMember;
import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.model.SeasonPlayer;
import org.cttelsamicsterrassa.data.core.domain.model.SeasonPlayerResult;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.ClubMemberCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.SeasonPlayerCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.SeasonPlayerResultCacheKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the per-run in-memory caches and preloaded lookup maps used during a BCNESA results import run.
 * Lifecycle: one processMatchResultsDetailsInfo() invocation only. Discarded after the loop completes.
 *
 * Fields are public to allow access from the delegate sub-package.
 *
 * Cache entries may become detached JPA entities after each REQUIRES_NEW row transaction commits.
 * Accessing .getId() and eager @ManyToOne associations on detached entities is safe.
 * See Phase 2 notes in AGENTS.md for cache detachment analysis.
 */
public class ImportRunContext {

    public final Map<String, Club> clubsByNameMap;
    public final Map<String, Practicioner> practicionersByNameMap;

    public final Map<ClubMemberCacheKey, ClubMember> clubMemberCache = new HashMap<>();
    public final Set<ClubMemberCacheKey> clubMemberSeasonRangeUpdated = new HashSet<>();
    public final Map<SeasonPlayerCacheKey, SeasonPlayer> seasonPlayerCache = new HashMap<>();
    public final Map<SeasonPlayerResultCacheKey, SeasonPlayerResult> seasonPlayerResultCache = new HashMap<>();

    public ImportRunContext(Map<String, Club> clubsByNameMap, Map<String, Practicioner> practicionersByNameMap) {
        this.clubsByNameMap = clubsByNameMap;
        this.practicionersByNameMap = practicionersByNameMap;
    }
}
