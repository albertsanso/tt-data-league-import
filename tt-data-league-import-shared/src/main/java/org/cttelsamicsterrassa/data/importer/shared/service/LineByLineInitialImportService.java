package org.cttelsamicsterrassa.data.importer.shared.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LineByLineInitialImportService<FileRowInfo, FileInfo> {
    protected final MatchResultDetailsByLineIterator<FileRowInfo, FileInfo> matchResultDetailsByLineIterator;

    public LineByLineInitialImportService(MatchResultDetailsByLineIterator<FileRowInfo, FileInfo> matchResultDetailsByLineIterator) {
        this.matchResultDetailsByLineIterator = matchResultDetailsByLineIterator;
    }

    protected void resetAndLoadTextFilesForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        matchResultDetailsByLineIterator.resetAndLoadTextFilesForSeason(new File(baseSeasonsFolder), seasonRange);
    }

    protected void resetAndLoadTextFilesForAllSeasons(String baseSeasonsFolder) throws IOException {
        matchResultDetailsByLineIterator.resetAndLoadTextFilesForAllSeasons(new File(baseSeasonsFolder));
    }

    protected List<FileRowInfo> fetchCsvRowInfos() {
        List<FileRowInfo> matchResultsDetailCsvFileRowInfoList = new ArrayList<>();
        while (matchResultDetailsByLineIterator.hasNext()) {
            FileRowInfo rowInfo = matchResultDetailsByLineIterator.next();
            matchResultsDetailCsvFileRowInfoList.add(rowInfo);
        }
        return matchResultsDetailCsvFileRowInfoList;
    }
}
