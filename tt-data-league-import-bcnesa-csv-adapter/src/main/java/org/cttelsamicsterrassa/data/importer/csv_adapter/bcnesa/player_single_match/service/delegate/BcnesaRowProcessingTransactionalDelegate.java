package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.delegate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.cttelsamicsterrassa.data.core.domain.repository.PlayersSingleMatchRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerResultRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.ClubMemberCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.SeasonPlayerCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model.SeasonPlayerResultCacheKey;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.ImportMetrics;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.ImportRunContext;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.model.MatchInfoKey;
import org.cttelsamicsterrassa.data.importer.shared.service.name.NameSimilarity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Handles per-row BCNESA results import inside an independent REQUIRES_NEW transaction.
 *
 * Placed in a separate Spring bean so Spring AOP can proxy the @Transactional method.
 * REQUIRES_NEW ensures each row commits (or rolls back) independently, preventing a
 * failed row from poisoning previously committed rows.
 *
 * EntityManager.flush() calls are placed after each new entity INSERT to enforce
 * FK dependency ordering (ClubMember → SeasonPlayer → SeasonPlayerResult → PlayersSingleMatch),
 * counteracting Hibernate's order_inserts reordering within the transaction.
 */
@Component
public class BcnesaRowProcessingTransactionalDelegate {

    private final ClubMemberRepository clubMemberRepository;
    private final SeasonPlayerRepository seasonPlayerRepository;
    private final SeasonPlayerResultRepository seasonPlayerResultRepository;
    private final PlayersSingleMatchRepository playersSingleMatchRepository;
    private final BcnesaCsvFileRowInfoExtractor rowInfoExtractor;

    private final LevenshteinDistance levenshtein = new LevenshteinDistance();

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public BcnesaRowProcessingTransactionalDelegate(
            ClubMemberRepository clubMemberRepository,
            SeasonPlayerRepository seasonPlayerRepository,
            SeasonPlayerResultRepository seasonPlayerResultRepository,
            PlayersSingleMatchRepository playersSingleMatchRepository,
            BcnesaCsvFileRowInfoExtractor rowInfoExtractor) {
        this.clubMemberRepository = clubMemberRepository;
        this.seasonPlayerRepository = seasonPlayerRepository;
        this.seasonPlayerResultRepository = seasonPlayerResultRepository;
        this.playersSingleMatchRepository = playersSingleMatchRepository;
        this.rowInfoExtractor = rowInfoExtractor;
    }

