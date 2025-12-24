package org.cttelsamicsterrassa.data.importer.shared.service.name;

public class NameSimilarity {

    private static final double EXTRA_TOKEN_PENALTY = 0.05;

    public static double similarity(String name1, String name2) {

        String[] a = NameNormalizer.normalize(name1).split(" ");
        String[] b = NameNormalizer.normalize(name2).split(" ");

        boolean[] used = new boolean[b.length];
        double scoreSum = 0.0;
        int matches = 0;

        for (String tokenA : a) {
            double bestScore = 0.0;
            int bestIndex = -1;

            for (int i = 0; i < b.length; i++) {
                if (used[i]) continue;

                double score = SoftLevenshtein.similarity(tokenA, b[i]);
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }

            // Accept only meaningful matches
            if (bestIndex >= 0 && bestScore >= 0.80) {
                used[bestIndex] = true;
                scoreSum += bestScore;
                matches++;
            }
        }

        if (matches == 0) return 0.0;

        // Base similarity based on matched tokens only
        double baseScore = scoreSum / a.length;

        // Penalize only unmatched extra tokens
        int extraTokens = Math.max(0, b.length - matches);
        double penalty = extraTokens * EXTRA_TOKEN_PENALTY;

        return Math.max(0.0, Math.min(1.0, baseScore - penalty));
    }

    public static boolean isSamePerson(String name1, String name2) {
        return similarity(name1, name2) >= 0.85;
    }
}