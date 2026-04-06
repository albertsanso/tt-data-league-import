package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.service;

import org.cttelsamicsterrassa.data.core.domain.model.Club;
import org.cttelsamicsterrassa.data.core.domain.model.ClubMember;
import org.cttelsamicsterrassa.data.core.domain.model.License;
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
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailCsvFileRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaMatchResultsDetailRowInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.model.fs.BcnesaPlayerCsvInfo;
import org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.shared.service.BcnesaCsvFileRowInfoExtractor;
import org.cttelsamicsterrassa.data.importer.shared.service.MatchResultDetailsByLineIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BcnesaPlayerAndResultsInitialImportServicePhase1Test {

    private ClubRepository clubRepository;
    private BcnesaCsvFileRowInfoExtractor rowInfoExtractor;
    private ClubMemberRepository clubMemberRepository;
    private PracticionerRepository practicionerRepository;
    private SeasonPlayerRepository seasonPlayerRepository;
    private SeasonPlayerResultRepository seasonPlayerResultRepository;
    private PlayersSingleMatchRepository playersSingleMatchRepository;

    private BcnesaPlayerAndResultsInitialImportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MatchResultDetailsByLineIterator<BcnesaMatchResultsDetailCsvFileRowInfo, BcnesaMatchResultsDetailCsvFileInfo> iterator =
                mock(MatchResultDetailsByLineIterator.class);
        clubRepository = mock(ClubRepository.class);
        rowInfoExtractor = mock(BcnesaCsvFileRowInfoExtractor.class);
        clubMemberRepository = mock(ClubMemberRepository.class);
        practicionerRepository = mock(PracticionerRepository.class);
        seasonPlayerRepository = mock(SeasonPlayerRepository.class);
        seasonPlayerResultRepository = mock(SeasonPlayerResultRepository.class);
        playersSingleMatchRepository = mock(PlayersSingleMatchRepository.class);

        service = new BcnesaPlayerAndResultsInitialImportService(
                iterator,
                clubRepository,
                rowInfoExtractor,
                clubMemberRepository,
                practicionerRepository,
                seasonPlayerRepository,
                seasonPlayerResultRepository,
                playersSingleMatchRepository
        );
    }

    @Test
    void shouldSkipRowWhenInferenceCannotResolveClubOrPracticioner() throws Exception {
        when(clubRepository.findAll()).thenReturn(List.of());
        when(practicionerRepository.findAll()).thenReturn(List.of());
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any())).thenReturn(buildRowInfo("Alpha Team", "A", "Unknown Name", "Beta Team", "B", "Other Name"));

        invokeProcessMatchResultsDetailsInfo(List.of(buildCsvFileRowInfo(), buildCsvFileRowInfo()));

        verify(clubMemberRepository, never()).findByPracticionerIdAndClubId(any(UUID.class), any(UUID.class));
        verify(seasonPlayerRepository, never()).findByPracticionerIdClubIdSeason(any(UUID.class), any(UUID.class), anyString());
        verify(seasonPlayerResultRepository, never()).findFor(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), any(), any(UUID.class));
        verify(playersSingleMatchRepository, never()).findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    void shouldUseCacheAndAvoidDuplicateFindCallsForRepeatedRows() throws Exception {
        UUID localClubId = UUID.randomUUID();
        UUID visitorClubId = UUID.randomUUID();
        UUID localPracticionerId = UUID.randomUUID();
        UUID visitorPracticionerId = UUID.randomUUID();
        UUID localResultId = UUID.randomUUID();
        UUID visitorResultId = UUID.randomUUID();

        Club localClub = mock(Club.class);
        when(localClub.getId()).thenReturn(localClubId);
        when(localClub.getName()).thenReturn("Alpha Team");

        Club visitorClub = mock(Club.class);
        when(visitorClub.getId()).thenReturn(visitorClubId);
        when(visitorClub.getName()).thenReturn("Beta Team");

        Practicioner localPracticioner = mock(Practicioner.class);
        when(localPracticioner.getId()).thenReturn(localPracticionerId);
        when(localPracticioner.getFullName()).thenReturn("Local Player");

        Practicioner visitorPracticioner = mock(Practicioner.class);
        when(visitorPracticioner.getId()).thenReturn(visitorPracticionerId);
        when(visitorPracticioner.getFullName()).thenReturn("Visitor Player");

        ClubMember localClubMember = mock(ClubMember.class);
        when(localClubMember.getClub()).thenReturn(localClub);

        ClubMember visitorClubMember = mock(ClubMember.class);
        when(visitorClubMember.getClub()).thenReturn(visitorClub);

        SeasonPlayer localSeasonPlayer = mock(SeasonPlayer.class);
        when(localSeasonPlayer.getClubMember()).thenReturn(localClubMember);
        when(localSeasonPlayer.getLicense()).thenReturn(new License("BCN", "L-123"));

        SeasonPlayer visitorSeasonPlayer = mock(SeasonPlayer.class);
        when(visitorSeasonPlayer.getClubMember()).thenReturn(visitorClubMember);
        when(visitorSeasonPlayer.getLicense()).thenReturn(new License("BCN", "V-456"));

        SeasonPlayerResult localResult = mock(SeasonPlayerResult.class);
        when(localResult.getId()).thenReturn(localResultId);
        when(localResult.getSeasonPlayer()).thenReturn(localSeasonPlayer);
        when(localResult.getPlayerLetter()).thenReturn("A");

        SeasonPlayerResult visitorResult = mock(SeasonPlayerResult.class);
        when(visitorResult.getId()).thenReturn(visitorResultId);
        when(visitorResult.getSeasonPlayer()).thenReturn(visitorSeasonPlayer);
        when(visitorResult.getPlayerLetter()).thenReturn("B");

        when(clubRepository.findAll()).thenReturn(List.of(localClub, visitorClub));
        when(practicionerRepository.findAll()).thenReturn(List.of(localPracticioner, visitorPracticioner));
        when(rowInfoExtractor.extractMatchDetailsRowInfo(any())).thenReturn(buildRowInfo("Alpha Team", "A", "Local Player", "Beta Team", "B", "Visitor Player"));

        when(clubMemberRepository.findByPracticionerIdAndClubId(localPracticionerId, localClubId)).thenReturn(Optional.of(localClubMember));
        when(clubMemberRepository.findByPracticionerIdAndClubId(visitorPracticionerId, visitorClubId)).thenReturn(Optional.of(visitorClubMember));

        when(seasonPlayerRepository.findByPracticionerIdClubIdSeason(localPracticionerId, localClubId, "2024-2025")).thenReturn(Optional.of(localSeasonPlayer));
        when(seasonPlayerRepository.findByPracticionerIdClubIdSeason(visitorPracticionerId, visitorClubId, "2024-2025")).thenReturn(Optional.of(visitorSeasonPlayer));

        when(seasonPlayerResultRepository.findFor(
                "2024-2025", "league", "preferent", "provincial", "bcn", "group-1", 4, "A", "A-B", org.cttelsamicsterrassa.data.core.domain.model.TeamRole.LOCAL, localClubId
        )).thenReturn(Optional.of(localResult));
        when(seasonPlayerResultRepository.findFor(
                "2024-2025", "league", "preferent", "provincial", "bcn", "group-1", 4, "B", "A-B", org.cttelsamicsterrassa.data.core.domain.model.TeamRole.VISITOR, visitorClubId
        )).thenReturn(Optional.of(visitorResult));

        when(playersSingleMatchRepository.findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId(any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(Optional.of(mock(PlayersSingleMatch.class)));

        invokeProcessMatchResultsDetailsInfo(List.of(buildCsvFileRowInfo(), buildCsvFileRowInfo()));

        verify(clubMemberRepository, times(2)).findByPracticionerIdAndClubId(any(UUID.class), any(UUID.class));
        verify(seasonPlayerRepository, times(2)).findByPracticionerIdClubIdSeason(any(UUID.class), any(UUID.class), anyString());
        verify(seasonPlayerResultRepository, times(2)).findFor(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), any(), any(UUID.class));
        verify(playersSingleMatchRepository, times(2)).findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId(any(UUID.class), any(UUID.class), anyString());
        verify(playersSingleMatchRepository, never()).save(any());
    }

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

    private static BcnesaMatchResultsDetailRowInfo buildRowInfo(
            String localTeam,
            String localLetter,
            String localName,
            String visitorTeam,
            String visitorLetter,
            String visitorName) {
        BcnesaPlayerCsvInfo local = new BcnesaPlayerCsvInfo(localTeam, localLetter, "L-123", localName, 3, "M");
        BcnesaPlayerCsvInfo visitor = new BcnesaPlayerCsvInfo(visitorTeam, visitorLetter, "V-456", visitorName, 0, "M");
        return new BcnesaMatchResultsDetailRowInfo(local, visitor, 4, "individual", ZonedDateTime.now());
    }
}