    /**
     * Processes one CSV row inside a new independent transaction.
     * Exceptions propagate to the caller; the outer loop increments rowExceptions and continues.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processRow(
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

        SeasonPlayerResult seasonPlayerResultLocal = createSeasonPlayerAndResultsAsLocal(
                rowInfo.localPlayer(), allClubsList, allPracticionersList, season, matchInfoKey,
                matchResultsDetailCsvFileRowInfo, rowInfo.visitorPlayer().playerLetter(), context, metrics);
        SeasonPlayerResult seasonPlayerResultVisitor = createSeasonPlayerAndResultsAsVisitor(
                rowInfo.visitorPlayer(), allClubsList, allPracticionersList, season, matchInfoKey,
                matchResultsDetailCsvFileRowInfo, rowInfo.localPlayer().playerLetter(), context, metrics);

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

        Optional<PlayersSingleMatch> optPlayersSingleMatch = playersSingleMatchRepository
                .findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId(
                        local.getId(), visitor.getId(), uniqueRowId);
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
        return createSeasonPlayerAndResults(playerInfo, allClubsList, allPracticionerList, seasonRange,
                matchInfoKey, matchResultsDetailCsvFileRowInfo, opponentLetter, TeamRole.LOCAL, context, metrics);
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
        return createSeasonPlayerAndResults(playerInfo, allClubsList, allPracticionerList, seasonRange,
                matchInfoKey, matchResultsDetailCsvFileRowInfo, opponentLetter, TeamRole.VISITOR, context, metrics);
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

        if (inferredClub == null || inferredPracticioner == null) {
            metrics.inferenceMisses++;
            System.out.println("UNABLE TO FIND CLUB OR PRACTICIONER FOR ROW: " + matchResultsDetailCsvFileRowInfo.fileInfo().csvFilepath());
            return null;
        }

        // Exceptions from getOrCreateClubMember / getOrCreateSeasonPlayer propagate to the
        // REQUIRES_NEW transaction boundary, triggering a rollback and surfacing to the outer
        // row loop as rowExceptions. Ghost entities are never cached.
        ClubMember clubMember = getOrCreateClubMember(inferredClub, inferredPracticioner, seasonRange, context, metrics);
        if (clubMember == null) {
            // Defensive guard — should not happen after removing exception swallowing.
            metrics.rowsSkippedNullEntity++;
            return null;
        }

        SeasonPlayer seasonPlayer = getOrCreateSeasonPlayer(
                inferredPracticioner,
                clubMember,
                seasonRange,
                new License("BCN", playerInfo.playerLicense()),
                context,
                metrics);
        if (seasonPlayer == null) {
            // Defensive guard — should not happen after removing exception swallowing.
            metrics.rowsSkippedNullEntity++;
            return null;
        }

        CompetitionInfo competitionInfo = new CompetitionInfo(
                matchInfoKey.competitionType(),
                matchInfoKey.competitionCategory(),
                matchInfoKey.competitionScope(),
                matchInfoKey.competitionScopeTag(),
                matchInfoKey.competitionGroup(),
                matchResultsDetailCsvFileRowInfo.fileInfo().competitionGender()
        );

        return getOrCreateSeasonPlayerResult(
                seasonRange, competitionInfo, matchInfoKey.matchDayNumber(),
                playerInfo, seasonPlayer, opponentLetter, teamRole, context, metrics);
    }

    private SeasonPlayerResult getOrCreateSeasonPlayerResult(
            String seasonRange,
            CompetitionInfo competitionInfo,
            int matchDayNumber,
            BcnesaPlayerCsvInfo playerInfo,
            SeasonPlayer seasonPlayer,
            String opponentLetter,
            TeamRole teamRole,
            ImportRunContext context,
            ImportMetrics metrics) {

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
                            new int[]{},
                            playerInfo.playerScore(),
                            playersPairingKey
                    ),
                    teamRole
            );
            seasonPlayerResultRepository.save(seasonPlayerResult);
            // Flush to guarantee the SeasonPlayerResult row is in DB before PlayersSingleMatch
            // INSERT references it via FK (spr_local_id / spr_visitor_id).
            entityManager.flush();
            metrics.entityManagerFlushCount++;
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

    private ClubMember getOrCreateClubMember(
            Club club,
            Practicioner practicioner,
            String seasonRange,
            ImportRunContext context,
            ImportMetrics metrics) {

        ClubMemberCacheKey cacheKey = new ClubMemberCacheKey(normalizeId(practicioner.getId()), normalizeId(club.getId()));
        ClubMember cachedClubMember = context.clubMemberCache.get(cacheKey);
        if (cachedClubMember != null) {
            metrics.clubMemberCacheHit++;
            ensureClubMemberSeasonRange(cachedClubMember, cacheKey, seasonRange, context, metrics);
            return cachedClubMember;
        }

        metrics.clubMemberCacheMiss++;
        Optional<ClubMember> optClubMember = clubMemberRepository.findByPracticionerIdAndClubId(practicioner.getId(), club.getId());
        ClubMember clubMember = optClubMember.orElseGet(() -> ClubMember.createNew(club, practicioner));

        if (optClubMember.isEmpty()) {
            clubMemberRepository.save(clubMember);
            // Flush to guarantee ClubMember row is in DB before SeasonPlayer INSERT references
            // it via FK (club_member_id). Prevents order_inserts reordering from causing FK violation.
            entityManager.flush();
            metrics.entityManagerFlushCount++;
            metrics.clubMemberSaved++;
        }

        ensureClubMemberSeasonRange(clubMember, cacheKey, seasonRange, context, metrics);
        context.clubMemberCache.put(cacheKey, clubMember);
        return clubMember;
    }

    private SeasonPlayer getOrCreateSeasonPlayer(
            Practicioner practicioner,
            ClubMember clubMember,
            String seasonRange,
            License license,
            ImportRunContext context,
            ImportMetrics metrics) {

        SeasonPlayerCacheKey cacheKey = new SeasonPlayerCacheKey(
                normalizeId(practicioner.getId()),
                normalizeId(clubMember.getClub().getId()),
                seasonRange);
        SeasonPlayer cachedSeasonPlayer = context.seasonPlayerCache.get(cacheKey);
        if (cachedSeasonPlayer != null) {
            metrics.seasonPlayerCacheHit++;
            return cachedSeasonPlayer;
        }

        metrics.seasonPlayerCacheMiss++;
        Optional<SeasonPlayer> existingSeasonPlayer = seasonPlayerRepository
                .findByPracticionerIdClubIdSeason(practicioner.getId(), clubMember.getClub().getId(), seasonRange);

        SeasonPlayer seasonPlayer;
        if (existingSeasonPlayer.isPresent()) {
            seasonPlayer = existingSeasonPlayer.get();
            metrics.saveSkippedNoChange++;
        } else {
            seasonPlayer = SeasonPlayer.createNew(clubMember, license, seasonRange);
            seasonPlayerRepository.save(seasonPlayer);
            // Flush to guarantee SeasonPlayer row is in DB before SeasonPlayerResult INSERT
            // references it via FK (season_player_id). This is the direct fix for the observed
            // FK violation: fkr8ydwhteprdbe811l3y6pk1dt.
            entityManager.flush();
            metrics.entityManagerFlushCount++;
            metrics.seasonPlayerSaved++;
        }

        context.seasonPlayerCache.put(cacheKey, seasonPlayer);
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

    private void ensureClubMemberSeasonRange(
            ClubMember clubMember,
            ClubMemberCacheKey cacheKey,
            String seasonRange,
            ImportRunContext context,
            ImportMetrics metrics) {
        if (context.clubMemberSeasonRangeUpdated.contains(cacheKey)) {
            return;
        }
        clubMember.addYearRange(seasonRange);
        clubMemberRepository.save(clubMember);
        // Flush to guarantee updated ClubMember is visible before SeasonPlayer INSERT.
        entityManager.flush();
        metrics.entityManagerFlushCount++;
        metrics.clubMemberSaved++;
        context.clubMemberSeasonRangeUpdated.add(cacheKey);
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

    private String normalize(String s) {
        return s.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replace("fc", "");
    }

    @SuppressWarnings("unused")
    private String[] splitIntoFirstNameAndSecondName(String input) {
        String[] words = input.split("\\s+");
        List<String> upperWords = new ArrayList<>();
        List<String> lowerWords = new ArrayList<>();

        for (String word : words) {
            if (word.equals(word.toUpperCase())) {
                upperWords.add(word);
            } else {
                lowerWords.add(word);
            }
        }

        String secondName = String.join(" ", upperWords);
        String firstName = String.join(" ", lowerWords);
        return new String[]{firstName, secondName};
    }
}

