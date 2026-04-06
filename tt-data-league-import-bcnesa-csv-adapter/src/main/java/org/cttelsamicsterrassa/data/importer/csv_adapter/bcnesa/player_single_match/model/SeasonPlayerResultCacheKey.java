package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model;

import org.cttelsamicsterrassa.data.core.domain.model.TeamRole;

public record SeasonPlayerResultCacheKey(
        String seasonRange,
        String competitionType,
        String competitionCategory,
        String competitionScope,
        String competitionScopeTag,
        String competitionGroup,
        int matchDayNumber,
        String playerLetter,
        String playersPairingKey,
        TeamRole teamRole,
        String clubId) {
}

