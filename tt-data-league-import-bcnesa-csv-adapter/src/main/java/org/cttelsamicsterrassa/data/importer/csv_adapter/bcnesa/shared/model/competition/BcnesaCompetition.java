package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.competition;

public enum BcnesaCompetition {
    BCN_SENIOR_PROVINCIAL_4A(BcnesaCompetitionType.SENIOR, "4"),
    BCN_SENIOR_PROVINCIAL_3A_B(BcnesaCompetitionType.SENIOR, "3,B"),
    BCN_SENIOR_PROVINCIAL_3A_A(BcnesaCompetitionType.SENIOR, "3,A"),
    BCN_SENIOR_PROVINCIAL_2A_B(BcnesaCompetitionType.SENIOR, "2,B"),
    BCN_SENIOR_PROVINCIAL_2A_A(BcnesaCompetitionType.SENIOR, "2,A"),
    BCN_SENIOR_PROVINCIAL_1A(BcnesaCompetitionType.SENIOR, "1"),
    BCN_SENIOR_PREFERENT(BcnesaCompetitionType.PREFERENT, "1,A"),
    BCN_VETERANS_4A_B(BcnesaCompetitionType.VETERANS, "4,B"),
    BCN_VETERANS_4A_A(BcnesaCompetitionType.VETERANS, "4,A"),
    BCN_VETERANS_3A_B(BcnesaCompetitionType.VETERANS, "3,B"),
    BCN_VETERANS_3A_A(BcnesaCompetitionType.VETERANS, "3,A"),
    BCN_VETERANS_2A_B(BcnesaCompetitionType.VETERANS, "2,B"),
    BCN_VETERANS_2A_A(BcnesaCompetitionType.VETERANS, "2,A"),
    BCN_VETERANS_1A(BcnesaCompetitionType.VETERANS, "1");

    private final BcnesaCompetitionType competitionType;

    private final String competitionLevel;

    BcnesaCompetition(BcnesaCompetitionType competitionType, String competitionLevel) {
        this.competitionType = competitionType;
        this.competitionLevel = competitionLevel;
    }
    public BcnesaCompetitionType getCompetitionType() {
        return competitionType;
    }

    public String getCompetitionLevel() {
        return competitionLevel;
    }

    public static void main(String[] args) {
        var d = BcnesaCompetition.values();
        System.out.println();
    }
}