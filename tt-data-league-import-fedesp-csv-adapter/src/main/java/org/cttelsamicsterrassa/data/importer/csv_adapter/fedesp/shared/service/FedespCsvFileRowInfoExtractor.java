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
        FedespPlayerCsvInfo abcPlayer = parsePlayerABC(rowInfo);
        FedespPlayerCsvInfo xyzPlayer = parsePlayerXYZ(rowInfo);
        int matchDayNumber = Integer.parseInt(rowInfo.rowInfo()[1]);
        String gameMode = rowInfo.rowInfo()[0];
        return new FedespMatchResultsDetailRowInfo(abcPlayer, xyzPlayer, matchDayNumber, gameMode);
    }

    private FedespPlayerCsvInfo parsePlayerABC(FedespMatchResultsDetailCsvFileRowInfo rowInfo) {
        if (rowInfo.rowInfo().length > 23) {
            System.out.println();
        }
        return new FedespPlayerCsvInfo(
                rowInfo.rowInfo()[3],
                rowInfo.rowInfo()[8],
                rowInfo.rowInfo()[7],
                rowInfo.rowInfo()[6],
                Integer.parseInt(rowInfo.rowInfo()[21].split("-")[0]),
                rowInfo.fileInfo().competitionGender()
        );
    }

    private FedespPlayerCsvInfo parsePlayerXYZ(FedespMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new FedespPlayerCsvInfo(
                rowInfo.rowInfo()[5],
                rowInfo.rowInfo()[11],
                rowInfo.rowInfo()[10],
                rowInfo.rowInfo()[9],
                Integer.parseInt(rowInfo.rowInfo()[21].split("-")[1]),
                rowInfo.fileInfo().competitionGender()
        );
    }
}
