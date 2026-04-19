package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.delegate;

import jakarta.persistence.EntityManager;
import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.ClubMember;
import org.cttelsamicsterrassa.data.core.domain.model.License;
import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.model.SeasonPlayer;
import org.cttelsamicsterrassa.data.core.domain.model.SeasonPlayerResult;
import org.cttelsamicsterrassa.data.core.domain.model.TeamRole;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubMemberRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PlayersSingleMatchRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.SeasonPlayerResultRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.ImportMetrics;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.ImportRunContext;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaCsvFileRowInfoExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BcnesaRowProcessingTransactionalDelegate (Phase 2).
 *
 * Validates that:
 *  - Exceptions from repository saves propagate out (not swallowed like in Phase 1).
 *  - Downstream saves are never attempted when an upstream save fails.
 *  - Happy path processes a full row and invokes each repository in order.
 *
 * EntityManager is injected as a mock via reflection so flush() calls do not NPE.
 */
class BcnesaRowProcessingTransactionalDelegatePhase2Test {

    private ClubMemberRepository clubMemberRepository;
    private SeasonPlayerRepository seasonPlayerRepository;
    private SeasonPlayerResultRepository seasonPlayerResultRepository;
    private PlayersSingleMatchRepository playersSingleMatchRepository;
    private BcnesaCsvFileRowInfoExtractor rowInfoExtractor;
    private EntityManager entityManager;

    private BcnesaRowProcessingTransactionalDelegate delegate;

    // --- Test data shared across tests ---
    private UUID localClubId;
    private UUID visitorClubId;
    private UUID localPracticionerId;
    private UUID visitorPracticionerId;

    private Club localClub;
    private Club visitorClub;
    private Practicioner localPracticioner;
    private Practicioner visitorPracticioner;

