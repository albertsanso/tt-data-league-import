package org.cttelsamicsterrassa.data.importer.shared.service;

import org.cttelsamicsterrassa.data.importer.shared.service.name.NameSimilarity;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PracticionerNameSimilarityService {
    public static List<String> reduceToSimilarClustersOfNames(List<String> items) {
        Map<String, List<String>> groups = new LinkedHashMap<>();

        for (String practicionerName : items) {
            String normalizedItem = normalize(practicionerName);
            String bestRoot = null;
            double bestScore = 0.0;

            for (String existingRoot : groups.keySet()) {
                double similarity = NameSimilarity.similarity(existingRoot, practicionerName);
                double adaptiveThreshold = getAdaptiveThreshold(existingRoot, practicionerName);

                if (similarity >= adaptiveThreshold && similarity > bestScore) {
                    bestRoot = existingRoot;
                    bestScore = similarity;
                }
            }

            if (bestRoot != null) {
                groups.get(bestRoot).add(practicionerName);
            } else {
                groups.put(practicionerName, new ArrayList<>(Collections.singletonList(practicionerName)));
            }
        }

        groups.replaceAll((k, v) ->
                new ArrayList<>(new HashSet<>(v))
        );
        return groups.keySet().stream().toList();
    }

    private static double getAdaptiveThreshold(String a, String b) {
        int avgLength = (a.length() + b.length()) / 2;
        if (avgLength < 15) return 0.85;
        if (avgLength < 30) return 0.75;
        if (avgLength < 50) return 0.65;
        return 0.55;
    }

    private static String normalize(String s) {
        // Remove accents and lowercase
        String noAccent = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccent.toLowerCase().trim().replaceAll("\\s+", " ");
    }
}
