package org.cttelsamicsterrassa.data.importer.csv_adapter.bcnesa.player_single_match.model;

import org.cttelsamicsterrassa.data.core.domain.model.TeamRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SeasonPlayerResultCacheKeyTest {

    @Test
    void shouldMatchWhenAllNaturalKeyFieldsAreEqual() {
        SeasonPlayerResultCacheKey left = new SeasonPlayerResultCacheKey(
                "2024-2025",
                "league",
                "preferent",
                "provincial",
                "bcn",
                "group-1",
                4,
                "A",
                "A-B",
                TeamRole.LOCAL,
                "club-10"
        );

        SeasonPlayerResultCacheKey right = new SeasonPlayerResultCacheKey(
                "2024-2025",
                "league",
                "preferent",
                "provincial",
                "bcn",
                "group-1",
                4,
                "A",
                "A-B",
                TeamRole.LOCAL,
                "club-10"
        );

        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    void shouldNotMatchWhenAnyNaturalKeyFieldChanges() {
        SeasonPlayerResultCacheKey base = new SeasonPlayerResultCacheKey(
                "2024-2025",
                "league",
                "preferent",
                "provincial",
                "bcn",
                "group-1",
                4,
                "A",
                "A-B",
                TeamRole.LOCAL,
                "club-10"
        );

        SeasonPlayerResultCacheKey differentPairing = new SeasonPlayerResultCacheKey(
                "2024-2025",
                "league",
                "preferent",
                "provincial",
                "bcn",
                "group-1",
                4,
                "A",
                "B-A",
                TeamRole.LOCAL,
                "club-10"
        );

        assertNotEquals(base, differentPairing);
    }
}

