# Quick Reference Card: BCNESA Practitioner Import Optimization

**Print this for your desk** ☕

---

## Current State
- **Time**: 7-20 seconds ⚠️
- **IOPs**: 500-650 ⚠️
- **Main Issue**: N+1 database queries + quadratic fuzzy matching

---

## Solution Roadmap

### Phase 1 (Next 1-2 weeks)
```
GOAL: Fix N+1 database problem
TIME SAVED: 60-70% (7-20s → 2-5s)
EFFORT: Low (80 code lines)
RISK: 🟢 Low
FILES: BcnesaPracticionerInitialImportService.java + application.yml

WHAT TO DO:
1. Replace findByFullName() loop with bulk findAll()
2. Change save() calls to saveAll() batch
3. Add hibernate.jdbc.batch_size config

VALIDATION:
- DB queries: 500-650 → 2-3 ✓
- DB IOPs: 500-650 → 5-10 ✓
```

### Phase 2 (Optional, 2-3 weeks)
```
GOAL: Optimize fuzzy matching algorithm
TIME SAVED: +30-50% (2-5s → 1-2s)
EFFORT: Medium (150 code lines)
RISK: 🟢 Low-Medium
FILE: PracticionerNameSimilarityService.java

WHAT TO DO:
1. Add normalization caching
2. Add pre-filters (length, prefix checks)
3. Skip expensive similarity for obvious non-matches

VALIDATION:
- Pre-filter skip rate: >70% ✓
- Fuzzy match time: 8s → 2-3s ✓
```

### Phase 3 (Rarely needed, 4-6 weeks)
```
GOAL: Redesign clustering algorithm
TIME SAVED: +30-50% (1-2s → 500-1000ms)
EFFORT: High (complex data structures)
RISK: 🟡 Medium
ONLY IF: Phase 1+2 insufficient or 10K+ practitioners

SKIP UNLESS CRITICAL ⛔
```

---

## Performance Targets

| Phase | Time | IOPs | Status |
|-------|------|------|--------|
| Current | 7-20s | 500-650 | ⚠️ Slow |
| +Phase 1 | **2-5s** | **5-10** | ✅ Good |
| +Phase 2 | **1-2s** | **5-10** | ✅ Excellent |
| +Phase 3 | **500-1000ms** | **2-5** | ⭐ Optimal |

---

## The 6 Bottlenecks

| # | Issue | Impact | Phase |
|---|-------|--------|-------|
| 1 | N+1 DB queries | 60% | 1 |
| 2 | Quadratic fuzzy | 20% | 2 |
| 3 | Memory load | 5% | 3 |
| 4 | No batch insert | 10% | 1 |
| 5 | FS traversal | 3% | 4 |
| 6 | Dup normalize | 2% | 2 |

---

## Phase 1: The Code Change

**Before** (BAD 🔴):
```java
for (String name : names) {
    if (repo.findByFullName(name).isEmpty()) {  // 300 queries
        repo.save(new Practicioner(name));       // 250 inserts
    }
}
// Result: 550 DB round-trips
```

**After** (GOOD 🟢):
```java
Set<String> existing = repo.findAll()  // 1 query
    .stream().map(P::getFullName).collect(toSet());

List<Practicioner> toAdd = names.stream()
    .filter(n -> !existing.contains(n))
    .map(n -> new Practicioner(n))
    .collect(toList());

repo.saveAll(toAdd);  // 5-6 batch inserts
// Result: 2-3 DB round-trips
```

---

## Configuration Change (Phase 1)

**application.yml** add:
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 50
          fetch_size: 100
        order_inserts: true
```

---

## Files to Modify

### Phase 1
- [ ] `tt-data-league-import-bcnesa-csv-adapter/.../BcnesaPracticionerInitialImportService.java` (method)
- [ ] `tt-data-league-import-runtime/src/main/resources/application.yml` (config)
- [ ] `tt-data-league-import-shared/.../PracticionerNameSimilarityService.java` (optional cache)

### Phase 2
- [ ] `tt-data-league-import-shared/.../PracticionerNameSimilarityService.java` (refactor)

---

## Testing Checklist

### Phase 1
- [ ] Build: `mvn clean install`
- [ ] Run import: `mvn -pl runtime spring-boot:run --args "--federation=bcnesa --workflow=practicioners ..."`
- [ ] Measure time: Should be 60-70% faster
- [ ] Count queries: Should be 2-3 (not 500+)
- [ ] Verify data: Same practitioners imported
- [ ] Test idempotency: Run twice, second should be faster

### Phase 2
- [ ] Build: `mvn clean install`
- [ ] Run import: Same command as Phase 1
- [ ] Measure time: Should be 30-50% faster than Phase 1
- [ ] Check pre-filters: Look for "X skipped by pre-filters" logs
- [ ] Verify clustering: Results should be identical to Phase 1

---

## Monitoring Commands

### During Development
```bash
# Measure time
time mvn -pl runtime spring-boot:run \
  --args "--federation=bcnesa --workflow=practicioners ..."

# Count queries (enable in application.yml)
logging:
  level:
    org.hibernate.SQL: DEBUG

# Profile CPU
mvn -pl runtime spring-boot:run \
  -Dspring-boot.run.jvmArguments="-XX:+UnlockDiagnosticVMOptions ..."
