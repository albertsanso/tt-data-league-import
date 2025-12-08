package org.cttelsamicsterrassa.data.importer.runtime;

import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.club.service.BcnesaClubInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.BcnesaPlayerAndResultsInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.club.service.FedespClubInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.player_single_match.service.FedespPlayerAndResultsImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.io.IOException;

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

    public static void main(String[] args) {
        SpringApplication.run(ImporterApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {

        //processBcnesa("2023-2024");
        processFedesp("2024-2025");
    }

    private void processBcnesa(String season) throws IOException {
        String baseFolder = "C:\\git\\bcnesa-data-csv\\resources\\matches-results-details\\csv";
        processBcnesaClubAndMembersInfoForFolderAndBySeason(baseFolder, season);
        processBcnesaMatchesAndResultsInfoForFolderAndBySeason(baseFolder, season);
    }

    private void processFedesp(String season) throws IOException {
        String baseFolder = "C:\\git\\fedesp-data-csv\\resources\\match-results-details";
        processFedespClubAndMembersInfoForFolderAndBySeason(baseFolder, season);
        processFedespMatchesAndResultsInfoForFolderAndBySeason(baseFolder, season);
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

}
