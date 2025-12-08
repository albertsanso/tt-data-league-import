package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespSeasonFolderInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FedespMatchResultDetailsByLineIterator extends MatchResultDetailsByLineIterator<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> {

    private static final String CSV_FILE_MATCHES_FOR_SEASON_GENDER_CATEGORY_GROUP_PATTERN_STRING = "rfetm-%s-(\\w+)-([a-zA-Z0-9-]+?)-group-(\\d+)-teamid-(\\d+)_matches\\.csv";

    private final FedespCsvRepositoryFinderService csvRepositoryFinderService;

    @Autowired
    public FedespMatchResultDetailsByLineIterator(FedespCsvRepositoryFinderService csvRepositoryFinderService) {
        this.csvRepositoryFinderService = csvRepositoryFinderService;
    }

    @Override
    protected FedespMatchResultsDetailCsvFileRowInfo createFileRowInfo(FedespMatchResultsDetailCsvFileInfo currentInfo, String[] lineToReturn, UUID uuid) {
        return new FedespMatchResultsDetailCsvFileRowInfo(currentInfo, lineToReturn, uuid);
    }

    @Override
    protected void processMatchesDetailsForAllSeasons(String baseNatchesDetailsCsvFilesFolder) throws IOException {
        csvRepositoryFinderService.findAllSeasonsFoldersFrom(baseNatchesDetailsCsvFilesFolder)
                .ifPresent(seasonFolderInfos ->
                        seasonFolderInfos.stream()
                                .forEach(this::processSeasonFolder)
                );
    }

    @Override
    protected void processMatchesDetailsForSeason(String baseNatchesDetailsCsvFilesFolder, String seasonRange) throws IOException {
        csvRepositoryFinderService.findAllSeasonsFoldersFrom(baseNatchesDetailsCsvFilesFolder)
                .ifPresent(seasonFolderInfos ->
                        seasonFolderInfos.stream()
                                .filter(seasonFolderInfo -> seasonFolderInfo.season().equals(seasonRange))
                                .forEach(this::processSeasonFolder)
                );
    }

    private void processSeasonFolder(FedespSeasonFolderInfo seasonFolderInfo) {

        String fileNamePatternString = CSV_FILE_MATCHES_FOR_SEASON_GENDER_CATEGORY_GROUP_PATTERN_STRING.formatted(seasonFolderInfo.season());
        final Pattern csvFilePattern = Pattern.compile(fileNamePatternString);

        try {
            Files.list(Path.of(seasonFolderInfo.folder())).forEach(csvFilePath -> {
                Matcher matcher = csvFilePattern.matcher(csvFilePath.getFileName().toString());
                if (matcher.matches()) {
                    String gender = matcher.group(1);
                    String categoria = matcher.group(2);
                    String groupNumber = matcher.group(3);

                    FedespMatchResultsDetailCsvFileInfo info = new FedespMatchResultsDetailCsvFileInfo(
                            csvFilePath,
                            seasonFolderInfo.season(),
                            "senior",
                            categoria,
                            "nacional",
                            "esp",
                            gender,
                            groupNumber,
                            "");
                    addToQueue(info);
                } else {
                    throw new RuntimeException("Wrong file name format for match results details: %s".formatted(csvFilePath));
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected CSVReader getReaderFromBufferedReader(FedespMatchResultsDetailCsvFileInfo fedespMatchResultsDetailCsvFileInfo) throws FileNotFoundException {
        return new CSVReaderBuilder(new BufferedReader(new FileReader(fedespMatchResultsDetailCsvFileInfo.csvFilepath().toFile())))
                .withSkipLines(1)
                .build();
    }
}
