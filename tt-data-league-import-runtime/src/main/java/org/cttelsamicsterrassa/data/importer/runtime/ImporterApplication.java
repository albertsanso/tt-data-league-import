package org.cttelsamicsterrassa.data.importer.runtime;

import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.club.service.BcnesaClubInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.club.service.BcnesaPracticionerInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.BcnesaPlayerAndResultsInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service.FedespClubInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service.FedespPracticionerInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.player_single_match.service.FedespPlayerAndResultsImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.io.IOException;
import java.util.List;

@SpringBootApplication(scanBasePackages = {
        "org.cttelsamicsterrassa"
})
@EnableJpaRepositories(basePackages = "org.cttelsamicsterrassa")
@EntityScan(basePackages = "org.cttelsamicsterrassa")
public class ImporterApplication implements CommandLineRunner {

    @Autowired
    BcnesaClubInitialImportService bcnesaClubInitialImportService;

    @Autowired
    FedespClubInitialImportService fedespClubInitialImportService;

    @Autowired
    private BcnesaPlayerAndResultsInitialImportService bcnesaPlayerAndResultsInitialImportService;

    @Autowired
    private FedespPlayerAndResultsImportService fedespPlayerAndResultsImportService;

    @Autowired
    private FedespPracticionerInitialImportService fedespPracticionerInitialImportService;

    @Autowired
    private BcnesaPracticionerInitialImportService bcnesaPracticionerInitialImportService;

    public static void main(String[] args) {
        SpringApplication.run(ImporterApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        long tsBeguin = System.currentTimeMillis();

        String baseFolderFedesp = "C:\\git\\fedesp-data-csv\\resources\\match-results-details";
        String baseFolderbcnesa = "C:\\git\\bcnesa-data-csv\\resources\\matches-results-details\\csv";

        //fedespPracticionerInitialImportService.processParacticionersForAllSeasons(baseFolderFedesp);
        //fedespClubInitialImportService.processClubNamesForAllSeason(baseFolderFedesp);
        //fedespPlayerAndResultsImportService.processForAllSeasons(baseFolderFedesp);
        fedespPlayerAndResultsImportService.processForSeason(baseFolderFedesp, "2018-2019");

        //bcnesaPracticionerInitialImportService.processParacticionersForAllSeasons(baseFolderbcnesa);
        //bcnesaClubInitialImportService.processClubNamesForAllSeason(baseFolderbcnesa);
        //bcnesaPlayerAndResultsInitialImportService.processForSeason(baseFolderbcnesa, "2018-2019");

        long tsEnd = System.currentTimeMillis();
        System.out.println("Total time (ms): " + (tsEnd - tsBeguin));
    }

    private void processBcnesa(String[] seasonsList) throws IOException {
        for (String season : seasonsList) {
            System.out.println(season);
            processBcnesa(season);
        }
    }

    private void processFedesp(String[] seasonsList) throws IOException {
        for (String season : seasonsList) {
            System.out.println(season);
            processFedesp(season);
        }
    }
    private void processBcnesa(String season) throws IOException {
        String baseFolder = "C:\\git\\bcnesa-data-csv\\resources\\matches-results-details\\csv";
        processBcnesaClubAndMembersInfoForFolderAndBySeason(baseFolder, season);
        processBcnesaPracticionersInfoForFolderAndBySeason(baseFolder, season);
        processBcnesaMatchesAndResultsInfoForFolderAndBySeason(baseFolder, season);
    }

    private void processFedesp(String season) throws IOException {
        String baseFolder = "C:\\git\\fedesp-data-csv\\resources\\match-results-details";
        //processFedespClubAndMembersInfoForFolderAndBySeason(baseFolder, season);
        processFedespPracticionersInfoForFolderAndBySeason(baseFolder, season);
        //processFedespMatchesAndResultsInfoForFolderAndBySeason(baseFolder, season);
    }

    private void processFedespClubAndMembersInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        fedespClubInitialImportService.processClubNamesForSeason(baseFolder, season);
    }

    private void processFedespMatchesAndResultsInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        fedespPlayerAndResultsImportService.processForSeason(baseFolder, season);
    }

    private void processBcnesaClubAndMembersInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        bcnesaClubInitialImportService.processClubNamesForSeason(baseFolder, season);
    }

    private void processBcnesaMatchesAndResultsInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        bcnesaPlayerAndResultsInitialImportService.processForSeason(baseFolder, season);
    }

    private void processFedespPracticionersInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        fedespPracticionerInitialImportService.processPracticionersForSeason(baseFolder, season);
    }

    private void processBcnesaPracticionersInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        //bcnesaPracticionerInitialImportService.processPracticionersForSeason(baseFolder, season);
    }

}
