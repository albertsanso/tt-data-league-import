package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service;

import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.model.PracticionerNameAndYearInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.CompletionTracker;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.service.PracticionerNameGrouppingService;
import org.cttelsamicsterrassa.data.importer.shared.service.PracticionerNameSimilarityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Component
public class FedespPracticionerInitialImportService extends
        LineByLineInitialImportService<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> {

    private final PracticionerRepository practicionerRepository;

    private final FedespCsvFileRowInfoExtractor rowInfoExtractor;

    @Autowired
    public FedespPracticionerInitialImportService(MatchResultDetailsByLineIterator<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> matchResultDetailsByLineIterator, PracticionerRepository practicionerRepository, FedespCsvFileRowInfoExtractor rowInfoExtractor) {
        super(matchResultDetailsByLineIterator);
        this.practicionerRepository = practicionerRepository;
        this.rowInfoExtractor = rowInfoExtractor;
    }

    public void processParacticionersForAllSeasons(String baseSeasonsFolder) throws IOException {
        resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
        importPracticioners();
    }

    public void processPracticionersForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
        importPracticioners();
    }

    private void importPracticioners() {
        savePracticionersInfo(fetchCsvRowInfos());
    }

    private void savePracticionersInfo(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {
        List<String> practicionersNamesList = extractPracticionersNames(fedespMatchResultsDetailCsvFileRowInfos);

        CompletionTracker completionTracker = CompletionTracker.buildTracker(practicionersNamesList.size(), 10, "Practicioner import");

        practicionersNamesList.forEach(practicionerName -> {
            Practicioner practicionerToCreate = Practicioner.createNew(practicionerName, practicionerName, practicionerName, new Date());
            if (practicionerRepository.findByFullName(practicionerName).isEmpty()) {
                practicionerRepository.save(practicionerToCreate);
            }

            completionTracker.trackIncrement();
        });
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

    private Map<String, List<String>> extractPracticionersNamesAndYears(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {

        List<PracticionerNameAndYearInfo> list = fedespMatchResultsDetailCsvFileRowInfos.stream()
                .map(rowInfo -> {
                    FedespMatchResultsDetailRowInfo fedespMatchResultsDetailRowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(rowInfo);
                    String localPracticionerName = fedespMatchResultsDetailRowInfo.localPlayer().playerName();
                    PracticionerNameAndYearInfo localPracticionerNameAndYearInfo = new PracticionerNameAndYearInfo(
                            localPracticionerName,
                            rowInfo.fileInfo().season()
                    );
                    String visitorPracticionerName = fedespMatchResultsDetailRowInfo.visitorPlayer().playerName();
                    PracticionerNameAndYearInfo visitorPracticionerNameAndYearInfo = new PracticionerNameAndYearInfo(
                            visitorPracticionerName,
                            rowInfo.fileInfo().season()
                    );

                    return List.of(localPracticionerNameAndYearInfo, visitorPracticionerNameAndYearInfo);
                })
                .flatMap(List::stream)
                .filter(practicionerNameAndYearInfo -> practicionerNameAndYearInfo.practicionerName().toLowerCase().contains("campos"))
                .toList();

        return PracticionerNameGrouppingService.groupByCommonRoot(list);
    }
}
