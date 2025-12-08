package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service;

import org.cttelsamicsterrassa.data.core.domain.service.SeasonRangeValidator;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespSeasonFolderInfo;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Component
public class FedespCsvRepositoryFinderService {
    public Optional<List<FedespSeasonFolderInfo>> findAllSeasonsFoldersFrom(String baseFolder) throws IOException {

        Path baseFolderPath = Path.of(baseFolder);
        return Optional.of(Files.list(baseFolderPath)
                .filter(Files::isDirectory)
                .filter(seasonPath -> SeasonRangeValidator.isValidYearRange(seasonPath.getFileName().toString()))
                .map(seasonPath -> new FedespSeasonFolderInfo(seasonPath.getFileName().toString(), seasonPath.toString()))
                .toList()
        ).filter(l -> !l.isEmpty());
    }
}
