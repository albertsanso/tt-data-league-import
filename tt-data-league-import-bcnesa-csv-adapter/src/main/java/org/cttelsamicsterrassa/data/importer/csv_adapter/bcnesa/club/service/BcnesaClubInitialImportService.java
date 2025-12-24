package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.club.service;

import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaMatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.model.ClubNameAndYearInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.ClubNameGrouppingService;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class BcnesaClubInitialImportService extends LineByLineInitialImportService<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> {

    private final BcnesaCsvFileRowInfoExtractor rowInfoExtractor;
    private final ClubRepository clubRepository;

    @Autowired
    public BcnesaClubInitialImportService(
            BcnesaMatchResultDetailsByLineIterator matchResultDetailsByLineIterator,
            BcnesaCsvFileRowInfoExtractor rowInfoExtractor,
            ClubRepository clubRepository) {
        super(matchResultDetailsByLineIterator);
        this.rowInfoExtractor = rowInfoExtractor;
        this.clubRepository = clubRepository;
    }

    public void processClubNamesForAllSeason(String baseSeasonsFolder) throws IOException {
        resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
        importClubNames();
    }

    public void processClubNamesForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
        importClubNames();
    }

    private void importClubNames() {
        saveClubNamesInfo(fetchCsvRowInfos());
    }

    private void saveClubNamesInfo(List<BcnesaMatchResultsDetailCsvFileRowInfo> bcnesaMatchResultsDetailCsvFileInfos) {
        Map<String, List<String>> cleanClubNamesAndYears = extractClubNamesFromTeamNames(bcnesaMatchResultsDetailCsvFileInfos);
        cleanClubNamesAndYears.keySet().forEach(cleanClubName -> {
            Club clubToCreate = Club.createNew(cleanClubName);
            cleanClubNamesAndYears.get(cleanClubName).forEach(clubToCreate::addYearRange);
            createClubIfDoesntExistYet(clubToCreate);
        });
    }

    private Map<String, List<String>> extractClubNamesFromTeamNames(List<BcnesaMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfoList) {
        Pattern clubNameWithTeamNamePattern = Pattern.compile("(['\"]{1,2})(.)(['\"]{1,2})");

        List<ClubNameAndYearInfo> filteredTeamNames = matchResultsDetailCsvFileRowInfoList.stream()
                .filter(rowInfo -> !clubNameWithTeamNamePattern.matcher(rowInfoExtractor.extractTeamNameFromRowInfo(rowInfo)).find())
                .map(matchResultsDetailCsvFileRowInfo ->
                        new ClubNameAndYearInfo(
                                rowInfoExtractor.extractTeamNameFromRowInfo(matchResultsDetailCsvFileRowInfo),
                                matchResultsDetailCsvFileRowInfo.fileInfo().season()))
                .toList();

        return ClubNameGrouppingService.groupByCommonRoot(filteredTeamNames);
    }

    private void createClubIfDoesntExistYet(Club clubToCreate) {
        Optional<Club> existingClub = clubRepository.findByName(clubToCreate.getName());
        if (existingClub.isPresent()) {
            existingClub.get().updateAllRanges(clubToCreate.getYearRanges());
            clubRepository.save(existingClub.get());
        } else {
            clubRepository.save(clubToCreate);
        }
    }
}
