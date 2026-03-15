package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs;

import java.time.ZonedDateTime;

public record BcnesaMatchResultsDetailRowInfo(
        BcnesaPlayerCsvInfo localPlayer,
        BcnesaPlayerCsvInfo visitorPlayer,
        int matchDayNumber,
        String gameMode,
        ZonedDateTime matchDateTime) {
}
