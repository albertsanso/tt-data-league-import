package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service;

import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.model.PracticionerNameAndYearInfo;
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
        extractPracticionersNames(fedespMatchResultsDetailCsvFileRowInfos).forEach(practicionerName -> {
            Practicioner practicionerToCreate = Practicioner.createNew(practicionerName, practicionerName, practicionerName, new Date());
            if (practicionerRepository.findByFullName(practicionerName).isEmpty()) {
                practicionerRepository.save(practicionerToCreate);
            }
        });
    }

    private List<String> extractPracticionersNames(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {

        return PracticionerNameSimilarityService.reduceToSimilarClustersOfNames(fedespMatchResultsDetailCsvFileRowInfos.stream()
                .map(rowInfo -> {
                    FedespMatchResultsDetailRowInfo fedespMatchResultsDetailRowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(rowInfo);
                    String abcPracticionerName = fedespMatchResultsDetailRowInfo.acbPlayer().playerName();
                    String xyzPracticionerName = fedespMatchResultsDetailRowInfo.xyzPlayer().playerName();

                    return List.of(abcPracticionerName, xyzPracticionerName);
                })
                .flatMap(List::stream)
                .distinct().toList());
    }

    private Map<String, List<String>> extractPracticionersNamesAndYears(List<FedespMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {

        List<PracticionerNameAndYearInfo> list = fedespMatchResultsDetailCsvFileRowInfos.stream()
                .map(rowInfo -> {
                    FedespMatchResultsDetailRowInfo fedespMatchResultsDetailRowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(rowInfo);
                    String abcPracticionerName = fedespMatchResultsDetailRowInfo.acbPlayer().playerName();
                    PracticionerNameAndYearInfo abcPracticionerNameAndYearInfo = new PracticionerNameAndYearInfo(
                            abcPracticionerName,
                            rowInfo.fileInfo().season()
                    );
                    String xyzPracticionerName = fedespMatchResultsDetailRowInfo.xyzPlayer().playerName();
                    PracticionerNameAndYearInfo xyzPracticionerNameAndYearInfo = new PracticionerNameAndYearInfo(
                            xyzPracticionerName,
                            rowInfo.fileInfo().season()
                    );

                    return List.of(abcPracticionerNameAndYearInfo, xyzPracticionerNameAndYearInfo);
                })
                .flatMap(List::stream)
                .filter(practicionerNameAndYearInfo -> practicionerNameAndYearInfo.practicionerName().toLowerCase().contains("campos"))
                .toList();

        return PracticionerNameGrouppingService.groupByCommonRoot(list);
    }
}
