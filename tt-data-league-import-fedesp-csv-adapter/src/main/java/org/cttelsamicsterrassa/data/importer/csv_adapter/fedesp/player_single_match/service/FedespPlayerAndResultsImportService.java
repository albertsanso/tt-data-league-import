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
import org.cttelsamicsterrassa.data.core.domain.model.TeamRole;
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
import org.cttelsamicsterrassa.data.importer.shared.service.CompletionTracker;
import org.cttelsamicsterrassa.data.importer.shared.service.LineByLineInitialImportService;
import org.cttelsamicsterrassa.data.importer.shared.service.name.NameSimilarity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FedespPlayerAndResultsImportService extends LineByLineInitialImportService<FedespMatchResultsDetailCsvFileRowInfo, FedespMatchResultsDetailCsvFileInfo> {

    private static final double MIN_PRACTICIONER_SIMILARITY = 0.50;
    private static final int ROW_TRANSACTION_BATCH_SIZE = 200;

    private final ClubRepository clubRepository;

    private final FedespCsvFileRowInfoExtractor rowInfoExtractor;

    private final LevenshteinDistance levenshtein = LevenshteinDistance.getDefaultInstance();

    private final ClubMemberRepository clubMemberRepository;

    private final PracticionerRepository practicionerRepository;

    private final SeasonPlayerRepository seasonPlayerRepository;

    private final SeasonPlayerResultRepository seasonPlayerResultRepository;

    private final PlayersSingleMatchRepository playersSingleMatchRepository;

    private final TransactionTemplate transactionTemplate;

    @Autowired
    public FedespPlayerAndResultsImportService(FedespMatchResultDetailsByLineIterator fedespMatchResultDetailsByLineIterator, ClubRepository clubRepository, FedespCsvFileRowInfoExtractor rowInfoExtractor, ClubMemberRepository clubMemberRepository, PracticionerRepository practicionerRepository, SeasonPlayerRepository seasonPlayerRepository, SeasonPlayerResultRepository seasonPlayerResultRepository, PlayersSingleMatchRepository playersSingleMatchRepository, PlatformTransactionManager platformTransactionManager) {
        super(fedespMatchResultDetailsByLineIterator);
        this.clubRepository = clubRepository;
        this.rowInfoExtractor = rowInfoExtractor;
        this.clubMemberRepository = clubMemberRepository;
        this.practicionerRepository = practicionerRepository;
        this.seasonPlayerRepository = seasonPlayerRepository;
        this.seasonPlayerResultRepository = seasonPlayerResultRepository;
        this.playersSingleMatchRepository = playersSingleMatchRepository;
        this.transactionTemplate = new TransactionTemplate(platformTransactionManager);
    }

    public void processForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
        long fileDiscoveryStart = System.currentTimeMillis();
        resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
        long fileDiscoveryMs = System.currentTimeMillis() - fileDiscoveryStart;
        importMatchResultsDetailsInfo(fileDiscoveryMs, "season " + seasonRange);
    }

    public void processForAllSeasons(String baseSeasonsFolder) throws IOException {
        long fileDiscoveryStart = System.currentTimeMillis();
        resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
        long fileDiscoveryMs = System.currentTimeMillis() - fileDiscoveryStart;
        importMatchResultsDetailsInfo(fileDiscoveryMs, "all seasons");
    }

    private void importMatchResultsDetailsInfo(long fileDiscoveryMs, String scopeLabel) {
        long fetchStart = System.currentTimeMillis();
        List<FedespMatchResultsDetailCsvFileRowInfo> rowInfowsList = fetchCsvRowInfos();
        long fetchMs = System.currentTimeMillis() - fetchStart;

        long processingStart = System.currentTimeMillis();
        ImportStats stats = processMatchResultsDetailsInfo(rowInfowsList);
        long processingMs = System.currentTimeMillis() - processingStart;

        System.out.println("[FEDESP][Results] scope=%s fileDiscoveryMs=%d rowFetchMs=%d rowProcessingMs=%d totalRows=%d skippedRows=%d clubInferenceMisses=%d practicionerInferenceMisses=%d".formatted(
                scopeLabel,
                fileDiscoveryMs,
                fetchMs,
                processingMs,
                stats.totalRows,
                stats.skippedRows,
                stats.clubInferenceMisses,
                stats.practicionerInferenceMisses
        ));

        System.out.println("[FEDESP][Results] cacheHits clubMember=%d seasonPlayer=%d seasonPlayerResult=%d | cacheMisses clubMember=%d seasonPlayer=%d seasonPlayerResult=%d".formatted(
                stats.clubMemberCacheHits,
                stats.seasonPlayerCacheHits,
                stats.seasonPlayerResultCacheHits,
                stats.clubMemberCacheMisses,
                stats.seasonPlayerCacheMisses,
                stats.seasonPlayerResultCacheMisses
        ));

        System.out.println("[FEDESP][Results] dbHits clubMember=%d seasonPlayer=%d seasonPlayerResult=%d playersSingleMatch=%d | dbMisses clubMember=%d seasonPlayer=%d seasonPlayerResult=%d playersSingleMatch=%d".formatted(
                stats.clubMemberDbHits,
                stats.seasonPlayerDbHits,
                stats.seasonPlayerResultDbHits,
                stats.playersSingleMatchDbHits,
                stats.clubMemberDbMisses,
                stats.seasonPlayerDbMisses,
                stats.seasonPlayerResultDbMisses,
                stats.playersSingleMatchDbMisses
        ));

        System.out.println("[FEDESP][Results] writes clubMember=%d seasonPlayer=%d seasonPlayerResult=%d playersSingleMatch=%d".formatted(
                stats.clubMemberWrites,
                stats.seasonPlayerWrites,
                stats.seasonPlayerResultWrites,
                stats.playersSingleMatchWrites
        ));
    }

    private ImportStats processMatchResultsDetailsInfo(List<FedespMatchResultsDetailCsvFileRowInfo> matchResultsDetailCsvFileRowInfoList) {
        List<Club> allClubsList = clubRepository.findAll();
        List<Practicioner> allPracticionersList = practicionerRepository.findAll();

        ImportStats stats = new ImportStats(matchResultsDetailCsvFileRowInfoList.size());
        Map<String, ClubMember> clubMemberCache = new HashMap<>();
        Map<String, SeasonPlayer> seasonPlayerCache = new HashMap<>();
        Map<String, SeasonPlayerResult> seasonPlayerResultCache = new HashMap<>();
        Map<String, Optional<Club>> inferredClubByTeamName = new HashMap<>();
        Map<String, Optional<Practicioner>> inferredPracticionerByName = new HashMap<>();

        CompletionTracker completionTracker = CompletionTracker.buildTracker(matchResultsDetailCsvFileRowInfoList.size(), 1, "Player and Results import");

        for (int startIndex = 0; startIndex < matchResultsDetailCsvFileRowInfoList.size(); startIndex += ROW_TRANSACTION_BATCH_SIZE) {
            final int fromIndex = startIndex;
            final int toIndex = Math.min(startIndex + ROW_TRANSACTION_BATCH_SIZE, matchResultsDetailCsvFileRowInfoList.size());

            transactionTemplate.executeWithoutResult(status -> {
                for (int i = fromIndex; i < toIndex; i++) {
                    FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo = matchResultsDetailCsvFileRowInfoList.get(i);
                    processMatchResultsDetailsRowInfo(
                            matchResultsDetailCsvFileRowInfo,
                            allClubsList,
                            allPracticionersList,
                            clubMemberCache,
                            seasonPlayerCache,
                            seasonPlayerResultCache,
                            inferredClubByTeamName,
                            inferredPracticionerByName,
                            stats);
                    completionTracker.trackIncrement();
                }
            });
        }

        return stats;
    }

    private void processMatchResultsDetailsRowInfo(
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionersList,
            Map<String, ClubMember> clubMemberCache,
            Map<String, SeasonPlayer> seasonPlayerCache,
            Map<String, SeasonPlayerResult> seasonPlayerResultCache,
            Map<String, Optional<Club>> inferredClubByTeamName,
            Map<String, Optional<Practicioner>> inferredPracticionerByName,
            ImportStats stats) {

        FedespMatchResultsDetailRowInfo rowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(matchResultsDetailCsvFileRowInfo);
        MatchInfoKey matchInfoKey = createMatchInfoKey(matchResultsDetailCsvFileRowInfo, rowInfo);

        String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();

        if (!rowInfo.localPlayer().playerLetter().equals("D")) {
            SeasonPlayerResult seasonPlayerResultLocal = createSeasonPlayerAndResultsAsLocal(
                    rowInfo.localPlayer(),
                    allClubsList,
                    allPracticionersList,
                    season,
                    matchInfoKey,
                    matchResultsDetailCsvFileRowInfo,
                    rowInfo.visitorPlayer().playerLetter(),
                    clubMemberCache,
                    seasonPlayerCache,
                    seasonPlayerResultCache,
                    inferredClubByTeamName,
                    inferredPracticionerByName,
                    stats);

            SeasonPlayerResult seasonPlayerResultVisitor = createSeasonPlayerAndResultsAsVisitor(
                    rowInfo.visitorPlayer(),
                    allClubsList,
                    allPracticionersList,
                    season,
                    matchInfoKey,
                    matchResultsDetailCsvFileRowInfo,
                    rowInfo.localPlayer().playerLetter(),
                    clubMemberCache,
                    seasonPlayerCache,
                    seasonPlayerResultCache,
                    inferredClubByTeamName,
                    inferredPracticionerByName,
                    stats);

            if (seasonPlayerResultLocal == null || seasonPlayerResultVisitor == null) {
                return;
            }

            String uniqueRowId = createUniqueRowId(seasonPlayerResultLocal, seasonPlayerResultVisitor, matchResultsDetailCsvFileRowInfo, rowInfo);
            createPlayersSingleMatchIfNotExists(seasonPlayerResultLocal, seasonPlayerResultVisitor, matchResultsDetailCsvFileRowInfo, rowInfo, uniqueRowId, stats);
        } else {
            stats.skippedRows++;
        }
    }

    private MatchInfoKey createMatchInfoKey(
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            FedespMatchResultsDetailRowInfo rowInfo) {
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
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            FedespMatchResultsDetailRowInfo rowInfo) {
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
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            FedespMatchResultsDetailRowInfo rowInfo,
            String uniqueRowId,
            ImportStats stats) {
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
            stats.playersSingleMatchDbMisses++;
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
            stats.playersSingleMatchWrites++;
        } else {
            stats.playersSingleMatchDbHits++;
        }
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsAsLocal(
            FedespPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            Map<String, ClubMember> clubMemberCache,
            Map<String, SeasonPlayer> seasonPlayerCache,
            Map<String, SeasonPlayerResult> seasonPlayerResultCache,
            Map<String, Optional<Club>> inferredClubByTeamName,
            Map<String, Optional<Practicioner>> inferredPracticionerByName,
            ImportStats stats) {
        return createSeasonPlayerAndResults(
                playerInfo,
                allClubsList,
                allPracticionerList,
                seasonRange,
                matchInfoKey,
                matchResultsDetailCsvFileRowInfo,
                opponentLetter,
                TeamRole.LOCAL,
                clubMemberCache,
                seasonPlayerCache,
                seasonPlayerResultCache,
                inferredClubByTeamName,
                inferredPracticionerByName,
                stats);
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsAsVisitor(
            FedespPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            Map<String, ClubMember> clubMemberCache,
            Map<String, SeasonPlayer> seasonPlayerCache,
            Map<String, SeasonPlayerResult> seasonPlayerResultCache,
            Map<String, Optional<Club>> inferredClubByTeamName,
            Map<String, Optional<Practicioner>> inferredPracticionerByName,
            ImportStats stats) {
        return createSeasonPlayerAndResults(
                playerInfo,
                allClubsList,
                allPracticionerList,
                seasonRange,
                matchInfoKey,
                matchResultsDetailCsvFileRowInfo,
                opponentLetter,
                TeamRole.VISITOR,
                clubMemberCache,
                seasonPlayerCache,
                seasonPlayerResultCache,
                inferredClubByTeamName,
                inferredPracticionerByName,
                stats);
    }

    private SeasonPlayerResult createSeasonPlayerAndResults(
            FedespPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            TeamRole teamRole,
            Map<String, ClubMember> clubMemberCache,
            Map<String, SeasonPlayer> seasonPlayerCache,
            Map<String, SeasonPlayerResult> seasonPlayerResultCache,
            Map<String, Optional<Club>> inferredClubByTeamName,
            Map<String, Optional<Practicioner>> inferredPracticionerByName,
            ImportStats stats) {

        Optional<Club> optInferredClub = inferredClubByTeamName.computeIfAbsent(
                playerInfo.teamName(),
                teamName -> inferClubByTeamName(teamName, allClubsList));
        Optional<Practicioner> optInferredPracticioner = inferredPracticionerByName.computeIfAbsent(
                playerInfo.playerName(),
                practicionerName -> inferPracticionerByName(practicionerName, allPracticionerList));

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
                    teamRole,
                    clubMemberCache,
                    seasonPlayerCache,
                    seasonPlayerResultCache,
                    stats);
        } else {
            if (optInferredClub.isEmpty()) {
                stats.clubInferenceMisses++;
                System.out.println("UNABLE TO INFER CLUB BY TEAM NAME: " + playerInfo.teamName());
            }
            if (optInferredPracticioner.isEmpty()) {
                stats.practicionerInferenceMisses++;
                System.out.println("UNABLE TO INFER PRACTICIONER BY NAME: " + playerInfo.playerName());
            }
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
                .map(practicioner -> Map.entry(practicioner, NameSimilarity.similarity(practicionerName, practicioner.getFullName())))
                .filter(entry -> entry.getValue() >= MIN_PRACTICIONER_SIMILARITY)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsForClub(
            Club inferredClub,
            Practicioner inferredPracticioner,
            FedespPlayerCsvInfo playerInfo,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            FedespMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            TeamRole teamRole,
            Map<String, ClubMember> clubMemberCache,
            Map<String, SeasonPlayer> seasonPlayerCache,
            Map<String, SeasonPlayerResult> seasonPlayerResultCache,
            ImportStats stats) {

        SeasonPlayerResult seasonPlayerResult = null;
        if (inferredClub != null && inferredPracticioner != null) {
            ClubMember clubMember = getOrCreateClubMember(inferredClub, inferredPracticioner, seasonRange, clubMemberCache, stats);
            SeasonPlayer seasonPlayer = getOrCreateSeasonPlayer(
                    inferredPracticioner,
                    clubMember,
                    seasonRange,
                    new License("ESP", playerInfo.playerLicense()),
                    seasonPlayerCache,
                    stats);

            CompetitionInfo competitionInfo = new CompetitionInfo(
                    matchInfoKey.competitionType(),
                    matchInfoKey.competitionCategory(),
                    matchInfoKey.competitionScope(),
                    matchInfoKey.competitionScopeTag(),
                    matchInfoKey.competitionGroup(),
                    matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender()
            );

            seasonPlayerResult = getOrCreateSeasonPlayerResult(
                    seasonRange,
                    competitionInfo,
                    matchInfoKey.matchDayNumber(),
                    playerInfo,
                    seasonPlayer,
                    opponentLetter,
                    teamRole,
                    seasonPlayerResultCache,
                    stats);

        } else {
            System.out.println("UNABLE TO CREATE SEASON PLAYER RESULT FOR INFERRED CLUB/PRACTICIONER");
            System.out.println("  > "+matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());

        }
        return seasonPlayerResult;
    }

    private SeasonPlayerResult getOrCreateSeasonPlayerResult(
            String seasonRange,
            CompetitionInfo competitionInfo,
            int matchDayNumber,
            FedespPlayerCsvInfo playerInfo,
            SeasonPlayer seasonPlayer,
            String opponentLetter,
            TeamRole teamRole,
            Map<String, SeasonPlayerResult> seasonPlayerResultCache,
            ImportStats stats) {

        String key = buildSeasonPlayerResultKey(
                seasonRange,
                competitionInfo,
                matchDayNumber,
                playerInfo.playerLetter(),
                buildPlayersPairingKey(playerInfo.playerLetter(), opponentLetter, teamRole),
                teamRole,
                seasonPlayer.getClubMember().getClub().getId());

        SeasonPlayerResult cachedSeasonPlayerResult = seasonPlayerResultCache.get(key);
        if (cachedSeasonPlayerResult != null) {
            stats.seasonPlayerResultCacheHits++;
            return cachedSeasonPlayerResult;
        }

        stats.seasonPlayerResultCacheMisses++;
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
            stats.seasonPlayerResultDbHits++;
            seasonPlayerResult = optSeasonPlayerResult.get();
        } else {
            stats.seasonPlayerResultDbMisses++;
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
            seasonPlayerResultRepository.save(seasonPlayerResult);
            stats.seasonPlayerResultWrites++;
        }

        seasonPlayerResultCache.put(key, seasonPlayerResult);
        return seasonPlayerResult;
    }

    private static String buildPlayersPairingKey(String playerLetter, String opponentLetter, TeamRole teamRole) {
        if (teamRole == TeamRole.LOCAL) {
            return "%s-%s".formatted(playerLetter, opponentLetter);
        } else {
            return "%s-%s".formatted(opponentLetter, playerLetter);
        }
    }

    private ClubMember getOrCreateClubMember(
            Club club,
            Practicioner practicioner,
            String seasonRange,
            Map<String, ClubMember> clubMemberCache,
            ImportStats stats) {

        String key = buildClubMemberKey(practicioner.getId(), club.getId());
        ClubMember cachedClubMember = clubMemberCache.get(key);
        if (cachedClubMember != null) {
            stats.clubMemberCacheHits++;
            return cachedClubMember;
        }

        stats.clubMemberCacheMisses++;
        Optional<ClubMember> optClubMember = clubMemberRepository.findByPracticionerIdAndClubId(practicioner.getId(), club.getId());
        ClubMember clubMember;
        if (optClubMember.isPresent()) {
            stats.clubMemberDbHits++;
            clubMember = optClubMember.get();
        } else {
            stats.clubMemberDbMisses++;
            clubMember = ClubMember.createNew(club, practicioner);
            clubMember.addYearRange(seasonRange);
            clubMemberRepository.save(clubMember);
            stats.clubMemberWrites++;
        }

        clubMemberCache.put(key, clubMember);
        return clubMember;
    }

    private SeasonPlayer getOrCreateSeasonPlayer(
            Practicioner practicioner,
            ClubMember clubMember,
            String seasonRange,
            License license,
            Map<String, SeasonPlayer> seasonPlayerCache,
            ImportStats stats) {

        String key = buildSeasonPlayerKey(practicioner.getId(), clubMember.getClub().getId(), seasonRange);
        SeasonPlayer cachedSeasonPlayer = seasonPlayerCache.get(key);
        if (cachedSeasonPlayer != null) {
            stats.seasonPlayerCacheHits++;
            return cachedSeasonPlayer;
        }

        stats.seasonPlayerCacheMisses++;
        Optional<SeasonPlayer> optSeasonPlayer = seasonPlayerRepository
                .findByPracticionerIdClubIdSeason(practicioner.getId(), clubMember.getClub().getId(), seasonRange);

        SeasonPlayer seasonPlayer;
        if (optSeasonPlayer.isPresent()) {
            stats.seasonPlayerDbHits++;
            seasonPlayer = optSeasonPlayer.get();
        } else {
            stats.seasonPlayerDbMisses++;
            seasonPlayer = SeasonPlayer.createNew(
                    clubMember,
                    license,
                    seasonRange
            );
            seasonPlayerRepository.save(seasonPlayer);
            stats.seasonPlayerWrites++;
        }

        seasonPlayerCache.put(key, seasonPlayer);
        return seasonPlayer;
    }

    private static String buildClubMemberKey(Object practicionerId, Object clubId) {
        return practicionerId + "::" + clubId;
    }

    private static String buildSeasonPlayerKey(Object practicionerId, Object clubId, String seasonRange) {
        return practicionerId + "::" + clubId + "::" + seasonRange;
    }

    private static String buildSeasonPlayerResultKey(
            String seasonRange,
            CompetitionInfo competitionInfo,
            int matchDayNumber,
            String playerLetter,
            String playersPairingKey,
            TeamRole teamRole,
            Object clubId) {
        return "%s::%s::%s::%s::%s::%s::%s::%s::%s::%s::%s".formatted(
                seasonRange,
                competitionInfo.competitionType(),
                competitionInfo.competitionCategory(),
                competitionInfo.competitionScope(),
                competitionInfo.competitionScopeTag(),
                competitionInfo.competitionGroup(),
                matchDayNumber,
                playerLetter,
                playersPairingKey,
                teamRole,
                clubId
        );
    }

    private static final class ImportStats {
        private final int totalRows;
        private int skippedRows;
        private int clubInferenceMisses;
        private int practicionerInferenceMisses;

        private int clubMemberCacheHits;
        private int clubMemberCacheMisses;
        private int seasonPlayerCacheHits;
        private int seasonPlayerCacheMisses;
        private int seasonPlayerResultCacheHits;
        private int seasonPlayerResultCacheMisses;

        private int clubMemberDbHits;
        private int clubMemberDbMisses;
        private int seasonPlayerDbHits;
        private int seasonPlayerDbMisses;
        private int seasonPlayerResultDbHits;
        private int seasonPlayerResultDbMisses;
        private int playersSingleMatchDbHits;
        private int playersSingleMatchDbMisses;

        private int clubMemberWrites;
        private int seasonPlayerWrites;
        private int seasonPlayerResultWrites;
        private int playersSingleMatchWrites;

        private ImportStats(int totalRows) {
            this.totalRows = totalRows;
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "") // remove spaces/punctuation
                .replace("fc", "");          // remove 'fc' if needed
    }
}
