package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.Practicioner;
import org.cttelsamicsterrassa.data.core.domain.repository.ClubRepository;
import org.cttelsamicsterrassa.data.core.domain.repository.PracticionerRepository;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service.delegate.BcnesaRowProcessingTransactionalDelegate;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for BcnesaPlayerAndResultsInitialImportService orchestration layer.
 *
 * After Phase 2, the service no longer calls repositories directly.
 * All entity logic has moved to BcnesaRowProcessingTransactionalDelegate.
 * These tests verify service-level concerns: delegate invocation per row,
 * exception isolation, and metrics tracking.
 */
class BcnesaPlayerAndResultsInitialImportServicePhase1Test {

    private ClubRepository clubRepository;
    private PracticionerRepository practicionerRepository;
    private BcnesaRowProcessingTransactionalDelegate delegate;

    private BcnesaPlayerAndResultsInitialImportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MatchResultDetailsByLineIterator<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> iterator =
                mock(MatchResultDetailsByLineIterator.class);
        clubRepository = mock(ClubRepository.class);
        practicionerRepository = mock(PracticionerRepository.class);
        delegate = mock(BcnesaRowProcessingTransactionalDelegate.class);

        service = new BcnesaPlayerAndResultsInitialImportService(
                iterator,
                clubRepository,
                practicionerRepository,
                delegate
        );
    }

    @Test
    void shouldCallDelegateOncePerRow() throws Exception {
        when(clubRepository.findAll()).thenReturn(List.of());
        when(practicionerRepository.findAll()).thenReturn(List.of());

        List<BcnesaMatchResultsDetailCsvFileRowInfo> twoRows = List.of(buildCsvFileRowInfo(), buildCsvFileRowInfo());
        invokeProcessMatchResultsDetailsInfo(twoRows);

        verify(delegate, times(2)).processRow(
                any(BcnesaMatchResultsDetailCsvFileRowInfo.class),
                any(List.class),
                any(List.class),
                any(ImportRunContext.class),
                any(ImportMetrics.class));
    }

    @Test
    void shouldCallDelegateForEachRowEvenWhenClubListIsEmpty() throws Exception {
        when(clubRepository.findAll()).thenReturn(List.of());
        when(practicionerRepository.findAll()).thenReturn(List.of());

        invokeProcessMatchResultsDetailsInfo(List.of(buildCsvFileRowInfo()));

        verify(delegate, times(1)).processRow(
                any(BcnesaMatchResultsDetailCsvFileRowInfo.class),
                eq(List.of()),
                eq(List.of()),
                any(ImportRunContext.class),
                any(ImportMetrics.class));
    }

    @Test
    void shouldNotPropagateExceptionWhenDelegateThrowsAndShouldContinueNextRow() throws Exception {
        when(clubRepository.findAll()).thenReturn(List.of());
        when(practicionerRepository.findAll()).thenReturn(List.of());

        doThrow(new RuntimeException("simulated FK violation"))
                .when(delegate).processRow(any(), any(), any(), any(), any());

        // Two rows — both throw. Neither exception should propagate to the test.
        List<BcnesaMatchResultsDetailCsvFileRowInfo> twoRows = List.of(buildCsvFileRowInfo(), buildCsvFileRowInfo());
        invokeProcessMatchResultsDetailsInfo(twoRows);

        // Delegate was invoked twice despite both throwing
        verify(delegate, times(2)).processRow(any(), any(), any(), any(), any());
    }

    @Test
    void shouldNotCallDelegateWhenRowListIsEmpty() throws Exception {
        when(clubRepository.findAll()).thenReturn(List.of());
        when(practicionerRepository.findAll()).thenReturn(List.of());

        invokeProcessMatchResultsDetailsInfo(List.of());

        verify(delegate, never()).processRow(any(), any(), any(), any(), any());
    }

    @Test
    void shouldPreloadClubsAndPracticionersBeforeCallingDelegate() throws Exception {
        Club club = mock(Club.class);
        when(club.getName()).thenReturn("Test Club");
        Practicioner practicioner = mock(Practicioner.class);
        when(practicioner.getFullName()).thenReturn("Test Player");

        when(clubRepository.findAll()).thenReturn(List.of(club));
        when(practicionerRepository.findAll()).thenReturn(List.of(practicioner));

        invokeProcessMatchResultsDetailsInfo(List.of(buildCsvFileRowInfo()));

        // Repositories called exactly once for preload
        verify(clubRepository, times(1)).findAll();
        verify(practicionerRepository, times(1)).findAll();

        // Delegate called with the preloaded lists
        verify(delegate, times(1)).processRow(
                any(),
                eq(List.of(club)),
                eq(List.of(practicioner)),
                any(),
                any());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Infrastructure helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void invokeProcessMatchResultsDetailsInfo(List<BcnesaMatchResultsDetailCsvFileRowInfo> rows) throws Exception {
        Method method = BcnesaPlayerAndResultsInitialImportService.class
                .getDeclaredMethod("processMatchResultsDetailsInfo", List.class);
        method.setAccessible(true);
        method.invoke(service, rows);
    }

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

    @SuppressWarnings("unused")
    private static BcnesaMatchResultsDetailRowInfo buildRowInfo(
            String localTeam, String localLetter, String localName,
            String visitorTeam, String visitorLetter, String visitorName) {
        BcnesaPlayerCsvInfo local = new BcnesaPlayerCsvInfo(localTeam, localLetter, "L-123", localName, 3, "M");
        BcnesaPlayerCsvInfo visitor = new BcnesaPlayerCsvInfo(visitorTeam, visitorLetter, "V-456", visitorName, 0, "M");
        return new BcnesaMatchResultsDetailRowInfo(local, visitor, 4, "individual", ZonedDateTime.now());
    }
}