    @BeforeEach
    void setUp() throws Exception {
        clubMemberRepository = mock(ClubMemberRepository.class);
        seasonPlayerRepository = mock(SeasonPlayerRepository.class);
        seasonPlayerResultRepository = mock(SeasonPlayerResultRepository.class);
        playersSingleMatchRepository = mock(PlayersSingleMatchRepository.class);
        rowInfoExtractor = mock(BcnesaCsvFileRowInfoExtractor.class);
        entityManager = mock(EntityManager.class);

        delegate = new BcnesaRowProcessingTransactionalDelegate(
                clubMemberRepository,
                seasonPlayerRepository,
                seasonPlayerResultRepository,
                playersSingleMatchRepository,
                rowInfoExtractor
        );

        // Inject mock EntityManager via reflection (@PersistenceContext field)
        Field emField = BcnesaRowProcessingTransactionalDelegate.class.getDeclaredField("entityManager");
        emField.setAccessible(true);
        emField.set(delegate, entityManager);

        localClubId = UUID.randomUUID();
        visitorClubId = UUID.randomUUID();
        localPracticionerId = UUID.randomUUID();
        visitorPracticionerId = UUID.randomUUID();

        localClub = mock(Club.class);
        when(localClub.getId()).thenReturn(localClubId);
        when(localClub.getName()).thenReturn("Alpha Team");

        visitorClub = mock(Club.class);
        when(visitorClub.getId()).thenReturn(visitorClubId);
        when(visitorClub.getName()).thenReturn("Beta Team");

        localPracticioner = mock(Practicioner.class);
        when(localPracticioner.getId()).thenReturn(localPracticionerId);
        when(localPracticioner.getFullName()).thenReturn("Local Player");

        visitorPracticioner = mock(Practicioner.class);
        when(visitorPracticioner.getId()).thenReturn(visitorPracticionerId);
        when(visitorPracticioner.getFullName()).thenReturn("Visitor Player");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // RC2 fix: exception propagation — no longer swallowed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldPropagateExceptionWhenClubMemberSaveThrows() {
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any()))
                .thenReturn(buildRowInfo("Alpha Team", "A", "Local Player", "Beta Team", "B", "Visitor Player"));

        // ClubMember not found → create + save → throws (before flush)
        when(clubMemberRepository.findByPracticionerIdAndClubId(localPracticionerId, localClubId))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("unique constraint violation"))
                .when(clubMemberRepository).save(any(ClubMember.class));

        assertThrows(RuntimeException.class, () -> {
            delegate.processRow(
                    buildCsvFileRowInfo(),
                    List.of(localClub, visitorClub),
                    List.of(localPracticioner, visitorPracticioner),
                    buildContext(localClub, visitorClub, localPracticioner, visitorPracticioner),
                    new ImportMetrics()
            );
        });

        // SeasonPlayer and downstream saves must never be reached after ClubMember save fails
        verify(seasonPlayerRepository, never()).save(any());
        verify(seasonPlayerResultRepository, never()).save(any());
        verify(playersSingleMatchRepository, never()).save(any());
    }

    @Test
    void shouldPropagateExceptionWhenSeasonPlayerSaveThrows() {
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any()))
                .thenReturn(buildRowInfo("Alpha Team", "A", "Local Player", "Beta Team", "B", "Visitor Player"));

        ClubMember localClubMember = buildClubMember(localClub, localPracticioner);
        ClubMember visitorClubMember = buildClubMember(visitorClub, visitorPracticioner);

        // ClubMember found in DB — no main save, ensureClubMemberSeasonRange will save
        when(clubMemberRepository.findByPracticionerIdAndClubId(localPracticionerId, localClubId))
                .thenReturn(Optional.of(localClubMember));
        when(clubMemberRepository.findByPracticionerIdAndClubId(visitorPracticionerId, visitorClubId))
                .thenReturn(Optional.of(visitorClubMember));

        // SeasonPlayer not found → create + save → throws
        when(seasonPlayerRepository.findByPracticionerIdClubIdSeason(any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("FK violation season_player"))
                .when(seasonPlayerRepository).save(any(SeasonPlayer.class));

        assertThrows(RuntimeException.class, () -> {
            delegate.processRow(
                    buildCsvFileRowInfo(),
                    List.of(localClub, visitorClub),
                    List.of(localPracticioner, visitorPracticioner),
                    buildContext(localClub, visitorClub, localPracticioner, visitorPracticioner),
                    new ImportMetrics()
            );
        });

        // SeasonPlayerResult and PlayersSingleMatch must never be reached
        verify(seasonPlayerResultRepository, never()).save(any());
        verify(playersSingleMatchRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Row skip: inference miss → no saves
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldSkipRowWithoutAnySaveWhenClubAndPracticionerCannotBeInferred() {
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any()))
                .thenReturn(buildRowInfo("Unknown Team", "A", "Unknown Player", "Other Team", "B", "Also Unknown"));

        // Empty lists → inference always returns empty → row skipped
        delegate.processRow(
                buildCsvFileRowInfo(),
                List.of(),
                List.of(),
                new ImportRunContext(java.util.Collections.emptyMap(), java.util.Collections.emptyMap()),
                new ImportMetrics()
        );

        verify(clubMemberRepository, never()).save(any());
        verify(seasonPlayerRepository, never()).save(any());
        verify(seasonPlayerResultRepository, never()).save(any());
        verify(playersSingleMatchRepository, never()).save(any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Row skip: playerLetter == "D"
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldSkipRowWhenLocalPlayerLetterIsD() {
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any()))
                .thenReturn(buildRowInfo("Alpha Team", "D", "Local Player", "Beta Team", "B", "Visitor Player"));

        delegate.processRow(
                buildCsvFileRowInfo(),
                List.of(localClub, visitorClub),
                List.of(localPracticioner, visitorPracticioner),
                new ImportRunContext(java.util.Collections.emptyMap(), java.util.Collections.emptyMap()),
                new ImportMetrics()
        );

        verify(clubMemberRepository, never()).findByPracticionerIdAndClubId(any(UUID.class), any(UUID.class));
        verify(seasonPlayerRepository, never()).findByPracticionerIdClubIdSeason(any(UUID.class), any(UUID.class), anyString());
        verify(seasonPlayerResultRepository, never()).findFor(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(UUID.class));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Happy path: all entities found in DB (full re-run — no saves, no flushes)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void shouldProcessRowSuccessfullyWhenAllEntitiesAlreadyExistInDb() {
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any()))
                .thenReturn(buildRowInfo("Alpha Team", "A", "Local Player", "Beta Team", "B", "Visitor Player"));

        ClubMember localClubMember = buildClubMember(localClub, localPracticioner);
        ClubMember visitorClubMember = buildClubMember(visitorClub, visitorPracticioner);

        SeasonPlayer localSeasonPlayer = buildSeasonPlayer(localClubMember);
        SeasonPlayer visitorSeasonPlayer = buildSeasonPlayer(visitorClubMember);

        UUID localResultId = UUID.randomUUID();
        UUID visitorResultId = UUID.randomUUID();
        SeasonPlayerResult localResult = buildSeasonPlayerResult(localResultId, localSeasonPlayer, "A");
        SeasonPlayerResult visitorResult = buildSeasonPlayerResult(visitorResultId, visitorSeasonPlayer, "B");

        // All entities found in DB → no saves → no flush calls
        when(clubMemberRepository.findByPracticionerIdAndClubId(localPracticionerId, localClubId))
                .thenReturn(Optional.of(localClubMember));
        when(clubMemberRepository.findByPracticionerIdAndClubId(visitorPracticionerId, visitorClubId))
                .thenReturn(Optional.of(visitorClubMember));

        when(seasonPlayerRepository.findByPracticionerIdClubIdSeason(localPracticionerId, localClubId, "2024-2025"))
                .thenReturn(Optional.of(localSeasonPlayer));
        when(seasonPlayerRepository.findByPracticionerIdClubIdSeason(visitorPracticionerId, visitorClubId, "2024-2025"))
                .thenReturn(Optional.of(visitorSeasonPlayer));

        when(seasonPlayerResultRepository.findFor(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(TeamRole.class), any(UUID.class)))
                .thenReturn(Optional.of(localResult))
                .thenReturn(Optional.of(visitorResult));

        when(playersSingleMatchRepository.findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId(
                any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(Optional.of(mock(org.cttelsamicsterrassa.data.core.domain.model.PlayersSingleMatch.class)));

        ImportMetrics metrics = new ImportMetrics();
        delegate.processRow(
                buildCsvFileRowInfo(),
                List.of(localClub, visitorClub),
                List.of(localPracticioner, visitorPracticioner),
                buildContext(localClub, visitorClub, localPracticioner, visitorPracticioner),
                metrics
        );

        // ensureClubMemberSeasonRange saves the ClubMember once per unique player per run
        // (local + visitor = 2 saves). SeasonPlayer, SPR, and PSM are NOT saved (found in DB).
        verify(clubMemberRepository, times(2)).save(any());
        verify(seasonPlayerRepository, never()).save(any());
        verify(seasonPlayerResultRepository, never()).save(any());
        verify(playersSingleMatchRepository, never()).save(any());
        // EntityManager flush called twice (once per ensureClubMemberSeasonRange save)
        verify(entityManager, times(2)).flush();
        // Row was counted as processed
        assert metrics.rowsProcessed == 1 : "Expected rowsProcessed=1 but was " + metrics.rowsProcessed;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static BcnesaMatchResultsDetailCsvFileRowInfo buildCsvFileRowInfo() {
        BcnesaMatchResultsDetailCsvFileInfo fileInfo = new BcnesaMatchResultsDetailCsvFileInfo(
                Path.of("season/league/preferent/jornada4-g1.csv"),
                "2024-2025",
                "league",
                "preferent",
                "provincial",
                "bcn",
                "M",
                "group-1",
                "4"
        );
        return new BcnesaMatchResultsDetailCsvFileRowInfo(fileInfo, new String[]{"x"}, UUID.randomUUID());
    }

    private static BcnesaMatchResultsDetailRowInfo buildRowInfo(
            String localTeam, String localLetter, String localName,
            String visitorTeam, String visitorLetter, String visitorName) {
        BcnesaPlayerCsvInfo local = new BcnesaPlayerCsvInfo(localTeam, localLetter, "L-123", localName, 3, "M");
        BcnesaPlayerCsvInfo visitor = new BcnesaPlayerCsvInfo(visitorTeam, visitorLetter, "V-456", visitorName, 0, "M");
        return new BcnesaMatchResultsDetailRowInfo(local, visitor, 4, "individual", ZonedDateTime.now());
    }

    private ClubMember buildClubMember(Club club, Practicioner practicioner) {
        ClubMember cm = mock(ClubMember.class);
        when(cm.getClub()).thenReturn(club);
        when(cm.getPracticioner()).thenReturn(practicioner);
        return cm;
    }

    private SeasonPlayer buildSeasonPlayer(ClubMember clubMember) {
        SeasonPlayer sp = mock(SeasonPlayer.class);
        when(sp.getClubMember()).thenReturn(clubMember);
        when(sp.getLicense()).thenReturn(new License("BCN", "L-123"));
        return sp;
    }

    private SeasonPlayerResult buildSeasonPlayerResult(UUID id, SeasonPlayer seasonPlayer, String playerLetter) {
        SeasonPlayerResult spr = mock(SeasonPlayerResult.class);
        when(spr.getId()).thenReturn(id);
        when(spr.getSeasonPlayer()).thenReturn(seasonPlayer);
        when(spr.getPlayerLetter()).thenReturn(playerLetter);
        return spr;
    }

    /**
     * Builds an ImportRunContext with pre-populated club and practicioner lookup maps,
     * so inference hits the preloaded map and reaches the persistence layer.
     */
    private static ImportRunContext buildContext(Club localClub, Club visitorClub,
                                                 Practicioner localPracticioner, Practicioner visitorPracticioner) {
        Map<String, Club> clubMap = new HashMap<>();
        clubMap.put(normalizeForTest(localClub.getName()), localClub);
        clubMap.put(normalizeForTest(visitorClub.getName()), visitorClub);

        Map<String, Practicioner> practicionerMap = new HashMap<>();
        practicionerMap.put(normalizePersonName(localPracticioner.getFullName()), localPracticioner);
        practicionerMap.put(normalizePersonName(visitorPracticioner.getFullName()), visitorPracticioner);

        return new ImportRunContext(clubMap, practicionerMap);
    }

    private static String normalizeForTest(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "").replace("fc", "");
    }

    private static String normalizePersonName(String fullName) {
        if (fullName == null) return "";
        return fullName.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}




