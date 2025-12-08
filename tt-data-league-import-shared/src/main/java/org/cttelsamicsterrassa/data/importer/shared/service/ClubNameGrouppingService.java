package org.cttelsamicsterrassa.data.importer.shared.service;


import org.cttelsamicsterrassa.data.importer.shared.model.ClubNameAndYearInfo;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ClubNameGrouppingService {
    private static final double MERGE_THRESHOLD = 0.8;

    // ---------------- CORE GROUPING LOGIC ----------------
    public static Map<String, List<String>> groupByCommonRoot(List<ClubNameAndYearInfo> items) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, Set<String>> groupsAndYears = new LinkedHashMap<>();
        Map<String, List<String>> outputClubNamesAndYears = new LinkedHashMap<>();

        for (ClubNameAndYearInfo item : items) {
            String normalizedItem = normalize(item.clubName());
            String bestRoot = null;
            double bestScore = 0.0;

            for (String existingRoot : groups.keySet()) {
                double similarity = levenshteinSimilarity(normalize(existingRoot), normalizedItem);
                double adaptiveThreshold = getAdaptiveThreshold(existingRoot, item.clubName());

                if (similarity >= adaptiveThreshold && similarity > bestScore) {
                    bestRoot = existingRoot;
                    bestScore = similarity;
                }
            }

            if (bestRoot != null) {
                groups.get(bestRoot).add(item.clubName());
                groupsAndYears.get(bestRoot).add(item.yearRange());
            } else {
                groups.put(item.clubName(), new ArrayList<>(Collections.singletonList(item.clubName())));
                groupsAndYears.put(item.clubName(), new HashSet<>(Collections.singletonList(item.yearRange())));
            }
        }

        // Optionally refine roots by picking the common core phrase
        Map<String, List<String>> mergedGroupsMap = mergeSimilarGroups(groups);

        // return Map where key is clubName and value is list of yearRanges
        mergedGroupsMap.keySet().stream().forEach(clubName -> {
            Set<String> rangesSet = groupsAndYears.get(clubName);
            if (rangesSet != null) {
                outputClubNamesAndYears.put(clubName, rangesSet.stream()
                        .sorted(Comparator.comparing(r -> r.split("-")[0])) // sort by first year
                        .toList());
            }
        });

        //return mergedGroupsMap;
        return outputClubNamesAndYears;
    }

    // ---------------- ADAPTIVE THRESHOLD ----------------
    private static double getAdaptiveThreshold(String a, String b) {
        int avgLength = (a.length() + b.length()) / 2;
        if (avgLength < 15) return 0.85;
        if (avgLength < 30) return 0.75;
        if (avgLength < 50) return 0.65;
        return 0.55;
    }

    // ---------------- LEVENSHTEIN SIMILARITY ----------------
    private static double levenshteinSimilarity(String s1, String s2) {
        int dist = levenshteinDistance(s1, s2);
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) dist / maxLen);
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) costs[j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            costs[0] = i;
            int nw = i - 1;
            for (int j = 1; j <= s2.length(); j++) {
                int cj = Math.min(1 + Math.min(costs[j], costs[j - 1]),
                        s1.charAt(i - 1) == s2.charAt(j - 1) ? nw : nw + 1);
                nw = costs[j];
                costs[j] = cj;
            }
        }
        return costs[s2.length()];
    }

    // ---------------- GROUP MERGING / ROOT CLEANING ----------------
    private static Map<String, List<String>> mergeSimilarGroups(Map<String, List<String>> groups) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            String root = cleanRoot(e.getKey());
            Optional<String> match = merged.keySet().stream()
                    .filter(r -> levenshteinSimilarity(normalize(r), normalize(root)) > MERGE_THRESHOLD)
                    .findFirst();
            if (match.isPresent()) {
                merged.get(match.get()).addAll(e.getValue());
            } else {
                merged.put(root, new ArrayList<>(e.getValue()));
            }
        }
        return merged;
    }

    private static String normalize(String s) {
        // Remove accents and lowercase
        String noAccent = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccent.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private static String cleanRoot(String s) {
        return s.replaceAll("\\s+-\\s*.*$", "").trim();
    }
}

