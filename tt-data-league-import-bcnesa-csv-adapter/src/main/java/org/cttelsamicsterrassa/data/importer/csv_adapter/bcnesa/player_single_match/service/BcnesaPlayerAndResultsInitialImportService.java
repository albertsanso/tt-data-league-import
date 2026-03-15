package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

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
import org.cttelsamicsterrassa.data.core.domain.model.TeamRole;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubMemberRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PlayersSingleMatchRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerResultRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.model.MatchInfoKey;
import org.cttelsamicsterrassa.data.importer.shared.service.CompletionTracker;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.cttelsamicsterrassa.data.importer.shared.service.name.NameSimilarity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class BcnesaPlayerAndResultsInitialImportService extends LineByLineInitialImportService<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> {

    private final ClubRepository clubRepository;

    private final BcnesaCsvFileRowInfoExtractor rowInfoExtractor;

    private final LevenshteinDistance levenshtein = new LevenshteinDistance();

    private final ClubMemberRepository clubMemberRepository;

    private final PracticionerRepository practicionerRepository;

    private final SeasonPlayerRepository seasonPlayerRepository;

    private final SeasonPlayerResultRepository seasonPlayerResultRepository;

    private final PlayersSingleMatchRepository playersSingleMatchRepository;

    @Autowired
    public BcnesaPlayerAndResultsInitialImportService(MatchResultDetailsByLineIterator<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> matchResultDetailsByLineIterator, ClubRepository clubRepository, BcnesaCsvFileRowInfoExtractor rowInfoExtractor, ClubMemberRepository clubMemberRepository, PracticionerRepository practicionerRepository, SeasonPlayerRepository seasonPlayerRepository, SeasonPlayerResultRepository seasonPlayerResultRepository, PlayersSingleMatchRepository playersSingleMatchRepository) {
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
        List<BcnesaMatchResultsDetailCsvFileRowInfo> rowInfowsList = fetchCsvRowInfos();
        processMatchResultsDetailsInfo(rowInfowsList);
    }

    private void processMatchResultsDetailsInfo(List<BcnesaMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfoList) {
        List<Club> allClubsList = clubRepository.findAll();
        List<Practicioner> allPracticionersList = practicionerRepository.findAll();

        CompletionTracker completionTracker = CompletionTracker.buildTracker(matchResultsDetailCsvFileRowInfoList.size(), 1, "Player and Results import");

        matchResultsDetailCsvFileRowInfoList
                .parallelStream()
                .forEach(matchResultsDetailCsvFileRowInfo -> {
                    processMatchResultsDetailsRowInfo(matchResultsDetailCsvFileRowInfo, allClubsList, allPracticionersList);
                    completionTracker.trackIncrement();
                });
    }

    private void processMatchResultsDetailsRowInfo(
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionersList) {

        BcnesaMatchResultsDetailRowInfo rowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(matchResultsDetailCsvFileRowInfo);
        MatchInfoKey matchInfoKey = createMatchInfoKey(matchResultsDetailCsvFileRowInfo, rowInfo);

        String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();

        if (!rowInfo.localPlayer().playerLetter().equals("D")) {
            SeasonPlayerResult seasonPlayerResultLocal = createSeasonPlayerAndResultsAsLocal(rowInfo.localPlayer(), allClubsList, allPracticionersList, season, matchInfoKey, matchResultsDetailCsvFileRowInfo, rowInfo.visitorPlayer().playerLetter());
            SeasonPlayerResult seasonPlayerResultVisitor = createSeasonPlayerAndResultsAsVisitor(rowInfo.visitorPlayer(), allClubsList, allPracticionersList, season, matchInfoKey, matchResultsDetailCsvFileRowInfo, rowInfo.localPlayer().playerLetter());

            String uniqueRowId = createUniqueRowId(seasonPlayerResultLocal, seasonPlayerResultVisitor, matchResultsDetailCsvFileRowInfo, rowInfo);
            createPlayersSingleMatchIfNotExists(seasonPlayerResultLocal, seasonPlayerResultVisitor, matchResultsDetailCsvFileRowInfo, rowInfo, uniqueRowId);
        }
    }

    private MatchInfoKey createMatchInfoKey(
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            BcnesaMatchResultsDetailRowInfo rowInfo) {
        String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();
        String competitionType = matchResultsDetailCsvFileRowInfo.fileInfo().competitionType();
        String competitionCategory = matchResultsDetailCsvFileRowInfo.fileInfo().competitionCategory();
        String competitionScope = matchResultsDetailCsvFileRowInfo.fileInfo().competitionScope();
        String competitionScopeTag = matchResultsDetailCsvFileRowInfo.fileInfo().competitionScopeTag();
        String competitionGroup = matchResultsDetailCsvFileRowInfo.fileInfo().competitionGroup();
        int matchDayNumber = rowInfo.matchDayNumber();

        return new MatchInfoKey(
                season,
                competitionType,
                competitionCategory,
                competitionScope,
                competitionScopeTag,
                competitionGroup,
                matchDayNumber,
                rowInfo.localPlayer().teamName(),
                rowInfo.visitorPlayer().teamName());
    }

    private String createUniqueRowId(
            SeasonPlayerResult local,
            SeasonPlayerResult visitor,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            BcnesaMatchResultsDetailRowInfo rowInfo) {
        String competitionCategory = matchResultsDetailCsvFileRowInfo.fileInfo().competitionCategory();
        String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();
        String competitionGroup = matchResultsDetailCsvFileRowInfo.fileInfo().competitionGroup();
        int matchDayNumber = rowInfo.matchDayNumber();
        String teamNameLocal = rowInfo.localPlayer().teamName();
        String teamNameVisitor = rowInfo.visitorPlayer().teamName();

        return "%s-%s-%s-%s-%s-%s-%s-%s-%s-%s".formatted(
                competitionCategory.strip(),
                season.strip(),
                competitionGroup,
                String.valueOf(matchDayNumber),
                teamNameLocal.strip(),
                local.getSeasonPlayer().getLicense().id().strip(),
                local.getPlayerLetter().strip(),
                teamNameVisitor.strip(),
                visitor.getSeasonPlayer().getLicense().id().strip(),
                visitor.getPlayerLetter().strip()
        );
    }

    private void createPlayersSingleMatchIfNotExists(
            SeasonPlayerResult local,
            SeasonPlayerResult visitor,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            BcnesaMatchResultsDetailRowInfo rowInfo,
            String uniqueRowId) {
        String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();
        String competitionType = matchResultsDetailCsvFileRowInfo.fileInfo().competitionType();
        String competitionCategory = matchResultsDetailCsvFileRowInfo.fileInfo().competitionCategory();
        String competitionScope = matchResultsDetailCsvFileRowInfo.fileInfo().competitionScope();
        String competitionScopeTag = matchResultsDetailCsvFileRowInfo.fileInfo().competitionScopeTag();
        String competitionGroup = matchResultsDetailCsvFileRowInfo.fileInfo().competitionGroup();
        String gender = matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender();
        int matchDayNumber = rowInfo.matchDayNumber();
        ZonedDateTime matchDateTime = rowInfo.matchDateTime();

        Optional<PlayersSingleMatch> optPlayersSingleMatch = playersSingleMatchRepository.findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId(local.getId(), visitor.getId(), uniqueRowId);
        if (optPlayersSingleMatch.isEmpty()) {
            CompetitionInfo competitionInfo = new CompetitionInfo(
                    competitionType,
                    competitionCategory,
                    competitionScope,
                    competitionScopeTag,
                    competitionGroup,
                    gender);

            PlayersSingleMatch playersSingleMatch = PlayersSingleMatch.createNew(
                    local,
                    visitor,
                    season,
                    competitionInfo,
                    matchDayNumber,
                    uniqueRowId,
                    matchDateTime
            );
            playersSingleMatchRepository.save(playersSingleMatch);
        }
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsAsLocal(
            BcnesaPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter) {
        return createSeasonPlayerAndResults(playerInfo, allClubsList, allPracticionerList, seasonRange, matchInfoKey, matchResultsDetailCsvFileRowInfo, opponentLetter, TeamRole.LOCAL);
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsAsVisitor(
            BcnesaPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter) {
        return createSeasonPlayerAndResults(playerInfo, allClubsList, allPracticionerList, seasonRange, matchInfoKey, matchResultsDetailCsvFileRowInfo, opponentLetter, TeamRole.VISITOR);
    }

    private SeasonPlayerResult createSeasonPlayerAndResults(
            BcnesaPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            TeamRole teamRole) {

        Optional<Club> optInferredClub = inferClubByTeamName(playerInfo.teamName(), allClubsList);
        Optional<Practicioner> optInferredPracticioner = inferPracticionerByName(playerInfo.playerName(), allPracticionerList);

        SeasonPlayerResult seasonPlayerResult = null;
        if (optInferredClub.isPresent() && optInferredPracticioner.isPresent()) {
            seasonPlayerResult = createSeasonPlayerAndResultsForClub(
                    optInferredClub.get(),
                    optInferredPracticioner.get(),
                    playerInfo,
                    seasonRange,
                    matchInfoKey,
                    matchResultsDetailCsvFileRowInfo,
                    opponentLetter,
                    teamRole);
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
        return allPracticionersList.stream()
                .max(Comparator.comparingDouble(practicioner -> NameSimilarity.similarity(practicionerName, practicioner.getFullName())));
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsForClub(
            Club inferredClub,
            Practicioner inferredPracticioner,
            BcnesaPlayerCsvInfo playerInfo,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            TeamRole teamRole) {

        SeasonPlayerResult seasonPlayerResult = null;
        Optional<Club> optClub = clubRepository.findByName(inferredClub.getName());
        Optional<Practicioner> optPracticioner = practicionerRepository.findByFullName(inferredPracticioner.getFullName());
        if (optClub.isPresent() && optPracticioner.isPresent()) {
            Club club = optClub.get();
            Practicioner practicioner = optPracticioner.get();
            ClubMember clubMember = getOrCreateClubMember(club, practicioner, seasonRange);
            SeasonPlayer seasonPlayer = getOrCreateSeasonPlayer(
                    practicioner,
                    clubMember,
                    seasonRange,
                    new License("BCN", playerInfo.playerLicense()));

            CompetitionInfo competitionInfo = new CompetitionInfo(
                    matchInfoKey.competitionType(),
                    matchInfoKey.competitionCategory(),
                    matchInfoKey.competitionScope(),
                    matchInfoKey.competitionScopeTag(),
                    matchInfoKey.competitionGroup(),
                    matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender()
            );

            seasonPlayerResult = getOrCreateSeasonPlayerResult(
                    seasonRange, competitionInfo, matchInfoKey.matchDayNumber(), playerInfo, seasonPlayer, opponentLetter, teamRole);

        } else {
            System.out.println("UNABLE TO FIND CLUB BY TEAM NAME: "+inferredClub.getName());
            System.out.println("  > "+matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());

        }
        return seasonPlayerResult;
    }

    private SeasonPlayerResult getOrCreateSeasonPlayerResult(String seasonRange, CompetitionInfo competitionInfo, int matchDayNumber, BcnesaPlayerCsvInfo playerInfo, SeasonPlayer seasonPlayer, String opponentLetter, TeamRole teamRole) {
        SeasonPlayerResult seasonPlayerResult;
        Optional<SeasonPlayerResult> optSeasonPlayerResult = seasonPlayerResultRepository
                .findFor(
                        seasonRange,
                        competitionInfo.competitionType(),
                        competitionInfo.competitionCategory(),
                        competitionInfo.competitionScope(),
                        competitionInfo.competitionScopeTag(),
                        competitionInfo.competitionGroup(),
                        matchDayNumber,
                        playerInfo.playerLetter(),
                        buildPlayersPairingKey(playerInfo.playerLetter(), opponentLetter, teamRole),
                        teamRole,
                        seasonPlayer.getClubMember().getClub().getId()
                );

        if (optSeasonPlayerResult.isPresent()) {
            seasonPlayerResult = optSeasonPlayerResult.get();
            /*
            System.out.println("SEASON PLAYER RESULT ALREADY EXISTS for season %s, competition %s, player %s, match day %s. Skipping creation. ID: %s".formatted(
                    seasonRange,
                    competitionInfo.competitionType(),
                    playerInfo.playerName(),
                    matchDayNumber,
                    seasonPlayerResult.getId()
            ));
            */
        } else {
            seasonPlayerResult = SeasonPlayerResult.createNew(
                    seasonRange,
                    competitionInfo,
                    seasonPlayer,
                    new MatchInfo(
                            matchDayNumber,
                            "",
                            playerInfo.playerLetter(),
                            new int[] {},
                            playerInfo.playerScore(),
                            buildPlayersPairingKey(playerInfo.playerLetter(), opponentLetter, teamRole)
                    ),
                    teamRole
            );
        }
        seasonPlayerResultRepository.save(seasonPlayerResult);
        return seasonPlayerResult;
    }

    private static String buildPlayersPairingKey(String playerLetter, String opponentLetter, TeamRole teamRole) {
        if (teamRole == TeamRole.LOCAL) {
            return "%s-%s".formatted(playerLetter, opponentLetter);
        } else {
            return "%s-%s".formatted(opponentLetter, playerLetter);
        }
    }

    private ClubMember getOrCreateClubMember(Club club, Practicioner practicioner, String seasonRange) {
        ClubMember clubMember = null;
        try {
            Optional<ClubMember> optClubMember = clubMemberRepository.findByPracticionerIdAndClubId(practicioner.getId(), club.getId());
            clubMember = optClubMember.orElseGet(() -> ClubMember.createNew(
                    club,
                    practicioner
            ));
            clubMember.addYearRange(seasonRange);
            clubMemberRepository.save(clubMember);
        } catch (Exception e) {
            System.out.println("ERROR creating ClubMember for club %s and practicioner %s".formatted(club.getName(), practicioner.getFullName()));
            System.out.println("  > "+e.getMessage());
        }
        return clubMember;
    }

    private SeasonPlayer getOrCreateSeasonPlayer(Practicioner practicioner, ClubMember clubMember, String seasonRange, License license) {
        SeasonPlayer seasonPlayer = null;
        try {
            seasonPlayer = seasonPlayerRepository
                    .findByPracticionerIdClubIdSeason(practicioner.getId(), clubMember.getClub().getId(), seasonRange)
                    .orElseGet(() -> SeasonPlayer.createNew(
                            clubMember,
                            license,
                            seasonRange
                    ));
            seasonPlayerRepository.save(seasonPlayer);
        } catch (Exception e) {
            System.out.println("ERROR creating SeasonPlayer for practicioner %s, club %s and season %s".formatted(practicioner.getFullName(), clubMember.getClub().getName(), seasonRange));
            System.out.println("  > "+e.getMessage());
        }
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

    private String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "") // remove spaces/punctuation
                .replace("fc", "");          // remove 'fc' if needed
    }
}
