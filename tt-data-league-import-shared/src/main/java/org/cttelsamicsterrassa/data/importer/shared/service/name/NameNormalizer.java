package org.cttelsamicsterrassa.data.importer.shared.service.name;

import java.text.Normalizer;

public class NameNormalizer {

    public static String normalize(String input) {
        String s = input.toLowerCase();

        // Remove accents
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Remove punctuation
        s = s.replaceAll("[^a-z\\s]", " ");

        return s.trim().replaceAll("\\s+", " ");
    }
}

