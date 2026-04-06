package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.ClubMemberCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.SeasonPlayerCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.SeasonPlayerResultCacheKey;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        ImportMetrics metrics = new ImportMetrics();
        long totalStartTimeMs = System.currentTimeMillis();

        long preloadStartTimeMs = System.currentTimeMillis();
        List<Club> allClubsList = clubRepository.findAll();
        List<Practicioner> allPracticionersList = practicionerRepository.findAll();
        Map<String, Club> clubsByNameMap = buildClubLookupMap(allClubsList);
        Map<String, Practicioner> practicionersByNameMap = buildPracticionerLookupMap(allPracticionersList);
        metrics.preloadMs = System.currentTimeMillis() - preloadStartTimeMs;

        ImportRunContext context = new ImportRunContext(clubsByNameMap, practicionersByNameMap);

        CompletionTracker completionTracker = CompletionTracker.buildTracker(matchResultsDetailCsvFileRowInfoList.size(), 1, "Player and Results import");

        /*
        matchResultsDetailCsvFileRowInfoList
                .parallelStream()
                .forEach(matchResultsDetailCsvFileRowInfo -> {
                            processMatchResultsDetailsRowInfoTransactional(matchResultsDetailCsvFileRowInfo, allClubsList, allPracticionersList);
                            completionTracker.trackIncrement();
                        });
        */

        long rowLoopStartTimeMs = System.currentTimeMillis();
        matchResultsDetailCsvFileRowInfoList
                .forEach(matchResultsDetailCsvFileRowInfo -> {
                    try {
                        processMatchResultsDetailsRowInfoTransactional(matchResultsDetailCsvFileRowInfo, allClubsList, allPracticionersList, context, metrics);
                    } catch (Exception e) {
                        metrics.rowExceptions++;
                        System.err.println("ERROR processing row: " + e.getMessage());
                        e.printStackTrace();
                    } finally {
                        completionTracker.trackIncrement();
                    }
                });

        metrics.rowLoopMs = System.currentTimeMillis() - rowLoopStartTimeMs;
        metrics.totalMs = System.currentTimeMillis() - totalStartTimeMs;
        printImportMetrics(metrics, context);
    }

    @Transactional
    private void processMatchResultsDetailsRowInfoTransactional(
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionersList,
            ImportRunContext context,
            ImportMetrics metrics) {
        processMatchResultsDetailsRowInfo(matchResultsDetailCsvFileRowInfo, allClubsList, allPracticionersList, context, metrics);
    }

    private void processMatchResultsDetailsRowInfo(
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionersList,
            ImportRunContext context,
            ImportMetrics metrics) {

        metrics.rowsTotal++;

        BcnesaMatchResultsDetailRowInfo rowInfo = rowInfoExtractor.extractMatchDetailsRowInfo(matchResultsDetailCsvFileRowInfo);
        MatchInfoKey matchInfoKey = createMatchInfoKey(matchResultsDetailCsvFileRowInfo, rowInfo);

        String season = matchResultsDetailCsvFileRowInfo.fileInfo().season();

        if (rowInfo.localPlayer().playerLetter().equals("D")) {
            metrics.rowsSkippedPlayerD++;
            return;
        }

        SeasonPlayerResult seasonPlayerResultLocal = createSeasonPlayerAndResultsAsLocal(rowInfo.localPlayer(), allClubsList, allPracticionersList, season, matchInfoKey, matchResultsDetailCsvFileRowInfo, rowInfo.visitorPlayer().playerLetter(), context, metrics);
        SeasonPlayerResult seasonPlayerResultVisitor = createSeasonPlayerAndResultsAsVisitor(rowInfo.visitorPlayer(), allClubsList, allPracticionersList, season, matchInfoKey, matchResultsDetailCsvFileRowInfo, rowInfo.localPlayer().playerLetter(), context, metrics);

        if (seasonPlayerResultLocal == null || seasonPlayerResultVisitor == null) {
            metrics.rowsSkippedInferenceMiss++;
            return;
        }

        String uniqueRowId = createUniqueRowId(seasonPlayerResultLocal, seasonPlayerResultVisitor, matchResultsDetailCsvFileRowInfo, rowInfo);
        createPlayersSingleMatchIfNotExists(seasonPlayerResultLocal, seasonPlayerResultVisitor, matchResultsDetailCsvFileRowInfo, rowInfo, uniqueRowId, metrics);
        metrics.rowsProcessed++;
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
            String uniqueRowId,
            ImportMetrics metrics) {
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
            metrics.playersSingleMatchCacheMiss++;
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
            metrics.playersSingleMatchSaved++;
        } else {
            metrics.playersSingleMatchCacheHit++;
            metrics.saveSkippedNoChange++;
        }
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsAsLocal(
            BcnesaPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            ImportRunContext context,
            ImportMetrics metrics) {
        return createSeasonPlayerAndResults(playerInfo, allClubsList, allPracticionerList, seasonRange, matchInfoKey, matchResultsDetailCsvFileRowInfo, opponentLetter, TeamRole.LOCAL, context, metrics);
    }

    private SeasonPlayerResult createSeasonPlayerAndResultsAsVisitor(
            BcnesaPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            ImportRunContext context,
            ImportMetrics metrics) {
        return createSeasonPlayerAndResults(playerInfo, allClubsList, allPracticionerList, seasonRange, matchInfoKey, matchResultsDetailCsvFileRowInfo, opponentLetter, TeamRole.VISITOR, context, metrics);
    }

    private SeasonPlayerResult createSeasonPlayerAndResults(
            BcnesaPlayerCsvInfo playerInfo,
            List<Club> allClubsList,
            List<Practicioner> allPracticionerList,
            String seasonRange,
            MatchInfoKey matchInfoKey,
            BcnesaMatchResultsDetailCsvFileRowInfo matchResultsDetailCsvFileRowInfo,
            String opponentLetter,
            TeamRole teamRole,
            ImportRunContext context,
            ImportMetrics metrics) {

        Optional<Club> optInferredClub = inferClubByTeamName(playerInfo.teamName(), allClubsList);
        Optional<Practicioner> optInferredPracticioner = inferPracticionerByName(playerInfo.playerName(), allPracticionerList);

        Optional<Club> optExistingClub = optInferredClub.flatMap(club -> findClubInPreloadedMap(club, context, metrics));
        Optional<Practicioner> optExistingPracticioner = optInferredPracticioner.flatMap(practicioner -> findPracticionerInPreloadedMap(practicioner, context, metrics));

        SeasonPlayerResult seasonPlayerResult = null;
        if (optExistingClub.isPresent() && optExistingPracticioner.isPresent()) {
            seasonPlayerResult = createSeasonPlayerAndResultsForClub(
                    optExistingClub.get(),
                    optExistingPracticioner.get(),
                    playerInfo,
                    seasonRange,
                    matchInfoKey,
                    matchResultsDetailCsvFileRowInfo,
                    opponentLetter,
                    teamRole,
                    context,
                    metrics);
        } else {
            metrics.inferenceMisses++;
            System.out.println("UNABLE TO INFER CLUB OR PRACTICIONER: " + playerInfo.teamName() + " / " + playerInfo.playerName());
            System.out.println("  > " + matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());
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
            TeamRole teamRole,
            ImportRunContext context,
            ImportMetrics metrics) {

        SeasonPlayerResult seasonPlayerResult = null;
        if (inferredClub != null && inferredPracticioner != null) {
            ClubMember clubMember = getOrCreateClubMember(inferredClub, inferredPracticioner, seasonRange, context, metrics);
            SeasonPlayer seasonPlayer = getOrCreateSeasonPlayer(
                    inferredPracticioner,
                    clubMember,
                    seasonRange,
                    new License("BCN", playerInfo.playerLicense()),
                    context,
                    metrics);

            CompetitionInfo competitionInfo = new CompetitionInfo(
                    matchInfoKey.competitionType(),
                    matchInfoKey.competitionCategory(),
                    matchInfoKey.competitionScope(),
                    matchInfoKey.competitionScopeTag(),
                    matchInfoKey.competitionGroup(),
                    matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender()
            );

            seasonPlayerResult = getOrCreateSeasonPlayerResult(
                    seasonRange, competitionInfo, matchInfoKey.matchDayNumber(), playerInfo, seasonPlayer, opponentLetter, teamRole, context, metrics);

        } else {
            metrics.inferenceMisses++;
            System.out.println("UNABLE TO FIND CLUB OR PRACTICIONER FOR ROW: " + matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());

        }
        return seasonPlayerResult;
    }

    private SeasonPlayerResult getOrCreateSeasonPlayerResult(String seasonRange, CompetitionInfo competitionInfo, int matchDayNumber, BcnesaPlayerCsvInfo playerInfo, SeasonPlayer seasonPlayer, String opponentLetter, TeamRole teamRole, ImportRunContext context, ImportMetrics metrics) {
        String playersPairingKey = buildPlayersPairingKey(playerInfo.playerLetter(), opponentLetter, teamRole);
        SeasonPlayerResultCacheKey cacheKey = new SeasonPlayerResultCacheKey(
                seasonRange,
                competitionInfo.competitionType(),
                competitionInfo.competitionCategory(),
                competitionInfo.competitionScope(),
                competitionInfo.competitionScopeTag(),
                competitionInfo.competitionGroup(),
                matchDayNumber,
                playerInfo.playerLetter(),
                playersPairingKey,
                teamRole,
                normalizeId(seasonPlayer.getClubMember().getClub().getId())
        );

        SeasonPlayerResult cachedResult = context.seasonPlayerResultCache.get(cacheKey);
        if (cachedResult != null) {
            metrics.seasonPlayerResultCacheHit++;
            return cachedResult;
        }

        metrics.seasonPlayerResultCacheMiss++;
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
                        playersPairingKey,
                        teamRole,
                        seasonPlayer.getClubMember().getClub().getId()
                );

        if (optSeasonPlayerResult.isPresent()) {
            SeasonPlayerResult existingResult = optSeasonPlayerResult.get();
            context.seasonPlayerResultCache.put(cacheKey, existingResult);
            metrics.saveSkippedNoChange++;
            return existingResult;
        } else {
            SeasonPlayerResult seasonPlayerResult = SeasonPlayerResult.createNew(
                    seasonRange,
                    competitionInfo,
                    seasonPlayer,
                    new MatchInfo(
                            matchDayNumber,
                            "",
                            playerInfo.playerLetter(),
                            new int[] {},
                            playerInfo.playerScore(),
                            playersPairingKey
                    ),
                    teamRole
            );
            seasonPlayerResultRepository.save(seasonPlayerResult);
            context.seasonPlayerResultCache.put(cacheKey, seasonPlayerResult);
            metrics.seasonPlayerResultSaved++;
            return seasonPlayerResult;
        }
    }

    private static String buildPlayersPairingKey(String playerLetter, String opponentLetter, TeamRole teamRole) {
        if (teamRole == TeamRole.LOCAL) {
            return "%s-%s".formatted(playerLetter, opponentLetter);
        } else {
            return "%s-%s".formatted(opponentLetter, playerLetter);
        }
    }

    private ClubMember getOrCreateClubMember(Club club, Practicioner practicioner, String seasonRange, ImportRunContext context, ImportMetrics metrics) {
        ClubMemberCacheKey cacheKey = new ClubMemberCacheKey(normalizeId(practicioner.getId()), normalizeId(club.getId()));
        ClubMember cachedClubMember = context.clubMemberCache.get(cacheKey);
        if (cachedClubMember != null) {
            metrics.clubMemberCacheHit++;
            ensureClubMemberSeasonRange(cachedClubMember, cacheKey, seasonRange, context, metrics);
            return cachedClubMember;
        }

        metrics.clubMemberCacheMiss++;
        ClubMember clubMember = null;
        try {
            Optional<ClubMember> optClubMember = clubMemberRepository.findByPracticionerIdAndClubId(practicioner.getId(), club.getId());
            clubMember = optClubMember.orElseGet(() -> ClubMember.createNew(
                    club,
                    practicioner
            ));

            if (optClubMember.isEmpty()) {
                clubMemberRepository.save(clubMember);
                metrics.clubMemberSaved++;
            }

            ensureClubMemberSeasonRange(clubMember, cacheKey, seasonRange, context, metrics);
            context.clubMemberCache.put(cacheKey, clubMember);
        } catch (Exception e) {
            System.out.println("ERROR creating ClubMember for club %s and practicioner %s".formatted(club.getName(), practicioner.getFullName()));
            System.out.println("  > "+e.getMessage());
        }
        return clubMember;
    }

    private SeasonPlayer getOrCreateSeasonPlayer(Practicioner practicioner, ClubMember clubMember, String seasonRange, License license, ImportRunContext context, ImportMetrics metrics) {
        SeasonPlayerCacheKey cacheKey = new SeasonPlayerCacheKey(normalizeId(practicioner.getId()), normalizeId(clubMember.getClub().getId()), seasonRange);
        SeasonPlayer cachedSeasonPlayer = context.seasonPlayerCache.get(cacheKey);
        if (cachedSeasonPlayer != null) {
            metrics.seasonPlayerCacheHit++;
            return cachedSeasonPlayer;
        }

        metrics.seasonPlayerCacheMiss++;
        SeasonPlayer seasonPlayer = null;
        try {
            Optional<SeasonPlayer> existingSeasonPlayer = seasonPlayerRepository
                    .findByPracticionerIdClubIdSeason(practicioner.getId(), clubMember.getClub().getId(), seasonRange);

            if (existingSeasonPlayer.isPresent()) {
                seasonPlayer = existingSeasonPlayer.get();
                metrics.saveSkippedNoChange++;
            } else {
                seasonPlayer = SeasonPlayer.createNew(
                        clubMember,
                        license,
                        seasonRange
                );
                seasonPlayerRepository.save(seasonPlayer);
                metrics.seasonPlayerSaved++;
            }

            context.seasonPlayerCache.put(cacheKey, seasonPlayer);
        } catch (Exception e) {
            System.out.println("ERROR creating SeasonPlayer for practicioner %s, club %s and season %s".formatted(practicioner.getFullName(), clubMember.getClub().getName(), seasonRange));
            System.out.println("  > "+e.getMessage());
        }
        return seasonPlayer;
    }

    private Optional<Club> findClubInPreloadedMap(Club inferredClub, ImportRunContext context, ImportMetrics metrics) {
        Club club = context.clubsByNameMap.get(normalize(inferredClub.getName()));
        if (club == null) {
            metrics.clubLookupMiss++;
            return Optional.empty();
        }

        metrics.clubLookupHit++;
        return Optional.of(club);
    }

    private Optional<Practicioner> findPracticionerInPreloadedMap(Practicioner inferredPracticioner, ImportRunContext context, ImportMetrics metrics) {
        Practicioner practicioner = context.practicionersByNameMap.get(normalizePersonName(inferredPracticioner.getFullName()));
        if (practicioner == null) {
            metrics.practicionerLookupMiss++;
            return Optional.empty();
        }

        metrics.practicionerLookupHit++;
        return Optional.of(practicioner);
    }

    private Map<String, Club> buildClubLookupMap(List<Club> allClubsList) {
        Map<String, Club> clubsByNameMap = new HashMap<>();
        for (Club club : allClubsList) {
            clubsByNameMap.putIfAbsent(normalize(club.getName()), club);
        }
        return clubsByNameMap;
    }

    private Map<String, Practicioner> buildPracticionerLookupMap(List<Practicioner> allPracticionersList) {
        Map<String, Practicioner> practicionersByNameMap = new HashMap<>();
        for (Practicioner practicioner : allPracticionersList) {
            practicionersByNameMap.putIfAbsent(normalizePersonName(practicioner.getFullName()), practicioner);
        }
        return practicionersByNameMap;
    }

    private void ensureClubMemberSeasonRange(ClubMember clubMember, ClubMemberCacheKey cacheKey, String seasonRange, ImportRunContext context, ImportMetrics metrics) {
        if (context.clubMemberSeasonRangeUpdated.contains(cacheKey)) {
            return;
        }

        clubMember.addYearRange(seasonRange);
        clubMemberRepository.save(clubMember);
        metrics.clubMemberSaved++;
        context.clubMemberSeasonRangeUpdated.add(cacheKey);
    }

    private void printImportMetrics(ImportMetrics metrics, ImportRunContext context) {
        System.out.println("BCNESA Phase1 metrics: rows total=" + metrics.rowsTotal
                + ", processed=" + metrics.rowsProcessed
                + ", skippedPlayerD=" + metrics.rowsSkippedPlayerD
                + ", skippedInferenceMiss=" + metrics.rowsSkippedInferenceMiss
                + ", rowExceptions=" + metrics.rowExceptions);

        System.out.println("BCNESA Phase1 lookup: club hit/miss=" + metrics.clubLookupHit + "/" + metrics.clubLookupMiss
                + ", practicioner hit/miss=" + metrics.practicionerLookupHit + "/" + metrics.practicionerLookupMiss);

        System.out.println("BCNESA Phase1 cache: clubMember hit/miss=" + metrics.clubMemberCacheHit + "/" + metrics.clubMemberCacheMiss
                + ", seasonPlayer hit/miss=" + metrics.seasonPlayerCacheHit + "/" + metrics.seasonPlayerCacheMiss
                + ", seasonPlayerResult hit/miss=" + metrics.seasonPlayerResultCacheHit + "/" + metrics.seasonPlayerResultCacheMiss
                + ", singleMatch hit/miss=" + metrics.playersSingleMatchCacheHit + "/" + metrics.playersSingleMatchCacheMiss);

        System.out.println("BCNESA Phase1 saves: clubMember=" + metrics.clubMemberSaved
                + ", seasonPlayer=" + metrics.seasonPlayerSaved
                + ", seasonPlayerResult=" + metrics.seasonPlayerResultSaved
                + ", playersSingleMatch=" + metrics.playersSingleMatchSaved
                + ", saveSkippedNoChange=" + metrics.saveSkippedNoChange
                + ", inferenceMisses=" + metrics.inferenceMisses);

        System.out.println("BCNESA Phase1 timingMs: preload=" + metrics.preloadMs
                + ", rowLoop=" + metrics.rowLoopMs
                + ", total=" + metrics.totalMs);

        System.out.println("BCNESA Phase1 cacheSize: clubMember=" + context.clubMemberCache.size()
                + ", seasonPlayer=" + context.seasonPlayerCache.size()
                + ", seasonPlayerResult=" + context.seasonPlayerResultCache.size());
    }

    private static String normalizeId(Object id) {
        return id == null ? "" : id.toString();
    }

    private static String normalizePersonName(String fullName) {
        if (fullName == null) {
            return "";
        }
        return fullName.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static final class ImportRunContext {
        private final Map<String, Club> clubsByNameMap;
        private final Map<String, Practicioner> practicionersByNameMap;
        private final Map<ClubMemberCacheKey, ClubMember> clubMemberCache = new HashMap<>();
        private final Set<ClubMemberCacheKey> clubMemberSeasonRangeUpdated = new HashSet<>();
        private final Map<SeasonPlayerCacheKey, SeasonPlayer> seasonPlayerCache = new HashMap<>();
        private final Map<SeasonPlayerResultCacheKey, SeasonPlayerResult> seasonPlayerResultCache = new HashMap<>();

        private ImportRunContext(Map<String, Club> clubsByNameMap, Map<String, Practicioner> practicionersByNameMap) {
            this.clubsByNameMap = clubsByNameMap;
            this.practicionersByNameMap = practicionersByNameMap;
        }
    }

    private static final class ImportMetrics {
        private long rowsTotal;
        private long rowsProcessed;
        private long rowsSkippedPlayerD;
        private long rowsSkippedInferenceMiss;
        private long rowExceptions;
        private long inferenceMisses;

        private long clubLookupHit;
        private long clubLookupMiss;
        private long practicionerLookupHit;
        private long practicionerLookupMiss;

        private long clubMemberCacheHit;
        private long clubMemberCacheMiss;
        private long seasonPlayerCacheHit;
        private long seasonPlayerCacheMiss;
        private long seasonPlayerResultCacheHit;
        private long seasonPlayerResultCacheMiss;
        private long playersSingleMatchCacheHit;
        private long playersSingleMatchCacheMiss;

        private long clubMemberSaved;
        private long seasonPlayerSaved;
        private long seasonPlayerResultSaved;
        private long playersSingleMatchSaved;
        private long saveSkippedNoChange;

        private long preloadMs;
        private long rowLoopMs;
        private long totalMs;
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
