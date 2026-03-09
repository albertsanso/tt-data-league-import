package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs;

import java.time.ZonedDateTime;

public record FedespMatchResultsDetailRowInfo(
        FedespPlayerCsvInfo localPlayer,
        FedespPlayerCsvInfo visitorPlayer,
        int matchDayNumber,
        String gameMode,
        ZonedDateTime matchDateTime) {
}
