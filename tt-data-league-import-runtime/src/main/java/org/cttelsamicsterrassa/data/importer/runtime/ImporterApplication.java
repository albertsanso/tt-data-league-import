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

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication(scanBasePackages = {
        "org.cttelsamicsterrassa"
})
@EnableJpaRepositories(basePackages = "org.cttelsamicsterrassa")
@EntityScan(basePackages = "org.cttelsamicsterrassa")
public class ImporterApplication implements CommandLineRunner {

    @Autowired
    private BcnesaClubInitialImportService bcnesaClubInitialImportService;

    @Autowired
    private FedespClubInitialImportService fedespClubInitialImportService;

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

    /**
     * Accepted arguments:
     *   --base-folder=<path>                  (required)
     *   --federation=<fedesp|bcnesa>          (required)
     *   --workflow=<clubs|practicioners|results> (required)
     *   --season=<YYYY-YYYY>                  (optional; runs all seasons when omitted)
     *
     * Examples:
     *   --federation=fedesp --workflow=clubs --base-folder=/data/fedesp
     *   --federation=bcnesa --workflow=results --base-folder=/data/bcnesa --season=2024-2025
     */
    @Override
    public void run(String... args) throws Exception {
        Map<String, String> params = parseArgs(args);

        String baseFolder = params.get("base-folder");
        String federation = params.get("federation");
        String workflow   = params.get("workflow");
        String season     = params.get("season");

        if (federation == null || workflow == null || baseFolder == null) {
            printUsage();
            return;
        }

        long tsBegin = System.currentTimeMillis();

        runFromParams(baseFolder, federation, workflow, season);

        System.out.println("Total time (ms): " + (System.currentTimeMillis() - tsBegin));
    }

    private void runFromParams(String baseFolder, String federation, String workflow, String season) throws Exception {
        dispatch(baseFolder, federation, workflow, season);
    }

    private void dispatch(String baseFolder, String federation, String workflow, String season) throws Exception {
        switch (federation.toLowerCase()) {
            case "fedesp" -> dispatchFedesp(workflow, baseFolder, season);
            case "bcnesa" -> dispatchBcnesa(workflow, baseFolder, season);
            default -> {
                System.err.println("Unknown federation: " + federation + ". Valid values: fedesp, bcnesa");
                printUsage();
            }
        }
    }

    private void dispatchFedesp(String workflow, String baseFolder, String season) throws Exception {
        switch (workflow.toLowerCase()) {
            case "clubs" -> {
                if (season != null) fedespClubInitialImportService.processClubNamesForSeason(baseFolder, season);
                else               fedespClubInitialImportService.processClubNamesForAllSeason(baseFolder);
            }
            case "practicioners" -> {
                if (season != null) fedespPracticionerInitialImportService.processPracticionersForSeason(baseFolder, season);
                else               fedespPracticionerInitialImportService.processParacticionersForAllSeasons(baseFolder);
            }
            case "results" -> {
                if (season != null) fedespPlayerAndResultsImportService.processForSeason(baseFolder, season);
                else               fedespPlayerAndResultsImportService.processForAllSeasons(baseFolder);
            }
            default -> {
                System.err.println("Unknown workflow: " + workflow + ". Valid values: clubs, practicioners, results");
                printUsage();
            }
        }
    }

    private void dispatchBcnesa(String workflow, String baseFolder, String season) throws Exception {
        switch (workflow.toLowerCase()) {
            case "clubs" -> {
                if (season != null) bcnesaClubInitialImportService.processClubNamesForSeason(baseFolder, season);
                else               bcnesaClubInitialImportService.processClubNamesForAllSeason(baseFolder);
            }
            case "practicioners" -> {
                if (season != null) bcnesaPracticionerInitialImportService.processPracticionersForSeason(baseFolder, season);
                else               bcnesaPracticionerInitialImportService.processParacticionersForAllSeasons(baseFolder);
            }
            case "results" -> {
                if (season != null) bcnesaPlayerAndResultsInitialImportService.processForSeason(baseFolder, season);
                else               bcnesaPlayerAndResultsInitialImportService.processForAllSeasons(baseFolder);
            }
            default -> {
                System.err.println("Unknown workflow: " + workflow + ". Valid values: clubs, practicioners, results");
                printUsage();
            }
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> params = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String withoutPrefix = arg.substring(2);
                int eq = withoutPrefix.indexOf('=');
                if (eq > 0) {
                    params.put(withoutPrefix.substring(0, eq), withoutPrefix.substring(eq + 1));
                }
            }
        }
        return params;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  --federation=<fedesp|bcnesa>           (required)");
        System.out.println("  --workflow=<clubs|practicioners|results> (required)");
        System.out.println("  --base-folder=<path>                   (required)");
        System.out.println("  --season=<YYYY-YYYY>                   (optional; runs all seasons when omitted)");
    }
}
