package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs;

import java.util.UUID;

public record FedespMatchResultsDetailCsvFileRowInfo(
        FedespMatchResultsDetailCsvFileInfo fileInfo,
        String[] rowInfo,
        UUID uniqueRowId
) {
}