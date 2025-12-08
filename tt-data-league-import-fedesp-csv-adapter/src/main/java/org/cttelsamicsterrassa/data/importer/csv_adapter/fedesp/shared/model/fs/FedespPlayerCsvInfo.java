package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs;

public record FedespPlayerCsvInfo(
        String teamName,
        String playerLetter,
        String playerLicense,
        String playerName,
        int playerScore) {
}