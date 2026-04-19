package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service;

import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.service.CompletionTracker;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.service.PracticionerNameSimilarityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FedespPracticionerInitialImportService extends
        LineByLineInitialImportService<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> {

    private static final int WRITE_BATCH_SIZE = 100;

    private final PracticionerRepository practicionerRepository;

    private final FedespCsvFileRowInfoExtractor rowInfoExtractor;

    @Autowired
    public FedespPracticionerInitialImportService(MatchResultDetailsByLineIterator<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> matchResultDetailsByLineIterator, PracticionerRepository practicionerRepository, FedespCsvFileRowInfoExtractor rowInfoExtractor) {
        super(matchResultDetailsByLineIterator);
        this.practicionerRepository = practicionerRepository;
        this.rowInfoExtractor = rowInfoExtractor;
    }

    @Transactional
    public void processParacticionersForAllSeasons(String baseSeasonsFolder) throws IOException {
        long fileDiscoveryStart = System.currentTimeMillis();
        resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
        long fileDiscoveryMs = System.currentTimeMillis() - fileDiscoveryStart;
        importPracticioners(fileDiscoveryMs, "all seasons");
    }

    @Transactional
    public void processPracticionersForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        long fileDiscoveryStart = System.currentTimeMillis();
        resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
        long fileDiscoveryMs = System.currentTimeMillis() - fileDiscoveryStart;
        importPracticioners(fileDiscoveryMs, "season " + seasonRange);
    }

    private void importPracticioners(long fileDiscoveryMs, String scopeLabel) {
        long fetchStart = System.currentTimeMillis();
        List<FedespMatchResultsDetailCsvFileRowInfo> rows = fetchCsvRowInfos();
        long fetchMs = System.currentTimeMillis() - fetchStart;

        ImportStats stats = savePracticionersInfo(rows);

        System.out.printf("[FEDESP][Practicioners] scope=%s fileDiscoveryMs=%d rowFetchMs=%d clusteringMs=%d persistenceMs=%d totalRows=%d clusteredNames=%d dbReads=%d dbWrites=%d inserted=%d skippedExisting=%d%n",
                scopeLabel,
                fileDiscoveryMs,
                fetchMs,
                stats.clusteringMs,
                stats.persistenceMs,
                rows.size(),
                stats.clusteredNames,
                stats.dbReads,
                stats.dbWrites,
                stats.inserted,
                stats.skippedExisting
        );
    }

    private ImportStats savePracticionersInfo(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {
        long clusteringStart = System.currentTimeMillis();
        List<String> practicionersNamesList = extractPracticionersNames(fedespMatchResultsDetailCsvFileRowInfos);
        long clusteringMs = System.currentTimeMillis() - clusteringStart;

        Set<String> existingNames = new HashSet<>();
        practicionerRepository.findAll().forEach(practicioner -> existingNames.add(practicioner.getFullName()));

        ImportStats stats = new ImportStats();
        stats.dbReads = 1;
        stats.clusteredNames = practicionersNamesList.size();
        stats.clusteringMs = clusteringMs;

        CompletionTracker completionTracker = CompletionTracker.buildTracker(practicionersNamesList.size(), 10, "Practicioner import");
        long persistenceStart = System.currentTimeMillis();
        List<Practicioner> practicionersToInsertBatch = new ArrayList<>(WRITE_BATCH_SIZE);

        practicionersNamesList.forEach(practicionerName -> {
            if (existingNames.contains(practicionerName)) {
                stats.skippedExisting++;
            } else {
                Practicioner practicionerToCreate = Practicioner.createNew(practicionerName, practicionerName, practicionerName, new Date());
                practicionersToInsertBatch.add(practicionerToCreate);
                existingNames.add(practicionerName);
                stats.inserted++;

                if (practicionersToInsertBatch.size() >= WRITE_BATCH_SIZE) {
                    persistPracticionersBatch(practicionersToInsertBatch, stats);
                }
            }

            completionTracker.trackIncrement();
        });

        persistPracticionersBatch(practicionersToInsertBatch, stats);

        stats.persistenceMs = System.currentTimeMillis() - persistenceStart;
        return stats;
    }

    private void persistPracticionersBatch(List<Practicioner> practicionersToInsertBatch, ImportStats stats) {
        if (practicionersToInsertBatch.isEmpty()) {
            return;
        }

        practicionersToInsertBatch.forEach(practicionerRepository::save);
        stats.dbWrites += practicionersToInsertBatch.size();
        practicionersToInsertBatch.clear();
    }

    private List<String> extractPracticionersNames(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {

        return PracticionerNameSimilarityService.reduceToSimilarClustersOfNames(fedespMatchResultsDetailCsvFileRowInfos.stream()
                .map(rowInfo -> {
                    FedespMatchResultsDetailRowInfo fedespMatchResultsDetailRowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(rowInfo);
                    String localPracticionerName = fedespMatchResultsDetailRowInfo.localPlayer().playerName();
                    String visitorPracticionerName = fedespMatchResultsDetailRowInfo.visitorPlayer().playerName();

                    return List.of(localPracticionerName, visitorPracticionerName);
                })
                .flatMap(List::stream)
                .distinct().toList());
    }


    private static final class ImportStats {
        private int clusteredNames;
        private long clusteringMs;
        private long persistenceMs;
        private int dbReads;
        private int dbWrites;
        private int inserted;
        private int skippedExisting;
    }
}
