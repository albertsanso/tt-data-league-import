# Performance Analysis: BCNESA Practitioner Import Use Case

**Analysis Date**: 2026-04-03  
**Scope**: `bcnesaPracticionerInitialImportService.processPracticionersForSeason(baseFolder, season)`  
**Focus**: IOPs minimization and execution time reduction  

---

## Executive Summary

The BCNESA practitioner import workflow has **multiple critical performance bottlenecks** centered around:
- **Full in-memory materialization** of all CSV rows before processing
- **Repeated, inefficient database lookups** (N+1 query problem) during deduplication
- **Expensive fuzzy-matching operations** applied to every practitioner name pair
- **Inefficient file-system traversal** with multiple redundant directory scans
- **Lack of batch persistence** leading to individual INSERT statements

These issues compound to create high IOPs and slow execution times, especially as data volumes scale.

---

## Current Implementation Flow

```
processPracticionersForSeason(baseFolder, season)
  │
  ├─> resetAndLoadTextFilesForSeason()
  │   └─> Scans filesystem hierarchy to queue CSV files
  │       [SCAN 1: Season folders]
  │       [SCAN 2: Competition type folders]
  │       [SCAN 3: Competition folders]
  │       [SCAN 4: Individual CSV files]
  │
  ├─> importPracticioners()
  │   └─> fetchCsvRowInfos()  [MEMORY: Materialize ALL rows into ArrayList]
  │
  └─> savePracticionersInfo(rows)
      └─> extractPracticionersNames(rows)
          ├─> Map each row → extract 2 player names (local + visitor)
          ├─> Flatten + distinct stream operations
          └─> PracticionerNameSimilarityService.reduceToSimilarClustersOfNames()
              ├─> FOR EACH practitioner name
              │   ├─> FOR EACH existing root (clustered group)
              │   │   └─> NameSimilarity.similarity(root, name)  [EXPENSIVE!]
              │   └─> Save to best cluster
              │
              └─> [RESULT: List of representative names]
      
      └─> FOR EACH distinct name in result
          ├─> DB Query: practicionerRepository.findByFullName(name)  [DB IOP]
          └─> If not found → practicionerRepository.save(new Practicioner)  [DB IOP]
```

---

## Detailed Performance Bottlenecks

### 1. **Full In-Memory Materialization** (Critical)

**Location**: `LineByLineInitialImportService.fetchCsvRowInfos()`

```java
protected List<FileRowInfo> fetchCsvRowInfos() {
    List<FileRowInfo> matchResultsDetailCsvFileRowInfoList = new ArrayList<>();
    while (matchResultDetailsByLineIterator.hasNext()) {
        FileRowInfo rowInfo = matchResultDetailsByLineIterator.next();
        matchResultsDetailCsvFileRowInfoList.add(rowInfo);  // ← ALL rows in memory
    }
    return matchResultsDetailCsvFileRowInfoList;
}
```

**Problem**:
- All CSV rows are loaded into memory **before any processing or deduplication** occurs
- For a large season with thousands of matches, this can be hundreds of thousands of row objects
- Each row carries full match metadata, creating significant heap pressure

**Impact**: O(n) memory consumption, potential GC pressure, OOM on large datasets

**Estimated IOPs**: Not direct IOPs, but affects cache locality and GC behavior

---

### 2. **N+1 Database Query Problem** (Critical)

**Location**: `BcnesaPracticionerInitialImportService.savePracticionersInfo()`

```java
practicionersNamesList.forEach(practicionerName -> {
    if (practicionerRepository.findByFullName(practicionerName).isEmpty()) {  // ← LOOKUP
        practicionerRepository.save(practicionerToCreate);  // ← INSERT
    }
});
```

**Problem**:
- For each distinct practitioner name, there is **1 SELECT query**
- If the name doesn't exist, there is **1 INSERT query**
- No batching: every operation is an individual round-trip

**Example**: 
- 500 distinct practitioner names → 500 SELECT queries + ~450 INSERT queries = **~950 database IOPs**

**Impact**: 
- Direct database overhead (network latency, connection pool contention)
- Serialized I/O (waits for previous query before issuing next)

**Estimated IOPs**: 1-2 IOPs per distinct practitioner (worst case = O(n))

