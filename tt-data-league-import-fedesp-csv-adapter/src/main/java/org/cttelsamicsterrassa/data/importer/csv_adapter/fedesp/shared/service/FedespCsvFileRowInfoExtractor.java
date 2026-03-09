package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service;

import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespPlayerCsvInfo;
import org.springframework.stereotype.Component;

@Component
public class FedespCsvFileRowInfoExtractor {

    public String extractTeamNameFromRowInfo(FedespMatchResultsDetailCsvFileRowInfo rowInfo) {
        return rowInfo.rowInfo()[3];
    }

    public FedespMatchResultsDetailRowInfo extractMatchDetailsRowInfo(FedespMatchResultsDetailCsvFileRowInfo rowInfo) {
        FedespPlayerCsvInfo localPlayer = parsePlayerLocal(rowInfo);
        FedespPlayerCsvInfo visitorPlayer = parsePlayerVisitor(rowInfo);
        //int matchDayNumber = Integer.parseInt(rowInfo.rowInfo()[1]);
        int matchDayNumber = Integer.parseInt(rowInfo.rowInfo()[0].replaceAll("\\D+", ""));
        String gameMode = rowInfo.rowInfo()[0];
        return new FedespMatchResultsDetailRowInfo(localPlayer, visitorPlayer, matchDayNumber, gameMode);
    }

    private FedespPlayerCsvInfo parsePlayerLocal(FedespMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new FedespPlayerCsvInfo(
                rowInfo.rowInfo()[3],
                rowInfo.rowInfo()[10],
                rowInfo.rowInfo()[13],
                rowInfo.rowInfo()[14],
                Integer.parseInt(rowInfo.rowInfo()[26]),
                rowInfo.fileInfo().competitionGender()
        );
    }

    private FedespPlayerCsvInfo parsePlayerVisitor(FedespMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new FedespPlayerCsvInfo(
                rowInfo.rowInfo()[5],
                rowInfo.rowInfo()[11],
                rowInfo.rowInfo()[17],
                rowInfo.rowInfo()[18],
                Integer.parseInt(rowInfo.rowInfo()[27]),
                rowInfo.fileInfo().competitionGender()
        );
    }
}
