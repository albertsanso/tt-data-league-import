package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.club.service;

import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.service.PracticionerNameSimilarityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.List;

@Component
public class BcnesaPracticionerInitialImportService extends
        LineByLineInitialImportService<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> {

    private final PracticionerRepository practicionerRepository;

    private final BcnesaCsvFileRowInfoExtractor fileInfoExtractor;

    @Autowired
    public BcnesaPracticionerInitialImportService(MatchResultDetailsByLineIterator<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> matchResultDetailsByLineIterator, PracticionerRepository practicionerRepository, BcnesaCsvFileRowInfoExtractor fileInfoExtractor) {
        super(matchResultDetailsByLineIterator);
        this.practicionerRepository = practicionerRepository;
        this.fileInfoExtractor = fileInfoExtractor;
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

    private void savePracticionersInfo(List<BcnesaMatchResultsDetailCsvFileRowInfo> fedespMatchResultsDetailCsvFileRowInfos) {
        extractPracticionersNames(fedespMatchResultsDetailCsvFileRowInfos).forEach(practicionerName -> {
            Practicioner practicionerToCreate = Practicioner.createNew(practicionerName, practicionerName, practicionerName, new Date());
            if (practicionerRepository.findByFullName(practicionerName).isEmpty()) {
                practicionerRepository.save(practicionerToCreate);
            }
        });
    }

    private List<String> extractPracticionersNames(List<BcnesaMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfos) {
        return PracticionerNameSimilarityService.reduceToSimilarClustersOfNames(matchResultsDetailCsvFileRowInfos.stream()
                .map(rowInfo -> {
                    BcnesaMatchResultsDetailRowInfo rowInfoDetails = fileInfoExtractor.extractMatchDetailsRowInfo(rowInfo);
                    String abcPracticionerName = rowInfoDetails.acbPlayer().playerName();
                    String xyzPracticionerName = rowInfoDetails.xyzPlayer().playerName();

                    return List.of(abcPracticionerName, xyzPracticionerName);
                })
                .flatMap(List::stream)
                .distinct()
                .toList());
    }
}