---

### 3. **Quadratic-Time Fuzzy Matching Algorithm** (High)

**Location**: `PracticionerNameSimilarityService.reduceToSimilarClustersOfNames()`

```java
for (String practicionerName : items) {  // ← Outer loop: O(n)
    for (String existingRoot : groups.keySet()) {  // ← Inner loop: O(m) where m = clusters formed so far
        double similarity = NameSimilarity.similarity(existingRoot, practicionerName);  // ← EXPENSIVE
    }
}
```

**Problem**:
- Algorithm is **O(n·m)** where n = total names, m = clusters formed
- Each `NameSimilarity.similarity()` call performs token normalization + Levenshtein distance
- String normalization (accent removal, lowercasing) happens repeatedly
- For 500+ distinct names, this is **potentially 125,000+ similarity calculations**

**NameSimilarity internals**:
```java
// From NameSimilarity
// This normalizes strings, tokenizes, then performs fuzzy matching
// Normalization: O(name_length)
// Tokenization: O(tokens)
// Fuzzy match: O(|tokens_a| × |tokens_b| × max_token_length)
```

**Impact**: CPU-bound, high latency per name

**Estimated cost per name comparison**: 10-100 µs depending on name length

---

### 4. **Redundant Filesystem Traversal** (Medium)

**Location**: `BcnesaMatchResultDetailsByLineIterator.processMatchesDetailsForSeason()` and subfunctions

```java
csvRepositoryFinderService.findAllSeasonsFoldersFrom(baseFolder)  // ← SCAN 1
    .filter(seasonFolderInfo -> seasonFolderInfo.season().equals(seasonRange))
    .forEach(this::processSeasonFolder);

// Inside processSeasonFolder:
csvRepositoryFinderService.findCompetitionTypeFoldersFrom(seasonFolder)  // ← SCAN 2

// Inside processCompetitionTypeFolder:
csvRepositoryFinderService.findCompetitionFoldersFrom(competitionTypeFolder)  // ← SCAN 3

// Inside processCompetitionFolder:
Files.list(competitionFolder)  // ← SCAN 4 for each competition
    .forEach(csvFile -> { ... });
```

**Problem**:
- Multiple sequential directory list operations
- Each `Files.list()` and directory filtering incurs I/O
- Pattern matching on filenames repeated per file

**Impact**: Moderate IOP overhead during file discovery phase

**Estimated IOPs**: 10-50 IOPs for filesystem traversal (depends on folder depth/breadth)

---

### 5. **No Batch Insert** (Medium)

**Location**: `BcnesaPracticionerInitialImportService.savePracticionersInfo()`

```java
practicionersNamesList.forEach(practicionerName -> {
    // ... create practitioner ...
    practicionerRepository.save(practicionerToCreate);  // ← Single insert per row
});
```

**Problem**:
- Spring Data JPA defaults to single-row `INSERT` statements
- No use of batch insert (multi-row `INSERT` or prepared statement batching)
- Each save incurs separate DB round-trip

**Impact**: 
- Serialized database writes
- Missing database-level batch optimization

**Estimated IOPs**: 2-5x worse than batch operations

---

### 6. **Stream Overhead & Distinct Pass** (Low-Medium)

**Location**: `BcnesaPracticionerInitialImportService.extractPracticionersNames()`

```java
return PracticionerNameSimilarityService.reduceToSimilarClustersOfNames(
    fedespMatchResultsDetailCsvFileRowInfos.stream()
        .map(rowInfo -> {
            // Extract 2 names per row
            return List.of(localPracticionerName, visitorPracticionerName);
        })
        .flatMap(List::stream)
        .distinct()  // ← Hash-based deduplication (good)
        .toList()
);
```

**Problem**:
- Multiple stream operations incur overhead
- `distinct()` requires hash-based tracking
- Not a primary bottleneck but contributes to GC pressure

**Impact**: Low (10-50 ms on typical data)

---

## Root Cause Analysis

### Why These Bottlenecks Exist

1. **Shared abstraction was designed for correctness, not performance**
   - `LineByLineInitialImportService` chose full materialization for simplicity
   - Template method pattern doesn't easily support streaming processing

