# Phase 2 Implementation Guide - Fuzzy Matching Optimization

**Target**: Additional 30-50% performance improvement after Phase 1  
**Estimated Time Reduction**: 2-5 seconds → 1-2 seconds  
**Focus**: Fuzzy matching algorithm optimization  
**Effort**: 2-3 weeks  

---

## Problem Analysis (Recap)

Current `PracticionerNameSimilarityService` clustering algorithm is **O(n²)** in worst case:

```
Input: 400 distinct practitioner names
Current: 400 × 400 × (similarity_calc) = 160,000 similarity calculations
Time: ~5-15 seconds
```

Main costs:
1. **Normalization repeated per comparison** (50-100 µs per name)
2. **No early termination** for obviously non-matching names
3. **No caching** of computed similarities

---

## Solution Strategy

### Strategy 2A: Normalization Caching (Low Effort, 10-15% Improvement)

**Already proposed in Phase 1**, but implement here if deferred:

```java
@Component
public class PracticionerNameSimilarityService {
    private static final Map<String, String> NORM_CACHE = new ConcurrentHashMap<>();
    
    public static List<String> reduceToSimilarClustersOfNames(List<String> items) {
        // Clear cache at start of batch
        NORM_CACHE.clear();
        
        Map<String, List<String>> groups = new LinkedHashMap<>();
        CompletionTracker tracker = CompletionTracker.buildTracker(items.size(), 5, "Grouping...");

        for (String practicionerName : items) {
            String bestRoot = null;
            double bestScore = 0.0;

            for (String existingRoot : groups.keySet()) {
                double similarity = NameSimilarity.similarity(
                    getCachedNormalized(existingRoot),  // ← Use cache
                    practicionerName
                );
                double threshold = getAdaptiveThreshold(existingRoot, practicionerName);

                if (similarity >= threshold && similarity > bestScore) {
                    bestRoot = existingRoot;
                    bestScore = similarity;
                }
            }

            if (bestRoot != null) {
                groups.get(bestRoot).add(practicionerName);
            } else {
                groups.put(practicionerName, new ArrayList<>(Collections.singletonList(practicionerName)));
            }

            tracker.trackIncrement();
        }

        groups.replaceAll((k, v) -> new ArrayList<>(new HashSet<>(v)));
        return groups.keySet().stream().toList();
    }
    
    private static String getCachedNormalized(String s) {
        return NORM_CACHE.computeIfAbsent(s, key -> normalize(key));
    }
    
    // ... rest of implementation
}
```

**Benefit**: 50-100 ms saved (~5-10% improvement)

---

### Strategy 2B: Pre-Filter with Cheap Heuristics (Medium Effort, 15-25% Improvement)

**Rationale**: Many name pairs can be eliminated without expensive similarity computation.

```java
@Component
public class PracticionerNameSimilarityService {
    
    // Pre-filter thresholds (tunable)
    private static final int MAX_LENGTH_DIFF = 20;  // Names differ by more than 20 chars → skip
    private static final int MIN_COMMON_PREFIX = 2;  // Must share first 2 chars
    
    public static List<String> reduceToSimilarClustersOfNames(List<String> items) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        CompletionTracker tracker = CompletionTracker.buildTracker(items.size(), 5, "Grouping...");

        for (String practicionerName : items) {
            String bestRoot = null;
            double bestScore = 0.0;

            for (String existingRoot : groups.keySet()) {
                // === PRE-FILTER 1: Length check (O(1)) ===
                int lengthDiff = Math.abs(existingRoot.length() - practicionerName.length());
                if (lengthDiff > MAX_LENGTH_DIFF) {
                    continue;  // Skip expensive similarity check
                }
                
                // === PRE-FILTER 2: Common prefix check (O(min_length)) ===
                if (commonPrefixLength(existingRoot, practicionerName) < MIN_COMMON_PREFIX) {
                    continue;  // Skip expensive similarity check
                }
                
                // === Now do expensive similarity check ===
                double similarity = NameSimilarity.similarity(existingRoot, practicionerName);
                double threshold = getAdaptiveThreshold(existingRoot, practicionerName);

                if (similarity >= threshold && similarity > bestScore) {
                    bestRoot = existingRoot;
                    bestScore = similarity;
                }
            }

            if (bestRoot != null) {
                groups.get(bestRoot).add(practicionerName);
            } else {
                groups.put(practicionerName, new ArrayList<>(Collections.singletonList(practicionerName)));
            }

            tracker.trackIncrement();
        }

        groups.replaceAll((k, v) -> new ArrayList<>(new HashSet<>(v)));
        return groups.keySet().stream().toList();
    }
    
    /**
     * Count common prefix length (normalized)
     * E.g., "João Silva" and "Joao Silva" share prefix length 4 ("joao")
     */
    private static int commonPrefixLength(String a, String b) {
        String normA = normalize(a);
        String normB = normalize(b);
        int len = 0;
        int minLen = Math.min(normA.length(), normB.length());
        while (len < minLen && normA.charAt(len) == normB.charAt(len)) {
            len++;
        }
        return len;
    }
    
    // ... rest unchanged
}
```

