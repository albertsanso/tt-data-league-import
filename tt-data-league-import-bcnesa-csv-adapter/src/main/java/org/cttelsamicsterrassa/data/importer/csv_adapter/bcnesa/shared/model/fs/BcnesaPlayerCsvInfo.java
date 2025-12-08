package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs;

public record BcnesaPlayerCsvInfo(
        String teamName,
        String playerLetter,
        String playerLicense,
        String playerName,
        int playerScore) {
}
