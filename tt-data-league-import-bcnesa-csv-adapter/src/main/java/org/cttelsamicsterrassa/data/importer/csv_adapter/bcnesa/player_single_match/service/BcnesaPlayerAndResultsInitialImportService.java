package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.delegate.BcnesaRowProcessingTransactionalDelegate;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.CompletionTracker;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the BCNESA player-and-results import.
 *
 * Responsibilities:
 *  - Discover and load CSV row infos via the shared iterator.
 *  - Preload club and practicioner lookup maps once per run.
 *  - Drive the per-row loop, delegating each row to BcnesaRowProcessingTransactionalDelegate
 *    which executes inside a REQUIRES_NEW transaction.
 *  - Collect and print ImportMetrics at the end of each run.
 *
 * Row-processing logic, entity inference, caching, and persistence are in the delegate.
 */
@Component
public class BcnesaPlayerAndResultsInitialImportService extends LineByLineInitialImportService<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> {

    private final ClubRepository clubRepository;
    private final PracticionerRepository practicionerRepository;
    private final BcnesaRowProcessingTransactionalDelegate delegate;

    @Autowired
    public BcnesaPlayerAndResultsInitialImportService(
            MatchResultDetailsByLineIterator<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> matchResultDetailsByLineIterator,
            ClubRepository clubRepository,
            PracticionerRepository practicionerRepository,
            BcnesaRowProcessingTransactionalDelegate delegate) {
        super(matchResultDetailsByLineIterator);
        this.clubRepository = clubRepository;
        this.practicionerRepository = practicionerRepository;
        this.delegate = delegate;
    }

    public void processForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
        importMatchResultsDetailsInfo();
    }

    public void processForAllSeasons(String baseSeasonsFolder) throws IOException {
        resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
        importMatchResultsDetailsInfo();
    }

    public void importMatchResultsDetailsInfo() {
        List<BcnesaMatchResultsDetailCsvFileRowInfo> rowInfosList = fetchCsvRowInfos();
        processMatchResultsDetailsInfo(rowInfosList);
    }

    private void processMatchResultsDetailsInfo(List<BcnesaMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfoList) {
        ImportMetrics metrics = new ImportMetrics();
        long totalStartTimeMs = System.currentTimeMillis();

        long preloadStartTimeMs = System.currentTimeMillis();
        List<Club> allClubsList = clubRepository.findAll();
        List<Practicioner> allPracticionersList = practicionerRepository.findAll();
        Map<String, Club> clubsByNameMap = buildClubLookupMap(allClubsList);
        Map<String, Practicioner> practicionersByNameMap = buildPracticionerLookupMap(allPracticionersList);
        metrics.preloadMs = System.currentTimeMillis() - preloadStartTimeMs;

        ImportRunContext context = new ImportRunContext(clubsByNameMap, practicionersByNameMap);

        CompletionTracker completionTracker = CompletionTracker.buildTracker(
                matchResultsDetailCsvFileRowInfoList.size(), 1, "Player and Results import");

        long rowLoopStartTimeMs = System.currentTimeMillis();
        matchResultsDetailCsvFileRowInfoList
                .forEach(matchResultsDetailCsvFileRowInfo -> {
                    try {
                        delegate.processRow(matchResultsDetailCsvFileRowInfo, allClubsList, allPracticionersList, context, metrics);
                    } catch (Exception e) {
                        metrics.rowExceptions++;
                        metrics.transactionRollbacks++;
                        System.err.println("ERROR processing row: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        completionTracker.trackIncrement();
                    }
                });

        metrics.rowLoopMs = System.currentTimeMillis() - rowLoopStartTimeMs;
        metrics.totalMs = System.currentTimeMillis() - totalStartTimeMs;
        printImportMetrics(metrics, context);
    }

    private Map<String, Club> buildClubLookupMap(List<Club> allClubsList) {
        Map<String, Club> clubsByNameMap = new HashMap<>();
        for (Club club : allClubsList) {
            clubsByNameMap.putIfAbsent(normalize(club.getName()), club);
        }
        return clubsByNameMap;
    }

    private Map<String, Practicioner> buildPracticionerLookupMap(List<Practicioner> allPracticionersList) {
        Map<String, Practicioner> practicionersByNameMap = new HashMap<>();
        for (Practicioner practicioner : allPracticionersList) {
            practicionersByNameMap.putIfAbsent(normalizePersonName(practicioner.getFullName()), practicioner);
        }
        return practicionersByNameMap;
    }

    private void printImportMetrics(ImportMetrics metrics, ImportRunContext context) {
        System.out.println("BCNESA Phase2 metrics: rows total=" + metrics.rowsTotal
                + ", processed=" + metrics.rowsProcessed
                + ", skippedPlayerD=" + metrics.rowsSkippedPlayerD
                + ", skippedInferenceMiss=" + metrics.rowsSkippedInferenceMiss
                + ", rowExceptions=" + metrics.rowExceptions);

        System.out.println("BCNESA Phase2 lookup: club hit/miss=" + metrics.clubLookupHit + "/" + metrics.clubLookupMiss
                + ", practicioner hit/miss=" + metrics.practicionerLookupHit + "/" + metrics.practicionerLookupMiss);

        System.out.println("BCNESA Phase2 cache: clubMember hit/miss=" + metrics.clubMemberCacheHit + "/" + metrics.clubMemberCacheMiss
                + ", seasonPlayer hit/miss=" + metrics.seasonPlayerCacheHit + "/" + metrics.seasonPlayerCacheMiss
                + ", seasonPlayerResult hit/miss=" + metrics.seasonPlayerResultCacheHit + "/" + metrics.seasonPlayerResultCacheMiss
                + ", singleMatch hit/miss=" + metrics.playersSingleMatchCacheHit + "/" + metrics.playersSingleMatchCacheMiss);

        System.out.println("BCNESA Phase2 saves: clubMember=" + metrics.clubMemberSaved
                + ", seasonPlayer=" + metrics.seasonPlayerSaved
                + ", seasonPlayerResult=" + metrics.seasonPlayerResultSaved
                + ", playersSingleMatch=" + metrics.playersSingleMatchSaved
                + ", saveSkippedNoChange=" + metrics.saveSkippedNoChange
                + ", inferenceMisses=" + metrics.inferenceMisses);

        System.out.println("BCNESA Phase2 timingMs: preload=" + metrics.preloadMs
                + ", rowLoop=" + metrics.rowLoopMs
                + ", total=" + metrics.totalMs);

        System.out.println("BCNESA Phase2 cacheSize: clubMember=" + context.clubMemberCache.size()
                + ", seasonPlayer=" + context.seasonPlayerCache.size()
                + ", seasonPlayerResult=" + context.seasonPlayerResultCache.size());

        System.out.println("BCNESA Phase2 tx: rollbacks=" + metrics.transactionRollbacks
                + ", emFlushes=" + metrics.entityManagerFlushCount
                + ", skippedNull=" + metrics.rowsSkippedNullEntity);
    }

    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replace("fc", "");
    }

    private static String normalizePersonName(String fullName) {
        if (fullName == null) {
            return "";
        }
        return fullName.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
