package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs;

import java.util.UUID;

public record BcnesaMatchResultsDetailCsvFileRowInfo(
        BcnesaMatchResultsDetailCsvFileInfo fileInfo,
        String[] rowInfo,
        UUID uniqueRowId
) {
}
