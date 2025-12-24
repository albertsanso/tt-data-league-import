package org.cttelsamicsterrassa.data.importer.shared.service.name;

public class SoftLevenshtein {

    public static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        int distance = Levenshtein.distance(a, b);
        int minLen = Math.min(a.length(), b.length());

        // Soft penalty: extra chars don't dominate
        return 1.0 - ((double) distance / (minLen + 2));
    }
}