2. **Fuzzy matching algorithm is greedy without optimization**
   - No caching of normalized strings
   - No pre-filtering before expensive similarity computation
   - Doesn't leverage indexed structures (e.g., Trie, BK-tree)

3. **No coordination with database layer**
   - No batch insert support in service layer
   - No prepared statement batching configured
   - Lookups could be optimized with IN queries or bloom filters

4. **Idempotency was prioritized over performance**
   - Design requires checking existence before every save
   - No bulk upsert pattern

---

## Performance Impact Estimation

### Baseline Scenario
- **Input**: BCNESA season with 50 match files, ~500 matches total
- **Practitioner Names Extracted**: ~600 raw names, ~400 after distinct, ~300 after fuzzy clustering
- **Expected Practitioners to Persist**: ~250 new (rest already in DB)

### Execution Time Breakdown

| Phase | Current | Estimated Time | Dominant Cost |
|-------|---------|-----------------|----------------|
| **Filesystem traversal** | Sequential | 50-200 ms | IOPs + metadata |
| **CSV parsing & materialization** | Full load | 200-500 ms | Memory allocation, stream ops |
| **Fuzzy matching clustering** | Quadratic | 5,000-15,000 ms | Similarity calculations (O(n²)) |
| **Database lookups** | N+1 | 1,000-3,000 ms | 300-400 SELECT queries |
| **Database inserts** | Single-row | 500-1,500 ms | 250 individual INSERT statements |
| **Overhead** | Various | 200-500 ms | GC, stream overhead |
| **TOTAL** | | **~7-20 seconds** | Fuzzy matching + DB queries |

### IOP Breakdown

| Category | Count | Cost |
|----------|-------|------|
| Filesystem list operations | 10-20 | ~50 IOPs |
| CSV file reads | 50 | ~50 IOPs (sequential) |
| Database SELECT (lookups) | 250-400 | **250-400 IOPs** |
| Database INSERT (writes) | 250 | **250 IOPs** |
| **Total Database IOPs** | | **~500-650 IOPs** |

---

## Optimization Strategies (Ranked by Impact)

### **Priority 1: Eliminate N+1 Database Problem** (60-70% improvement)

#### Strategy 1a: Bulk Insert with "Insert or Ignore"
Use SQL `INSERT ... ON CONFLICT DO NOTHING` (PostgreSQL) or equivalent.

**Before**:
```java
for (String name : 250 names) {
    SELECT (1 IOP) + INSERT (1 IOP if new) = ~250-500 IOPs
}
```

**After**:
```java
List<Practicioner> toInsert = names.stream()
    .map(name -> Practicioner.createNew(name, name, name, new Date()))
    .toList();
INSERT ALL toInsert ON CONFLICT DO NOTHING (1-2 IOPs)
```

**Benefit**: 250-500 IOPs → ~2 IOPs = **250x reduction**

**Implementation Effort**: Medium (requires custom repository method or SQL)

---

#### Strategy 1b: Pre-load Existing Practitioners
Load all existing practitioners into memory before processing.

**Before**:
```java
for (String name : names) {
    practicionerRepository.findByFullName(name)  // DB query
}
```

**After**:
```java
Set<String> existing = practicionerRepository.findAll()
    .stream()
    .map(Practicioner::getFullName)
    .collect(toSet());
    
for (String name : names) {
    if (!existing.contains(name)) {
        toInsert.add(name);
    }
}
```

**Benefit**: 250-400 SELECT queries → 1 SELECT (bulk) = **250x reduction**

**Implementation Effort**: Low (simple refactor)

---

### **Priority 2: Optimize Fuzzy Matching** (30-50% improvement)

#### Strategy 2a: Cache Normalized Names
Pre-compute normalized versions to avoid repeated normalization.

**Before**:
```java
for (String name1 : names) {
    for (String name2 : clusteredNames) {
        similarity(name1, name2)  // Normalizes both inside
    }
}
```

**After**:
```java
Map<String, NormalizedName> normalized = names.stream()
    .collect(toMap(
        identity(),
        name -> new NormalizedName(name)  // Normalize once
    ));

for (String name : names) {
    for (String clusteredName : clusteredNames) {
        similarity(normalized.get(name), normalized.get(clusteredName))
    }
}
```

