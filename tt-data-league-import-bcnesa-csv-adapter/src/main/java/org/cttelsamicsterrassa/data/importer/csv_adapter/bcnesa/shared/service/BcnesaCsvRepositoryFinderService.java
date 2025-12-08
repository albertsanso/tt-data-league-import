package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service;

import org.cttelsamicsterrassa.data.core.domain.service.SeasonRangeValidator;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.competition.BcnesaCompetition;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.competition.BcnesaCompetitionType;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaCompetitionFolderInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaCompetitionTypeFolderInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaSeasonFolderInfo;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
public class BcnesaCsvRepositoryFinderService {

    public Optional<List<BcnesaCompetitionFolderInfo>> findCompetitionFoldersFrom(String baseFolder, BcnesaCompetitionType competitionType) {
        return Optional.of(Arrays.stream(BcnesaCompetition.values())
                .map(competition ->
                        new BcnesaCompetitionFolderInfo(
                                competition,
                                buildCompetitionFolder(competition, baseFolder).toString()
                        )
                )
                .filter(competitionFolderInfo -> competitionFolderInfo.competition().getCompetitionType() == competitionType)
                .filter(competitionFolderInfo -> Files.isDirectory(Path.of(competitionFolderInfo.folder())))
                .toList()
        ).filter(l -> !l.isEmpty());
    }

    private Path buildCompetitionFolder(BcnesaCompetition competition, String baseFolder) {
        String[] split = competition.getCompetitionLevel().split(",");
        String folderName = split[0];

        if (split.length == 2) {
            folderName += split[1].toLowerCase();
        }

        return Path.of(baseFolder).resolve(folderName);
    }

    public Optional<List<BcnesaSeasonFolderInfo>> findAllSeasonsFoldersFrom(String baseFolder) throws IOException {

        Path baseFolderPath = Path.of(baseFolder);
        return Optional.of(Files.list(baseFolderPath)
                .filter(Files::isDirectory)
                .filter(seasonPath -> SeasonRangeValidator.isValidYearRange(seasonPath.getFileName().toString()))
                .map(seasonPath -> new BcnesaSeasonFolderInfo(seasonPath.getFileName().toString(), seasonPath.toString()))
                .toList()
        ).filter(l -> !l.isEmpty());
    }

    public Optional<List<BcnesaCompetitionTypeFolderInfo>> findCompetitionTypeFoldersFrom(String baseFolder) throws IOException {
        Path baseFolderPath = Path.of(baseFolder);
        return Optional.of(Files.list(baseFolderPath)
                .filter(Files::isDirectory)
                .filter(competitionTypePath ->
                        Arrays.stream(BcnesaCompetitionType.values())
                                .anyMatch(competitionType ->
                                        competitionTypePath.endsWith(Path.of(competitionType.getValue()))))
                .map(competitionTypePath -> new BcnesaCompetitionTypeFolderInfo(
                        BcnesaCompetitionType.fromValue(competitionTypePath.getFileName().toString()),
                        competitionTypePath.toString()
                ))
                .toList()
        ).filter(l -> !l.isEmpty());
    }
}

