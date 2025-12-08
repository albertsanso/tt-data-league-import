package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.player_single_match.service;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.CompetitionInfo;
import org.cttelsamicsterrassa.data.core.domain.model.PlayersSingleMatch;
import org.cttelsamicsterrassa.data.core.domain.model.SeasonPlayerResult;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubMemberRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PlayersSingleMatchRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerResultRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.model.MatchInfoKey;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FedespPlayerAndResultsImportService extends LineByLineInitialImportService<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> {

    private final ClubRepository clubRepository;

    private final FedespCsvFileRowInfoExtractor rowInfoExtractor;

    private final LevenshteinDistance levenshtein = new LevenshteinDistance();

    private final ClubMemberRepository clubMemberRepository;

    private final PracticionerRepository practicionerRepository;

    private final SeasonPlayerRepository seasonPlayerRepository;

    private final SeasonPlayerResultRepository seasonPlayerResultRepository;

    private final PlayersSingleMatchRepository playersSingleMatchRepository;

    @Autowired
    public FedespPlayerAndResultsImportService(MatchResultDetailsByLineIterator<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> matchResultDetailsByLineIterator, ClubRepository clubRepository, FedespCsvFileRowInfoExtractor rowInfoExtractor, ClubMemberRepository clubMemberRepository, PracticionerRepository practicionerRepository, SeasonPlayerRepository seasonPlayerRepository, SeasonPlayerResultRepository seasonPlayerResultRepository, PlayersSingleMatchRepository playersSingleMatchRepository) {
        super(matchResultDetailsByLineIterator);
        this.clubRepository = clubRepository;
        this.rowInfoExtractor = rowInfoExtractor;
        this.clubMemberRepository = clubMemberRepository;
        this.practicionerRepository = practicionerRepository;
        this.seasonPlayerRepository = seasonPlayerRepository;
        this.seasonPlayerResultRepository = seasonPlayerResultRepository;
        this.playersSingleMatchRepository = playersSingleMatchRepository;
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
        List<FedespMatchResultsDetailCsvFileRowInfo> rowInfowsList = fetchCsvRowInfos();
        processMatchResultsDetailsInfo(rowInfowsList);
    }

    private void processMatchResultsDetailsInfo(List<FedespMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfoList) {

        List<Club> allClubsList = clubRepository.findAll();
        Map<MatchInfoKey, List<SeasonPlayerResult>> mapOfMatchesList = new HashMap<>();

        matchResultsDetailCsvFileRowInfoList.forEach(matchResultsDetailCsvFileRowInfo -> {

            String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();
            String gender = matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender();
            String competitionType = matchResultsDetailCsvFileRowInfo.fileInfo().competitionType();
            String competitionCategory = matchResultsDetailCsvFileRowInfo.fileInfo().competitionCategory();
            String competitionScope = matchResultsDetailCsvFileRowInfo.fileInfo().competitionScope();
            String competitionScopeTag = matchResultsDetailCsvFileRowInfo.fileInfo().competitionScopeTag();
            String competitionGroup = matchResultsDetailCsvFileRowInfo.fileInfo().competitionGroup();

            FedespMatchResultsDetailRowInfo rowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(matchResultsDetailCsvFileRowInfo);

            String teamNameABC = rowInfo.acbPlayer().teamName();
            String teamNameXYZ = rowInfo.xyzPlayer().teamName();
            int matchDayNumber = rowInfo.matchDayNumber();

            String fullTeamABCId = season + competitionCategory + matchDayNumber + competitionGroup + teamNameABC; // NOT REALLY USED
            String fullTeamXYZId = season + competitionCategory + matchDayNumber + competitionGroup + teamNameXYZ; // NOT REALLY USED

            String gameId = season+"-"+competitionCategory+"-"+matchDayNumber+"-"+competitionGroup+"-"+rowInfo.acbPlayer().playerLicense()+"-"+rowInfo.xyzPlayer().playerLicense();
            MatchInfoKey matchInfoKey = new MatchInfoKey(
                    season,
                    competitionType,
                    competitionCategory,
                    competitionScope,
                    competitionScopeTag,
                    competitionGroup,
                    matchDayNumber,
                    rowInfo.acbPlayer().teamName(),
                    rowInfo.xyzPlayer().teamName());

            //checkScores(rowInfo.acbPlayer(), rowInfo.xyzPlayer(), matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());

            SeasonPlayerResult seasonPlayerResultAbc = createSeasonPlayerAndResults(rowInfo.acbPlayer(), allClubsList, season, gameId, fullTeamABCId, matchInfoKey, mapOfMatchesList, matchResultsDetailCsvFileRowInfo);
            SeasonPlayerResult seasonPlayerResultXyz = createSeasonPlayerAndResults(rowInfo.xyzPlayer(), allClubsList, season, gameId, fullTeamXYZId, matchInfoKey, mapOfMatchesList, matchResultsDetailCsvFileRowInfo);

            String uniqueRowId = "%s-%s-%s-%s-%s-%s-%s-%s-%s-%s".formatted(
                    competitionCategory.strip(),
                    season.strip(),
                    competitionGroup,
                    String.valueOf(matchDayNumber),
                    teamNameABC.strip(),
                    seasonPlayerResultAbc.getSeasonPlayer().getLicense().id().strip(),
                    seasonPlayerResultAbc.getPlayerLetter().strip(),
                    teamNameXYZ.strip(),
                    seasonPlayerResultXyz.getSeasonPlayer().getLicense().id().strip(),
                    seasonPlayerResultXyz.getPlayerLetter().strip()
            );

            Optional<PlayersSingleMatch> optPlayersSingleMatch = playersSingleMatchRepository.findBySeasonPlayerResultAbcIdAndSeasonPlayerResultXyzIdAndUniqueId(seasonPlayerResultAbc.getId(), seasonPlayerResultXyz.getId(), uniqueRowId);
            if (optPlayersSingleMatch.isEmpty()) {
                CompetitionInfo competitionInfo = new CompetitionInfo(
                        competitionType,
                        competitionCategory,
                        competitionScope,
                        competitionScopeTag,
                        competitionGroup,
                        gender);

                PlayersSingleMatch playersSingleMatch = PlayersSingleMatch.createNew(
                        seasonPlayerResultAbc,
                        seasonPlayerResultXyz,
                        season,
                        competitionInfo,
                        matchDayNumber,
                        uniqueRowId
                );
                playersSingleMatchRepository.save(playersSingleMatch);
            }
        });
    }
}
