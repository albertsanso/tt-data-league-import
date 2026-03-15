package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service;

import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class BcnesaCsvFileRowInfoExtractor {

    public String extractTeamNameFromRowInfo(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        return rowInfo.rowInfo()[11];
    }

    public BcnesaMatchResultsDetailRowInfo extractMatchDetailsRowInfo(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        BcnesaPlayerCsvInfo localPlayer = parsePlayerLocal(rowInfo);
        BcnesaPlayerCsvInfo visitorPlayer = parsePlayerVisitor(rowInfo);
        int matchDayNumber = Integer.parseInt(rowInfo.rowInfo()[0].replaceAll("\\D+", ""));
        String gameMode = rowInfo.rowInfo()[0];
        ZonedDateTime matchDateTime = parseZonedDateTime(rowInfo.rowInfo()[1]);
        return new BcnesaMatchResultsDetailRowInfo(localPlayer, visitorPlayer, matchDayNumber, gameMode, matchDateTime);
    }

    private static ZonedDateTime parseZonedDateTime(String dateStr) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yy");
        return LocalDate.parse(dateStr, formatter)
                .atStartOfDay(ZoneId.systemDefault()); // or ZoneId.systemDefault()
    }

    private BcnesaPlayerCsvInfo parsePlayerLocal(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new BcnesaPlayerCsvInfo(
                rowInfo.rowInfo()[11],
                rowInfo.rowInfo()[12],
                rowInfo.rowInfo()[13],
                rowInfo.rowInfo()[14],
                Integer.parseInt(rowInfo.rowInfo()[15]),
                rowInfo.fileInfo().competitionGender()
        );
    }

    private BcnesaPlayerCsvInfo parsePlayerVisitor(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new BcnesaPlayerCsvInfo(
                rowInfo.rowInfo()[21],
                rowInfo.rowInfo()[22],
                rowInfo.rowInfo()[23],
                rowInfo.rowInfo()[24],
                Integer.parseInt(rowInfo.rowInfo()[25]),
                rowInfo.fileInfo().competitionGender()
        );
    }
}
