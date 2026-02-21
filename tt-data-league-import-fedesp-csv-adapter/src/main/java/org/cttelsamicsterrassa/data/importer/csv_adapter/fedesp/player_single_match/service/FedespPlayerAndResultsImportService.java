package org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.player_single_match.service;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.ClubMember;
import org.cttelsamicsterrassa.data.core.domain.model.CompetitionInfo;
import org.cttelsamicsterrassa.data.core.domain.model.License;
import org.cttelsamicsterrassa.data.core.domain.model.MatchInfo;
import org.cttelsamicsterrassa.data.core.domain.model.PlayersSingleMatch;
import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.model.SeasonPlayer;
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
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.model.fs.FedespPlayerCsvInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.csv_adapter.fedesp.shared.service.FedespMatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.model.MatchInfoKey;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.name.NameSimilarity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
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
    public FedespPlayerAndResultsImportService(FedespMatchResultDetailsByLineIterator fedespMatchResultDetailsByLineIterator, ClubRepository clubRepository, FedespCsvFileRowInfoExtractor rowInfoExtractor, ClubMemberRepository clubMemberRepository, PracticionerRepository practicionerRepository, SeasonPlayerRepository seasonPlayerRepository, SeasonPlayerResultRepository seasonPlayerResultRepository, PlayersSingleMatchRepository playersSingleMatchRepository) {
        super(fedespMatchResultDetailsByLineIterator);
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
        List<Practicioner> allPracticionersList = practicionerRepository.findAll();

        Map<MatchInfoKey, List<SeasonPlayerResult>> mapOfMatchesList = new HashMap<>();

        matchResultsDetailCsvFileRowInfoList
            .parallelStream()
            .forEach(matchResultsDetailCsvFileRowInfo -> {

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

            //String gameId = season+"-"+competitionCategory+"-"+matchDayNumber+"-"+competitionGroup+"-"+rowInfo.acbPlayer().playerLicense()+"-"+rowInfo.xyzPlayer().playerLicense();
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

            SeasonPlayerResult seasonPlayerResultAbc = createSeasonPlayerAndResults(rowInfo.acbPlayer(), allClubsList, allPracticionersList, season, "", fullTeamABCId, matchInfoKey, mapOfMatchesList, matchResultsDetailCsvFileRowInfo);
            SeasonPlayerResult seasonPlayerResultXyz = createSeasonPlayerAndResults(rowInfo.xyzPlayer(), allClubsList, allPracticionersList, season, "", fullTeamXYZId, matchInfoKey, mapOfMatchesList, matchResultsDetailCsvFileRowInfo);


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

    private SeasonPlayerResult createSeasonPlayerAndResults(FedespPlayerCsvInfo playerInfo, List<Club> allClubsList, List<Practicioner> allPracticionerList, String seasonRange, String uniqueRowId, String fullTeamId, MatchInfoKey matchInfoKey, Map<MatchInfoKey, List<SeasonPlayerResult>> mapOfMatchesList, FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo) {
        Optional<Club> optInferredClub = inferClubByTeamName(playerInfo.teamName(), allClubsList);
        Optional<Practicioner> optInferredPracticioner = inferPracticionerByName(playerInfo.playerName(), allPracticionerList);

        SeasonPlayerResult seasonPlayerResult = null;
        if (optInferredClub.isPresent() && optInferredPracticioner.isPresent()) {
            seasonPlayerResult = createSeasonPlayerAndResultsForClub(
                    optInferredClub.get(),
                    optInferredPracticioner.get(),
                    playerInfo,
                    seasonRange,
                    uniqueRowId,
                    fullTeamId,
                    matchInfoKey,
                    mapOfMatchesList,
                    matchResultsDetailCsvFileRowInfo);
        } else {

            System.out.println("UNABLE TO INFER CLUB BY TEAM NAME: "+playerInfo.teamName());
            System.out.println("  > "+matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());
        }
        return seasonPlayerResult;
    }

    private Optional<Club> inferClubByTeamName(String teamName, List<Club> allClubsList) {
        String normalizedInput = normalize(teamName);
        return allClubsList.stream()
                .min(Comparator.comparingInt(club -> levenshtein.apply(normalizedInput, normalize(club.getName()))));
    }

    private static Optional<Practicioner> inferPracticionerByName(String practicionerName, List<Practicioner> allPracticionersList) {
        //String normalizedInput = normalize(practicionerName);
        return allPracticionersList.stream()
                .max(Comparator.comparingDouble(practicioner -> NameSimilarity.similarity(practicionerName, practicioner.getFullName())));
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsForClub(Club inferredClub, Practicioner inferredPracticioner, FedespPlayerCsvInfo playerInfo, String seasonRange, String uniqueRowId, String fullTeamId, MatchInfoKey matchInfoKey, Map<MatchInfoKey, List<SeasonPlayerResult>> mapOfMatchesList, FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo) {
        SeasonPlayerResult seasonPlayerResult = null;
        Optional<Club> optClub = clubRepository.findByName(inferredClub.getName());
        Optional<Practicioner> optPracticioner = practicionerRepository.findByFullName(inferredPracticioner.getFullName());
        if (optClub.isPresent() && optPracticioner.isPresent()) {
            Club club = optClub.get();
            Practicioner practicioner = optPracticioner.get();
            ClubMember clubMember = getOrCreateClubMember(club, practicioner, seasonRange);
            SeasonPlayer seasonPlayer = getOrCreateSeasonPlayer(practicioner, clubMember, seasonRange, new License("ESP", playerInfo.playerLicense()));

            CompetitionInfo competitionInfo = new CompetitionInfo(
                    matchInfoKey.competitionType(),
                    matchInfoKey.competitionCategory(),
                    matchInfoKey.competitionScope(),
                    matchInfoKey.competitionScopeTag(),
                    matchInfoKey.competitionGroup(),
                    matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender()
            );

            seasonPlayerResult = getOrCreateSeasonPlayerResult(seasonRange, competitionInfo, matchInfoKey, playerInfo, seasonPlayer, uniqueRowId);
            //addSeasonPlayerResultToMap(seasonPlayerResult, matchInfoKey.teamNameAbc(), matchInfoKey.teamNameXyz(), mapOfMatchesList);

        } else {
            System.out.println("UNABLE TO FIND CLUB BY TEAM NAME: "+inferredClub.getName());
            System.out.println("  > "+matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());

        }
        return seasonPlayerResult;
    }

    private SeasonPlayerResult getOrCreateSeasonPlayerResult(String seasonRange, CompetitionInfo competitionInfo, MatchInfoKey matchInfoKey, FedespPlayerCsvInfo playerInfo, SeasonPlayer seasonPlayer, String uniqueRowId) {
        SeasonPlayerResult seasonPlayerResult = seasonPlayerResultRepository
                .findFor(
                        seasonRange,
                        competitionInfo.competitionType(),
                        competitionInfo.competitionCategory(),
                        competitionInfo.competitionScope(),
                        competitionInfo.competitionScopeTag(),
                        competitionInfo.competitionGroup(),
                        matchInfoKey.matchDayNumber(),
                        playerInfo.playerLetter(),
                        uniqueRowId,
                        seasonPlayer.getClubMember().getClub().getId()
                )
                .orElseGet(() -> SeasonPlayerResult.createNew(
                        seasonRange,
                        competitionInfo,
                        seasonPlayer,
                        new MatchInfo(
                                matchInfoKey.matchDayNumber(),
                                "",
                                playerInfo.playerLetter(),
                                new int[] {},
                                playerInfo.playerScore(),
                                uniqueRowId
                        )
                ));
        seasonPlayerResultRepository.save(seasonPlayerResult);
        return seasonPlayerResult;
    }

    private void addSeasonPlayerResultToMap(SeasonPlayerResult seasonPlayerResult, String abcTeamName, String xyzTeamName,Map<MatchInfoKey, List<SeasonPlayerResult>> mapOfMatchesList) {
        MatchInfoKey matchInfoKey = new MatchInfoKey(
                seasonPlayerResult.getSeason(),
                seasonPlayerResult.getCompetitionType(),
                seasonPlayerResult.getCompetitionCategory(),
                seasonPlayerResult.getCompetitionScope(),
                seasonPlayerResult.getCompetitionScopeTag(),
                seasonPlayerResult.getCompetitionGroup(),
                seasonPlayerResult.getMatchDayNumber(),
                abcTeamName,
                xyzTeamName
        );

        List<SeasonPlayerResult> seasonPlayerResultsList;
        if (!mapOfMatchesList.keySet().contains(matchInfoKey)) {
            seasonPlayerResultsList = new ArrayList<SeasonPlayerResult>();
            mapOfMatchesList.put(matchInfoKey, seasonPlayerResultsList);
        } else {
            seasonPlayerResultsList = mapOfMatchesList.get(matchInfoKey);
        }
        seasonPlayerResultsList.add(seasonPlayerResult);
    }

    private Practicioner getOrCreatePracticionerFromPlayerInfo(FedespPlayerCsvInfo playerInfo) {
        String[] firstAndSecondNames = splitIntoFirstNameAndSecondName(playerInfo.playerName());
        String firstName = firstAndSecondNames[0];
        String secondName = firstAndSecondNames[1];

        Optional<Practicioner> optPracticionerFromPlayer = practicionerRepository.findByFullName(playerInfo.playerName());
        Practicioner practicionerFromPlayer = optPracticionerFromPlayer.orElseGet(() -> Practicioner.createNew(
                firstName,
                secondName,
                playerInfo.playerName(),
                null));
        practicionerRepository.save(practicionerFromPlayer);
        return practicionerFromPlayer;
    }

    private ClubMember getOrCreateClubMember(Club club, Practicioner practicioner, String seasonRange) {
        Optional<ClubMember> optClubMember = clubMemberRepository.findByPracticionerIdAndClubId(practicioner.getId(), club.getId());
        ClubMember clubMember = optClubMember.orElseGet(() -> ClubMember.createNew(
                club,
                practicioner
        ));
        clubMember.addYearRange(seasonRange);
        clubMemberRepository.save(clubMember);
        return clubMember;
    }

    private SeasonPlayer getOrCreateSeasonPlayer(Practicioner practicioner,  ClubMember clubMember, String seasonRange, License license) {
        SeasonPlayer seasonPlayer = seasonPlayerRepository
                .findByPracticionerIdClubIdSeason(practicioner.getId(), clubMember.getClub().getId(), seasonRange)
                //.findByPracticionerNameAndClubNameAndSeason(practicioner.getFullName(), clubMember.getClub().getName(), seasonRange)
                .orElseGet(() -> SeasonPlayer.createNew(
                        clubMember,
                        license,
                        seasonRange
                ));
        seasonPlayerRepository.save(seasonPlayer);
        return seasonPlayer;
    }

    private String[] splitIntoFirstNameAndSecondName(String input) {
        String[] words = input.split("\\s+");
        List<String> upperWords = new ArrayList<>();
        List<String> lowerWords = new ArrayList<>();

        for (String word : words) {
            if (word.equals(word.toUpperCase())) {
                // Entire word is uppercase
                upperWords.add(word);
            } else {
                // Mixed or lowercase word
                lowerWords.add(word);
            }
        }

        String secondName = String.join(" ", upperWords);
        String firstName = String.join(" ", lowerWords);
        return new String[] {firstName, secondName};
    }
    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "") // remove spaces/punctuation
                .replace("fc", "");          // remove 'fc' if needed
    }
}