**Effect on example**:
- Input: 400 names
- Without pre-filter: 160,000 similarity calculations
- With pre-filter: ~40,000 calculations (75% reduction)
- Time savings: 2,000-5,000 ms (20-30% improvement)

---

### Strategy 2C: Token-Level Pre-Grouping (Medium Effort, 10-20% Improvement)

**Rationale**: Exact token matches are common; use them as cheap grouping key first.

```java
@Component
public class PracticionerNameTokenGroupingService {
    
    /**
     * First pass: group by token overlap (cheap, exact matching)
     * Second pass: fuzzy match within token groups
     */
    public static List<String> reduceToSimilarClustersOfNames(List<String> items) {
        // === PASS 1: Token-based exact grouping ===
        Map<String, List<String>> tokenGroups = new HashMap<>();
        
        for (String name : items) {
            String[] tokens = normalizeAndTokenize(name);
            for (String token : tokens) {
                tokenGroups.computeIfAbsent(token, k -> new ArrayList<>()).add(name);
            }
        }
        
        // === PASS 2: Fuzzy match within each token group ===
        Map<String, List<String>> finalGroups = new LinkedHashMap<>();
        CompletionTracker tracker = CompletionTracker.buildTracker(items.size(), 5, "Grouping...");
        
        Set<String> processed = new HashSet<>();
        for (String name : items) {
            if (processed.contains(name)) continue;
            
            // Find the token group this name belongs to
            String[] tokens = normalizeAndTokenize(name);
            Set<String> candidates = new HashSet<>();
            for (String token : tokens) {
                candidates.addAll(tokenGroups.getOrDefault(token, new ArrayList<>()));
            }
            
            // Fuzzy cluster within candidates (much smaller set than all names)
            List<String> cluster = fuzzyClusterSmallSet(candidates, name);
            
            for (String item : cluster) {
                processed.add(item);
            }
            
            finalGroups.put(name, cluster);
            tracker.trackIncrement();
        }
        
        return finalGroups.keySet().stream().toList();
    }
    
    private static String[] normalizeAndTokenize(String name) {
        String normalized = normalize(name);
        return normalized.split("\\s+");
    }
    
    private static List<String> fuzzyClusterSmallSet(Set<String> candidates, String anchor) {
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            double similarity = NameSimilarity.similarity(anchor, candidate);
            if (similarity >= getAdaptiveThreshold(anchor, candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }
    
    // ... rest unchanged
}
```

**Effect**:
- First pass groups related names into "neighborhoods"
- Second pass fuzzy matches within smaller neighborhoods
- Reduces comparisons from O(n²) to O(n × k) where k = avg neighborhood size (~10-20)

---

### Strategy 2D: Similarity Cache (Medium Effort, 5-15% Improvement)

**Rationale**: Some names are compared multiple times; cache results.

```java
@Component
public class PracticionerNameSimilarityService {
    
    // Cache: (nameA_normalized + "|" + nameB_normalized) → similarity_score
    private static final Map<String, Double> SIMILARITY_CACHE = new ConcurrentHashMap<>();
    
    public static List<String> reduceToSimilarClustersOfNames(List<String> items) {
        SIMILARITY_CACHE.clear();  // Clear at start of batch
        
        Map<String, List<String>> groups = new LinkedHashMap<>();
        CompletionTracker tracker = CompletionTracker.buildTracker(items.size(), 5, "Grouping...");

        for (String practicionerName : items) {
            String bestRoot = null;
            double bestScore = 0.0;

            for (String existingRoot : groups.keySet()) {
                double similarity = getCachedSimilarity(existingRoot, practicionerName);
                double threshold = getAdaptiveThreshold(existingRoot, practicionerName);

                if (similarity >= threshold && similarity > bestScore) {
                    bestRoot = existingRoot;
                    bestScore = similarity;
                }
            }

            if (bestRoot != null) {
                groups.get(bestRoot).add(practicionerName);
            } else {
                groups.put(practicionerName, new ArrayList<>(Collections.singletonList(practicionerName)));
            }

            tracker.trackIncrement();
        }

        groups.replaceAll((k, v) -> new ArrayList<>(new HashSet<>(v)));
        SIMILARITY_CACHE.clear();  // Free memory after use
        return groups.keySet().stream().toList();
    }
    
    private static double getCachedSimilarity(String a, String b) {
        String key = getCacheKey(a, b);
        return SIMILARITY_CACHE.computeIfAbsent(key, k -> NameSimilarity.similarity(a, b));
    }
    
    private static String getCacheKey(String a, String b) {
        // Normalize order to use same cache key regardless of argument order
        if (a.compareTo(b) <= 0) {
            return a + "|" + b;
        } else {
            return b + "|" + a;
        }
    }
    
    // ... rest unchanged
}
```