```

---

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Batch fails | Try smaller batch size (25 instead of 50) |
| Memory OOM | Pre-load existing names instead (Phase 1 default) |
| Queries wrong | Check logs with `org.hibernate.SQL: DEBUG` |
| Data lost | Validate row count before/after |
| Cache stale | Clear cache at start of batch |

---

## Decision Tree

```
Is import taking >5s? 
├─ YES → Implement Phase 1 (high priority)
│        ├─ After Phase 1, is it <2s?
│        │  ├─ YES → Done! ✅
│        │  └─ NO → Consider Phase 2
│        │
│        └─ After Phase 1+2, is it <1s?
│           ├─ YES → Done! ✅
│           └─ NO → Investigate other issues
│
└─ NO (already <5s) → Skip optimization
```

---

## Timeline Estimate

| Activity | Days | Notes |
|----------|------|-------|
| Read analysis | 0.5 | `EXECUTIVE_SUMMARY.md` |
| Code review | 1 | Understand Phase 1 changes |
| Implementation | 3-5 | Code + testing + validation |
| Benchmarking | 1 | Before/after measurement |
| Phase 2 (opt) | 7-10 | If Phase 1 insufficient |
| **Total Phase 1** | **5-8 days** | **~1 week** |

---

## Success Criteria

### Phase 1 ✅
```
Time: 7-20s → 2-5s (60-70% faster)
DB Queries: 500-650 → 2-3
Practitioners imported: Same as before
Idempotency: Yes (can run multiple times)
```

### Phase 1+2 ✅
```
Time: 2-5s → 1-2s (30-50% faster)
Pre-filter skip rate: >70%
Clustering results: Identical to Phase 1
Memory: Stable
```

---

## ROI Analysis

| Aspect | Value |
|--------|-------|
| Effort (Phase 1) | 1-2 weeks |
| Improvement | 60-70% faster |
| Risk | Low |
| Payback | ~2 weeks (if regular imports) |
| Scalability | 3-4x before re-optimization needed |

---

## One-Pager Summary

```
CURRENT: 7-20 seconds ⚠️
↓ Phase 1 (1-2 weeks)
AFTER:   2-5 seconds ✅
↓ Phase 2 (optional, 2-3 weeks)
BETTER:  1-2 seconds ✅✅

Main fix: Change 500 DB queries to 2-3
Method: Bulk load + batch insert
Config: Add 3 lines to application.yml
Impact: 60-70% faster
Risk: Low
```

---

## Contact Points in Documentation

| Question | Doc | Section |
|----------|-----|---------|
| What's the issue? | EXECUTIVE_SUMMARY | Key Findings |
| Show me visually | VISUAL_REFERENCE | All charts |
| How to fix? | PHASE_1 | Implementation Details |
| Deep dive | PERFORMANCE_ANALYSIS | All sections |
| Risk? | PHASE_1 | Risk Assessment |
| Phase 2? | PHASE_2 | Strategies 2A-2D |

---

## Conversation Starters

**To your manager:**
> "We can make practitioner imports 3-4x faster with Phase 1 (1-2 weeks). It fixes the N+1 database problem. Should I start?"

**To your team:**
> "Let's implement Phase 1. It's a straightforward bulk load + batch insert pattern. Low risk, high ROI."

**To your DBA:**
> "We're adding batch inserts and bulk loads. Can you enable Hibernate batching in the datasource config?"

---

## Red Flags ⛔

🚩 "Just do Phase 2" → NO! Phase 1 prerequisite  
🚩 "Skip testing" → NO! Must validate idempotency  
🚩 "Tune pre-filters without data" → NO! Benchmark first  
🚩 "Do Phase 3 first" → NO! Start with Phase 1  
🚩 "Forget configuration" → NO! Hibernate batching crucial  

---

## Green Lights ✅

✅ "Start with Phase 1" → YES, do it  
✅ "Measure before/after" → YES, mandatory  
✅ "Run twice to verify idempotency" → YES, do it  
✅ "Consider Phase 2 later" → YES, if needed  
✅ "Document lessons learned" → YES, please  

---

## Useful Links in Codebase

```
Repository Root
├─ PERFORMANCE_ANALYSIS_BCNESA_PRACTITIONER_IMPORT.md (deep)
├─ PHASE_1_IMPLEMENTATION_GUIDE.md (implement)
├─ PHASE_2_IMPLEMENTATION_GUIDE.md (optional)
├─ EXECUTIVE_SUMMARY.md (start here)
├─ VISUAL_REFERENCE.md (see charts)
└─ tt-data-league-import-bcnesa-csv-adapter/
   └─ club/service/BcnesaPracticionerInitialImportService.java (modify)
```

---

## TL;DR (Too Long; Didn't Read)

**Problem**: Practitioner import slow (7-20s)  
**Reason**: 500-650 database queries per import (N+1 problem)  
**Solution**: Load all existing practitioners once, batch insert new ones  
**Effort**: 1-2 weeks  
**Result**: 3-4x faster (2-5 seconds)  
**Risk**: Low  

👉 **Next Step**: Read `PHASE_1_IMPLEMENTATION_GUIDE.md` and start coding

---

**Print date**: 2026-04-03  
**Valid while**: Analysis holds (framework/DB version unchanged)  
**Update when**: Major schema/code architecture changes

