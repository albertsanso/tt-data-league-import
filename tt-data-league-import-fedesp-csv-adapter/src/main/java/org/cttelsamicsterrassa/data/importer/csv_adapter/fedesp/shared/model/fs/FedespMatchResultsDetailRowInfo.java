package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs;

public record FedespMatchResultsDetailRowInfo(
        FedespPlayerCsvInfo acbPlayer,
        FedespPlayerCsvInfo xyzPlayer,
        int matchDayNumber,
        String gameMode) {
}
