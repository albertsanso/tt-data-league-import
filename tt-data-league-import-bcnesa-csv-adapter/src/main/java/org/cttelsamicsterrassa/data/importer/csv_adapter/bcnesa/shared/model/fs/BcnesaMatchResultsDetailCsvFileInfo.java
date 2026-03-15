package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs;

import java.nio.file.Path;

public record BcnesaMatchResultsDetailCsvFileInfo(
        Path csvFilepath,
        String season,
        String competitionType,
        String competitionCategory,
        String competitionScope,
        String competitionScopeTag,
        String competitionGender,
        String competitionGroup,
        String matchDayNumber
) {
}