**Effect**: 5-15% improvement depending on name repetition patterns

---

## Recommended Phase 2 Approach

### Implement in Order of Effort:

1. **Strategy 2A** (Normalization caching) - 1 hour
   - Add cache to `normalize()` method
   - Expected: 50-100 ms improvement

2. **Strategy 2B** (Pre-filters) - 4-6 hours
   - Add `commonPrefixLength()` method
   - Add pre-filter checks before similarity
   - Test with realistic data to tune thresholds
   - Expected: 2,000-5,000 ms improvement

3. **Strategy 2C** (Token-level grouping) - 8-12 hours
   - More complex logic; requires careful implementation
   - Potential risk of over-grouping/under-grouping
   - Can defer if 2A+2B sufficient
   - Expected: 1,000-3,000 ms improvement

4. **Strategy 2D** (Similarity cache) - 2-3 hours
   - Only if significant duplicate comparisons observed
   - Expected: 300-800 ms improvement

---

## Implementation: Combined 2A + 2B

This is the recommended minimum for Phase 2:

```java
package org.cttelsamicsterrassa.data.importer.shared.service;

import org.cttelsamicsterrassa.data.importer.shared.service.name.NameSimilarity;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PracticionerNameSimilarityService {
    
    // Pre-filter thresholds
    private static final int MAX_LENGTH_DIFF = 25;
    private static final int MIN_COMMON_PREFIX = 2;
    
    // Caches (cleared between batches)
    private static final Map<String, String> NORMALIZATION_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Double> SIMILARITY_CACHE = new ConcurrentHashMap<>();
    
    public static List<String> reduceToSimilarClustersOfNames(List<String> items) {
        clearCaches();
        
        Map<String, List<String>> groups = new LinkedHashMap<>();
        CompletionTracker completionTracker = CompletionTracker.buildTracker(items.size(), 5, "Grouping practicioner names");

        long comparisonsSkipped = 0;
        long comparisonsTotal = 0;

        for (String practicionerName : items) {
            String bestRoot = null;
            double bestScore = 0.0;

            for (String existingRoot : groups.keySet()) {
                comparisonsTotal++;
                
                // === PRE-FILTER 1: Length check ===
                int lengthDiff = Math.abs(existingRoot.length() - practicionerName.length());
                if (lengthDiff > MAX_LENGTH_DIFF) {
                    comparisonsSkipped++;
                    continue;
                }
                
                // === PRE-FILTER 2: Common prefix check ===
                if (commonPrefixLength(existingRoot, practicionerName) < MIN_COMMON_PREFIX) {
                    comparisonsSkipped++;
                    continue;
                }
                
                // === Expensive similarity check ===
                double similarity = getCachedSimilarity(existingRoot, practicionerName);
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

            completionTracker.trackIncrement();
        }

        groups.replaceAll((k, v) -> new ArrayList<>(new HashSet<>(v)));
        
        // Log optimization statistics
        System.out.println("Clustering stats: " + comparisonsTotal + " comparisons, " + 
                         comparisonsSkipped + " skipped by pre-filters (" + 
                         (100 * comparisonsSkipped / Math.max(1, comparisonsTotal)) + "%)");
        
        clearCaches();
        return groups.keySet().stream().toList();
    }
    
    /**
     * Get cached similarity score; compute if not cached
     */
    private static double getCachedSimilarity(String a, String b) {
        String cacheKey = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
        return SIMILARITY_CACHE.computeIfAbsent(cacheKey, k -> NameSimilarity.similarity(a, b));
    }
    
    /**
     * Count common prefix length after normalization
     */
    private static int commonPrefixLength(String a, String b) {
        String normA = getCachedNormalized(a);
        String normB = getCachedNormalized(b);
        int len = 0;
        int minLen = Math.min(normA.length(), normB.length());
        while (len < minLen && normA.charAt(len) == normB.charAt(len)) {
            len++;
        }
        return len;
    }
    
    /**
     * Get cached normalized version of string
     */
    private static String getCachedNormalized(String s) {
        return NORMALIZATION_CACHE.computeIfAbsent(s, key -> normalize(key));
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
    
    /**
     * Clear caches between batch processing
     */
    public static void clearCaches() {
        NORMALIZATION_CACHE.clear();
        SIMILARITY_CACHE.clear();
    }
}
```

