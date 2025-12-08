package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.competition;

import java.util.Optional;

public enum BcnesaCompetitionType {
    PREFERENT("preferent"),
    SENIOR("senior"),
    VETERANS("veterans");

    private final String value;

    BcnesaCompetitionType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    private static Optional<BcnesaCompetitionType> findByValue(String value) {
        for (BcnesaCompetitionType competitionType : BcnesaCompetitionType.values()) {
            if (competitionType.value.equals(value)) {
                return Optional.of(competitionType);
            }
        }
        return Optional.empty();
    }

    public static BcnesaCompetitionType fromValue(String value) {
        return findByValue(value).orElseThrow(() -> new IllegalArgumentException("Unknown CompetitionType: " + value));

    }

    public static boolean existsByValue(String value) {
        return findByValue(value).isPresent();
    }
}