package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaCompetitionFolderInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaCompetitionTypeFolderInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaSeasonFolderInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BcnesaMatchResultDetailsByLineIterator extends MatchResultDetailsByLineIterator<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> {

    private static final Pattern JORNADA_AND_GRUP_FILENAME_PATTERN = Pattern.compile("jornada(\\d+)-g(\\d+)\\.csv");

    private final BcnesaCsvRepositoryFinderService csvRepositoryFinderService;

    @Autowired
    public BcnesaMatchResultDetailsByLineIterator(BcnesaCsvRepositoryFinderService csvRepositoryFinderService) {
        this.csvRepositoryFinderService = csvRepositoryFinderService;
    }

    @Override
    protected BcnesaMatchResultsDetailCsvFileRowInfo createFileRowInfo(BcnesaMatchResultsDetailCsvFileInfo currentInfo, String[] lineToReturn, UUID uuid) {
        return new BcnesaMatchResultsDetailCsvFileRowInfo(currentInfo, lineToReturn, uuid);
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

    @Override
    protected CSVReader getReaderFromBufferedReader(BcnesaMatchResultsDetailCsvFileInfo bcnesaMatchResultsDetailCsvFileInfo) throws FileNotFoundException {
        return new CSVReaderBuilder(new BufferedReader(new java.io.FileReader(bcnesaMatchResultsDetailCsvFileInfo.csvFilepath().toFile())))
                .withSkipLines(1)
                .build();
    }

    private void processSeasonFolder(BcnesaSeasonFolderInfo seasonFolderInfo) {

        Optional<List<BcnesaCompetitionTypeFolderInfo>> optCompetitionTypeFolders;
        try {
            optCompetitionTypeFolders = csvRepositoryFinderService.findCompetitionTypeFoldersFrom(seasonFolderInfo.folder());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        optCompetitionTypeFolders.ifPresent(competitionTypeFolderInfos -> {
            competitionTypeFolderInfos.forEach(competitionTypeFolderInfo ->  processCompetitionTypeFolder(competitionTypeFolderInfo, seasonFolderInfo));
        });
    }

    private void processCompetitionTypeFolder(BcnesaCompetitionTypeFolderInfo competitionTypeFolderInfo, BcnesaSeasonFolderInfo seasonFolderInfo) {
        csvRepositoryFinderService.findCompetitionFoldersFrom(competitionTypeFolderInfo.folder(), competitionTypeFolderInfo.competitionType()).ifPresent(competitionFolderInfos -> {
            competitionFolderInfos.forEach(competitionFolderInfo -> processCompetitionFolder(competitionFolderInfo, seasonFolderInfo, competitionTypeFolderInfo));
        });
    }

    private void processCompetitionFolder(BcnesaCompetitionFolderInfo competitionFolderInfo, BcnesaSeasonFolderInfo seasonFolderInfo, BcnesaCompetitionTypeFolderInfo competitionTypeFolderInfo) {
        try {
            Files.list(Path.of(competitionFolderInfo.folder())).forEach(csvFilePath -> {
                Matcher matcher = JORNADA_AND_GRUP_FILENAME_PATTERN.matcher(csvFilePath.getFileName().toString());

                if (matcher.matches()) {
                    String jornadaNumber = matcher.group(1);
                    String groupNumber = matcher.group(2);

                    BcnesaMatchResultsDetailCsvFileInfo info = new BcnesaMatchResultsDetailCsvFileInfo(
                            csvFilePath,
                            seasonFolderInfo.season(),
                            competitionTypeFolderInfo.competitionType().toString(),
                            competitionFolderInfo.competition().toString(),
                            "provincial",
                            "bcn",
                            jornadaNumber,
                            groupNumber);
                    addToQueue(info);
                } else {
                    throw new RuntimeException("Wrong file name format for match results details: %s".formatted(csvFilePath));
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
