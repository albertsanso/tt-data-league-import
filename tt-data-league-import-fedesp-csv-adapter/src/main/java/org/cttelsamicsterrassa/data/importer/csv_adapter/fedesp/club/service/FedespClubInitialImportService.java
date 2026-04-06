package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service;

import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespMatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.model.ClubNameAndYearInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.ClubNameGrouppingService;
import org.cttelsamicsterrassa.data.importer.shared.service.CompletionTracker;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class FedespClubInitialImportService
        extends LineByLineInitialImportService<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> {

    private static final int WRITE_BATCH_SIZE = 100;

    private final ClubRepository clubRepository;

    private final FedespCsvFileRowInfoExtractor rowInfoExtractor;

    @Autowired
    public FedespClubInitialImportService(
            FedespMatchResultDetailsByLineIterator matchResultDetailsByLineIterator,
            ClubRepository clubRepository,
            FedespCsvFileRowInfoExtractor rowInfoExtractor) {
        super(matchResultDetailsByLineIterator);
        this.clubRepository = clubRepository;
        this.rowInfoExtractor = rowInfoExtractor;
    }

    @Transactional
    public void processClubNamesForAllSeason(String baseSeasonsFolder) throws IOException {
        long fileDiscoveryStart = System.currentTimeMillis();
        resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
        long fileDiscoveryMs = System.currentTimeMillis() - fileDiscoveryStart;
        importClubNames(fileDiscoveryMs, "all seasons");
    }

    @Transactional
    public void processClubNamesForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        long fileDiscoveryStart = System.currentTimeMillis();
        resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
        long fileDiscoveryMs = System.currentTimeMillis() - fileDiscoveryStart;
        importClubNames(fileDiscoveryMs, "season " + seasonRange);
    }


    private void importClubNames(long fileDiscoveryMs, String scopeLabel) {
        long fetchStart = System.currentTimeMillis();
        List<FedespMatchResultsDetailCsvFileRowInfo> rows = fetchCsvRowInfos();
        long fetchMs = System.currentTimeMillis() - fetchStart;

        ImportStats stats = saveClubNamesInfo(rows);

        System.out.printf("[FEDESP][Clubs] scope=%s fileDiscoveryMs=%d rowFetchMs=%d groupingMs=%d persistenceMs=%d totalRows=%d groupedClubs=%d dbReads=%d dbWrites=%d inserted=%d updated=%d unchanged=%d%n",
                scopeLabel,
                fileDiscoveryMs,
                fetchMs,
                stats.groupingMs,
                stats.persistenceMs,
                rows.size(),
                stats.groupedClubs,
                stats.dbReads,
                stats.dbWrites,
                stats.inserted,
                stats.updated,
                stats.unchanged
        );
    }

    private ImportStats saveClubNamesInfo(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileInfos) {
        long groupingStart = System.currentTimeMillis();
        Map<String, List<String>> cleanClubNamesAndYears = extractClubNamesFromTeamNames(fedespMatchResultsDetailCsvFileInfos);
        long groupingMs = System.currentTimeMillis() - groupingStart;

        Map<String, Club> existingClubsByName = new HashMap<>();
        clubRepository.findAll().forEach(club -> existingClubsByName.put(club.getName(), club));

        ImportStats stats = new ImportStats();
        stats.groupingMs = groupingMs;
        stats.groupedClubs = cleanClubNamesAndYears.size();
        stats.dbReads = 1;

        CompletionTracker completionTracker = CompletionTracker.buildTracker(cleanClubNamesAndYears.size(), 10, "Club import");
        long persistenceStart = System.currentTimeMillis();
        List<Club> clubsToInsert = new ArrayList<>(WRITE_BATCH_SIZE);
        List<Club> clubsToUpdate = new ArrayList<>(WRITE_BATCH_SIZE);

        cleanClubNamesAndYears.keySet().forEach(cleanClubName -> {
            createOrUpdateClub(cleanClubName, cleanClubNamesAndYears.get(cleanClubName), existingClubsByName, clubsToInsert, clubsToUpdate, stats);

            if (clubsToInsert.size() >= WRITE_BATCH_SIZE) {
                persistClubsBatch(clubsToInsert, stats);
            }
            if (clubsToUpdate.size() >= WRITE_BATCH_SIZE) {
                persistClubsBatch(clubsToUpdate, stats);
            }

            completionTracker.trackIncrement();
        });

        persistClubsBatch(clubsToInsert, stats);
        persistClubsBatch(clubsToUpdate, stats);

        stats.persistenceMs = System.currentTimeMillis() - persistenceStart;
        return stats;
    }

    private Map<String, List<String>> extractClubNamesFromTeamNames(List<FedespMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfoList) {
        Pattern clubNameWithTeamNamePattern = Pattern.compile("(['\"]{1,2})(.)(['\"]{1,2})");
        List<ClubNameAndYearInfo> filteredTeamNames = matchResultsDetailCsvFileRowInfoList.stream()
                .filter(rowInfo ->
                        !clubNameWithTeamNamePattern.matcher(rowInfoExtractor.extractTeamNameFromRowInfo(rowInfo))
                                .find())
                .map(matchResultsDetailCsvFileRowInfo ->
                        new ClubNameAndYearInfo(
                                rowInfoExtractor.extractTeamNameFromRowInfo(matchResultsDetailCsvFileRowInfo),
                                matchResultsDetailCsvFileRowInfo.fileInfo().season()))
                .toList();

        return ClubNameGrouppingService.groupByCommonRoot(filteredTeamNames);
    }

    private void createOrUpdateClub(
            String cleanClubName,
            List<String> yearRanges,
            Map<String, Club> existingClubsByName,
            List<Club> clubsToInsert,
            List<Club> clubsToUpdate,
            ImportStats stats) {

        Club existingClub = existingClubsByName.get(cleanClubName);
        if (existingClub != null) {
            boolean changed = !new HashSet<>(existingClub.getYearRanges()).containsAll(yearRanges);
            if (changed) {
                existingClub.setYearRanges(yearRanges);
                clubsToUpdate.add(existingClub);
                stats.updated++;
            } else {
                stats.unchanged++;
            }
        } else {
            Club clubToCreate = Club.createNew(cleanClubName);
            yearRanges.forEach(clubToCreate::addYearRange);
            clubsToInsert.add(clubToCreate);
            existingClubsByName.put(cleanClubName, clubToCreate);
            stats.inserted++;
        }
    }

    private void persistClubsBatch(List<Club> clubsBatch, ImportStats stats) {
        if (clubsBatch.isEmpty()) {
            return;
        }

        clubsBatch.forEach(clubRepository::save);
        stats.dbWrites += clubsBatch.size();
        clubsBatch.clear();
    }

    private static final class ImportStats {
        private int groupedClubs;
        private long groupingMs;
        private long persistenceMs;
        private int dbReads;
        private int dbWrites;
        private int inserted;
        private int updated;
        private int unchanged;
    }
}
