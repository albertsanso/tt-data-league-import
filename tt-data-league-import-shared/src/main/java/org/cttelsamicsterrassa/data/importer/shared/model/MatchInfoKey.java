package org.cttelsamicsterrassa.data.importer.shared.model;

public record MatchInfoKey (String season,
                            String competitionType,
                            String competitionCategory,
                            String competitionScope,
                            String competitionScopeTag,
                            String competitionGroup,
                            int matchDayNumber,
                            String teamNameAbc,
                            String teamNameXyz) {
}
