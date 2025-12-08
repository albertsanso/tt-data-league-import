package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service;

import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.springframework.stereotype.Component;

@Component
public class BcnesaCsvFileRowInfoExtractor {

    public String extractTeamNameFromRowInfo(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        return rowInfo.rowInfo()[1];
    }

    public BcnesaMatchResultsDetailRowInfo extractMatchDetailsRowInfo(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        BcnesaPlayerCsvInfo abcPlayer = parsePlayerABC(rowInfo);
        BcnesaPlayerCsvInfo xyzPlayer = parsePlayerXYZ(rowInfo);
        return new BcnesaMatchResultsDetailRowInfo(abcPlayer, xyzPlayer);
    }

    private BcnesaPlayerCsvInfo parsePlayerABC(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new BcnesaPlayerCsvInfo(
                rowInfo.rowInfo()[0],
                rowInfo.rowInfo()[2],
                rowInfo.rowInfo()[3],
                rowInfo.rowInfo()[4],
                Integer.parseInt(rowInfo.rowInfo()[5])
        );
    }

    private BcnesaPlayerCsvInfo parsePlayerXYZ(BcnesaMatchResultsDetailCsvFileRowInfo rowInfo) {
        return new BcnesaPlayerCsvInfo(
                rowInfo.rowInfo()[1],
                rowInfo.rowInfo()[6],
                rowInfo.rowInfo()[7],
                rowInfo.rowInfo()[8],
                Integer.parseInt(rowInfo.rowInfo()[9])
        );
    }
}