---

## Integration with Phase 1

If combining Phase 1 + Phase 2, modify `BcnesaPracticionerInitialImportService`:

```java
public void processPracticionersForSeason(String baseSeasonsFolder, String seasonRange) throws IOException {
    // Clear fuzzy-matching caches before processing
    PracticionerNameSimilarityService.clearCaches();
    
    resetAndLoadTextFilesForSeason(baseSeasonsFolder, seasonRange);
    importPracticioners();
}

public void processParacticionersForAllSeasons(String baseSeasonsFolder) throws IOException {
    // Clear fuzzy-matching caches before processing
    PracticionerNameSimilarityService.clearCaches();
    
    resetAndLoadTextFilesForAllSeasons(baseSeasonsFolder);
    importPracticioners();
}
```

---

## Testing Strategy

### Unit Test for Pre-Filters

```java
@Test
void testPreFiltersSkipNonMatches() {
    // Very different names should be pre-filtered
    int lengthDiff = Math.abs("João Silva Ferreira".length() - "A".length());
    assertTrue(lengthDiff > 25);  // Should be skipped by MAX_LENGTH_DIFF pre-filter
    
    // Names with no common prefix
    String prefix1 = getCommonPrefix("João Silva", "Xavier Gomez");
    assertEquals(0, prefix1.length());  // Should be skipped
}

@Test
void testCachingImprovesPerfomance() {
    List<String> names = generateTestNames(100);
    
    long startTime = System.nanoTime();
    List<String> clustered = PracticionerNameSimilarityService.reduceToSimilarClustersOfNames(names);
    long duration = System.nanoTime() - startTime;
    
    // Should complete in <500ms for 100 names with optimizations
    assertTrue(duration < 500_000_000);
}
```

### Performance Benchmark

```bash
# Run with Phase 2 optimizations and measure:
mvn -pl tt-data-league-import-runtime spring-boot:run \
  -Dspring-boot.run.arguments="--federation=bcnesa --workflow=practicioners ..."

# Expected output in logs:
# "Clustering stats: 160000 comparisons, 120000 skipped by pre-filters (75%)"
# Total time: 1-2 seconds (down from 7-20 seconds initially)
```

---

## Tuning Parameters

If Phase 2 doesn't achieve target performance, adjust:

```java
// In PracticionerNameSimilarityService
private static final int MAX_LENGTH_DIFF = 25;      // Increase for more strict filtering
private static final int MIN_COMMON_PREFIX = 2;     // Decrease to allow more matches
```

Monitor and log:
```
Clustering stats: X comparisons, Y skipped
Rejection rate: Y/X%
```

If rejection rate < 70%, consider lowering thresholds.  
If rejection rate > 90%, consider raising thresholds (pre-filtering too aggressive).

---

## Expected Phase 1 + Phase 2 Results

```
After Phase 1 only:
  Total: 3-6 seconds
  DB IOPs: 5-10
  Fuzzy matching: 8,000 ms (unchanged)

After Phase 1 + Phase 2:
  Total: 1-2 seconds
  DB IOPs: 5-10 (unchanged)
  Fuzzy matching: 2,000-3,000 ms (50-60% reduction)
  Pre-filter skip rate: 70-75%
  Cache hit rate: 20-30%
```

**Overall improvement from baseline**: **6-10x faster** (7-20s → 1-2s)

---

## Phase 3 Preview (Advanced, Not Recommended for Now)

If Phase 1+2 insufficient, consider:

### BK-Tree Index (Burkhard-Keller Tree)

```java
// Metric tree that efficiently answers "find all strings within distance D"
public class BKTreeClusterer {
    private BKTree<String> tree;
    
    public List<String> cluster(List<String> items) {
        tree = new BKTree<>(new Levenshtein());
        
        for (String item : items) {
            Set<String> nearby = tree.search(item, maxDistance);
            // ... merge with nearby group
        }
        
        return result;
    }
}
```

**Expected**: O(n log n) clustering (vs O(n²) greedy)  
**Effort**: 3-4 weeks  
**Only if**: Phase 1+2 doesn't meet requirements  

---

## Summary

**Phase 2 Strategy**:
1. Combine **2A** (caching) + **2B** (pre-filters)
2. Optionally add **2D** (similarity cache) if profiling shows high repetition
3. Defer **2C** (token grouping) unless benchmark shows inadequate improvement

**Expected improvement**: 2,000-5,000 ms additional savings  
**Effort**: 2-3 weeks including testing  
**Combined Phase 1+2 result**: **6-10x overall performance improvement**