**Benefit**: 500+ duplicate normalizations eliminated = **50-100 ms saved**

**Implementation Effort**: Low

---

#### Strategy 2b: Pre-filter Before Expensive Similarity Check
Use cheap, approximate filters before invoking expensive similarity.

**Before**:
```java
for (String name1 : names) {
    for (String name2 : clusteredNames) {
        if (NameSimilarity.similarity(name1, name2) >= threshold) {  // Always computed
            cluster.add(name1);
        }
    }
}
```

**After**:
```java
for (String name1 : names) {
    for (String name2 : clusteredNames) {
        // Fast pre-filters
        if (lengthDifference(name1, name2) > maxLengthDiff) continue;
        if (commonPrefixLength(name1, name2) < minCommonPrefix) continue;
        
        // Only then compute expensive similarity
        if (NameSimilarity.similarity(name1, name2) >= threshold) {
            cluster.add(name1);
        }
    }
}
```

**Benefit**: Skip 30-50% of expensive similarity calls = **2,000-5,000 ms saved**

**Implementation Effort**: Medium

---

#### Strategy 2c: Replace Quadratic Algorithm with Indexed Structure (BK-Tree)
Use a Burkhard-Keller tree or similar metric tree to prune search space.

**Before**:
```
For each new name, compare against ALL clustered names: O(n²)
```

**After**:
```
BK-Tree indexed on normalized names
For each new name, query tree with radius threshold: O(log n + k)
where k = matches within radius
```

**Benefit**: O(n²) → O(n log n) = **10-50x speedup on large datasets**

**Implementation Effort**: High (requires new data structure)

---

### **Priority 3: Streaming Processing** (20-30% improvement)

#### Strategy 3a: Process Names In Batches Instead of Full Materialization
Modify `fetchCsvRowInfos()` to support callback-based processing.

**Before**:
```java
List<Row> allRows = fetchCsvRowInfos();  // Load everything
extractAndCluster(allRows);
```

**After**:
```java
processCsvRowInfos(rowBatch -> {
    extractAndCluster(rowBatch);  // Process in 1000-row chunks
});
```

**Benefit**: 
- Reduced peak memory from O(n) to O(batch_size)
- Better GC behavior
- Earlier cache eviction

**Estimated Saving**: 200-400 ms + reduced GC pause times

**Implementation Effort**: High (requires API redesign)

---

### **Priority 4: Batch Database Inserts** (10-20% improvement)

#### Strategy 4a: Use JDBC Batch Insert
Configure Spring Data JPA batch size or use native JDBC.

**Before**:
```java
for (Practicioner p : toInsert) {
    save(p);  // Individual INSERT
}
```

**After**:
```java
saveAll(toInsert);  // Batch INSERT with hibernate.jdbc.batch_size=50
```

**Benefit**: 50 rows per batch = ~5x fewer network round-trips = **100-300 ms saved**

**Implementation Effort**: Low (configuration)

---

### **Priority 5: Parallel Filesystem Traversal** (5-10% improvement)

#### Strategy 5a: Parallel Stream for File Discovery
Use `Files.list()` parallelStream where safe.

**Benefit**: 50-100 ms saved on filesystem operations (depends on I/O speed)

**Implementation Effort**: Low

**Caveat**: Minimal impact compared to other strategies.

---

## Recommended Optimization Plan

### **Phase 1 (Quick Win): 60-70% improvement, 1-2 weeks**
1. **Implement bulk insert** (Strategy 1a)
2. **Pre-load existing practitioners** (Strategy 1b)
3. **Configure JDBC batch size** (Strategy 4a)

**Expected Result**: 7-20 seconds → **2-5 seconds**

### **Phase 2 (Medium Effort): +30-50% improvement, 2-3 weeks**
1. **Cache normalized names** (Strategy 2a)
2. **Add pre-filters to similarity check** (Strategy 2b)

**Expected Result**: 2-5 seconds → **1-2 seconds**

### **Phase 3 (Advanced): +30-50% improvement, 4-6 weeks**
1. **Implement BK-Tree or Trie-based deduplication** (Strategy 2c)
2. **Refactor to streaming batch processing** (Strategy 3a)

**Expected Result**: 1-2 seconds → **500-1000 ms** (or less depending on data)

