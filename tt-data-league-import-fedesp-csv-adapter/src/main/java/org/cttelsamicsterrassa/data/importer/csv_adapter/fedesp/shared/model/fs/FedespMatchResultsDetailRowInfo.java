package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs;

public record FedespMatchResultsDetailRowInfo(
        FedespPlayerCsvInfo localPlayer,
        FedespPlayerCsvInfo visitorPlayer,
        int matchDayNumber,
        String gameMode) {
}
