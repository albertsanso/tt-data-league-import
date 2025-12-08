package org.cttelsamicsterrassa.data.importer.runtime;

import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.club.service.BcnesaClubInitialImportService;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.BcnesaPlayerAndResultsInitialImportService;
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
    BcnesaClubInitialImportService clubInitialImportService;

    @Autowired
    private BcnesaPlayerAndResultsInitialImportService bcnesaPlayerAndResultsInitialImportService;


    public static void main(String[] args) {
        SpringApplication.run(ImporterApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String baseFolder = "C:\\git\\bcnesa-data-csv\\resources\\matches-results-details\\csv";
        processClubAndMembersInfoForFolderAndBySeason(baseFolder, "2023-2024");
        processMatchesAndResultsInfoForFolderAndBySeason(baseFolder, "2023-2024");
    }

    private void processClubAndMembersInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        clubInitialImportService.processClubNamesForSeason(baseFolder, season);
    }

    private void processMatchesAndResultsInfoForFolderAndBySeason(String baseFolder, String season) throws IOException {
        bcnesaPlayerAndResultsInitialImportService.processForSeason(baseFolder, season);
    }

}