---

## Implementation Details for Phase 1

### 1. Bulk Insert Implementation

**Option A: Native SQL (Most Efficient)**

```java
// In PracticionerRepository (core-repository-jpa)
@Query(value = """
    INSERT INTO practicioner (full_name, short_name, alias_name, created_date)
    SELECT :fullName, :shortName, :aliasName, :createdDate
    WHERE NOT EXISTS (
        SELECT 1 FROM practicioner WHERE full_name = :fullName
    )
    """, nativeQuery = true)
void insertIfNotExists(String fullName, String shortName, String aliasName, Date createdDate);

// Or use JPA Bulk API
@Modifying
@Query("INSERT INTO Practicioner p (fullName, shortName, aliasName, createdDate) " +
       "VALUES (:fullName, :shortName, :aliasName, :createdDate) " +
       "WHERE NOT EXISTS (SELECT 1 FROM Practicioner WHERE fullName = :fullName)")
void insertIfNotExists(String fullName, String shortName, String aliasName, Date createdDate);
```

**Option B: Spring Data JPA (Simpler, Less Efficient)**

```java
public void savePracticionersInfo(List<String> practicionersNamesList) {
    Set<String> existing = practicionerRepository.findAll()
        .stream()
        .map(Practicioner::getFullName)
        .collect(Collectors.toSet());
    
    List<Practicioner> toInsert = practicionersNamesList.stream()
        .filter(name -> !existing.contains(name))
        .map(name -> Practicioner.createNew(name, name, name, new Date()))
        .toList();
    
    practicionerRepository.saveAll(toInsert);  // Batch insert
}
```

### 2. Hibernate JDBC Batch Configuration

Add to `application.yml`:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          fetch_size: 100
        order_inserts: true
        order_updates: true
```

### 3. Pre-load Existing Practitioners

Replace individual lookups with bulk fetch before processing.

---

## Monitoring & Validation

### Metrics to Track

1. **Execution Time**
   - Total wall-clock time
   - Per-phase time breakdown (FS, clustering, DB)
   
2. **Database IOPs**
   - Number of SELECT queries
   - Number of INSERT queries
   - Average query latency
   
3. **Memory Usage**
   - Peak heap usage
   - GC pause time
   - Object allocation rate

4. **CPU Usage**
   - CPU time in fuzzy matching
   - CPU time in normalization

### Testing Approach

1. **Benchmark current implementation**
   ```bash
   # Run with profiler
   mvn -pl tt-data-league-import-runtime spring-boot:run \
     -Dspring-boot.run.jvmArguments="-XX:+UnlockDiagnosticVMOptions -XX:+TraceClassLoading"
   ```

2. **Run with representative data**
   - Use actual season dataset
   - Measure before/after for each optimization

3. **Database query logging**
   ```yaml
   logging:
     level:
       org.hibernate.SQL: DEBUG
       org.hibernate.type.descriptor.sql.BasicBinder: TRACE
   ```

4. **JProfiler or YourKit analysis**
   - CPU profiling for hot spots
   - Memory profiling for allocation patterns
   - Lock contention analysis

---

## Summary Table

| Issue | Root Cause | Impact | Priority | Estimated Savings |
|-------|-----------|--------|----------|------------------|
| N+1 Database | Individual lookups + inserts | 250-650 IOPs | **P1** | 60-70% time |
| Fuzzy Matching Quadratic | O(n²) similarity comparisons | 5-15 seconds | **P2** | 30-50% time |
| Memory Materialization | Full load before processing | GC pressure, OOM risk | **P2** | 200-400 ms |
| No Batch Insert | Single-row INSERTs | 5x network overhead | **P1** | 10-20% time |
| Redundant Normalization | Per-comparison normalization | CPU overhead | **P2** | 50-100 ms |
| Filesystem Traversal | Sequential list operations | 50-200 ms | **P4** | 5-10% time |

---

## Next Steps

1. **Create benchmark test** with representative BCNESA data
2. **Implement Phase 1 optimizations** (bulk insert, pre-load, batch config)
3. **Measure improvement** with database metrics
4. **Plan Phase 2** if Phase 1 doesn't meet target
5. **Consider Phase 3** only if performance is still insufficient or scalability is critical

