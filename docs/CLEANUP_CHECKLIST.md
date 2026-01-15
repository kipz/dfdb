# DFDB Cleanup Checklist

## Completed Refactoring - Items to Clean Up

### High Priority - Delete Now

#### 1. Backup Files (20 files)
**Location**: `src/dfdb/dd/`
```bash
rm src/dfdb/dd/full_pipeline.clj.bak*
rm src/dfdb/dd/full_pipeline.clj.fixjoin
rm src/dfdb/dd/full_pipeline.clj.fixset
rm src/dfdb/dd/full_pipeline.clj.fixloop
rm src/dfdb/dd/full_pipeline.clj.current
rm src/dfdb/dd/full_pipeline.clj.manual
rm src/dfdb/dd/full_pipeline.clj.before_join_fix
```

**Reason**: These are backup files from the refactoring process. The code is now in `compiler.clj` and tests pass.

#### 2. Test Backup Files
**Location**: `test/dfdb/`
```bash
rm test/dfdb/generative_stress_test.clj.bak*
rm test/dfdb/core_test.clj.future
```

**Reason**: Backup files no longer needed.

#### 3. Performance Output Files (1 file in root)
**Location**: Root directory
```bash
rm perf_test_results_20260114_102510.txt
```

**Reason**: Transient test output. Keep in `.gitignore` but don't track.

#### 4. Duplicate Sandbox Directories
**Issue**: Both `sanbox/` (typo) and `sandbox/` exist
```bash
# Review contents first, then delete the typo directory
rm -rf sanbox/  # After confirming nothing important
```

**Reason**: Typo directory, experimental code should only be in `sandbox/`.

---

### Medium Priority - Consider Consolidating

#### 5. Documentation Files (78 files in docs/)
**Location**: `docs/`

**Status Documentation (30+ files with similar names)**:
- `FINAL.md`, `FINAL-STATUS.md`, `FINAL-STATE.md`, `COMPLETE.md`, etc.
- `STATUS.md`, `CURRENT-STATUS.md`, `ACTUAL-STATUS.md`, etc.
- `PROJECT-COMPLETE.md`, `PROJECT-SUMMARY.md`, `PROJECT-STATE.md`, etc.

**Recommendation**:
Create a single `docs/DEVELOPMENT_HISTORY.md` that links to key milestones:
- Phase 1 Complete
- Phase 2 Complete
- Phase 3 Complete
- Performance Testing Results
- Final Refactoring Summary

Keep only:
- `REFACTORING_SUMMARY.md` (current state)
- `recursive_unification_plan.md` (future work)
- `operator_state_formalization_plan.md` (future work)
- `REQUIREMENTS.md` (original requirements)
- One performance report (most recent)

Archive the rest to `docs/archive/` or delete.

#### 6. Deprecated Function in storage.clj

**Function**: `try-require-namespace` in `src/dfdb/storage.clj`

**Status**: No longer used (replaced by factory pattern in `storage/factory.clj`)

**Options**:
1. Delete it entirely (recommended - no external usage)
2. Mark as deprecated with warning
3. Keep for backward compatibility (not needed - internal function)

**Recommendation**: Delete lines 38-67 from `storage.clj`

---

### Low Priority - Future Cleanup

#### 7. Old full_pipeline.clj

**Location**: `src/dfdb/dd/full_pipeline.clj`

**Current State**: Compatibility shim that re-exports from `compiler.clj`

**Options**:
1. Keep as-is (backward compatibility for any external code)
2. Add deprecation warnings
3. Remove after one release cycle

**Recommendation**: Keep for now with deprecation notices (already added).

#### 8. Complex Query Test

**Location**: `test/dfdb/complex_query_combinations_test.clj`

**Status**: Untracked file with 20 new tests (188 total vs 168 original)

**Options**:
1. Add to git and track (recommended - adds good coverage)
2. Delete if not needed
3. Move to integration test suite

**Recommendation**: Add to git - these tests are valuable.

---

## Cleanup Script

```bash
#!/bin/bash
# DFDB Cleanup Script

echo "Cleaning up DFDB after refactoring..."

# 1. Remove backup files
echo "Removing backup files..."
rm -f src/dfdb/dd/full_pipeline.clj.bak*
rm -f src/dfdb/dd/full_pipeline.clj.fix*
rm -f src/dfdb/dd/full_pipeline.clj.current
rm -f src/dfdb/dd/full_pipeline.clj.manual
rm -f src/dfdb/dd/full_pipeline.clj.before_join_fix
rm -f test/dfdb/generative_stress_test.clj.bak*
rm -f test/dfdb/core_test.clj.future

# 2. Remove performance output
echo "Removing transient performance output..."
rm -f perf_test_results_*.txt
rm -f perf_*.txt
rm -f test_*.txt
rm -f join_*.txt

# 3. Check sanbox directory
if [ -d "sanbox" ]; then
    echo "Found 'sanbox' directory (typo). Contents:"
    ls -la sanbox/
    echo "Review contents and run: rm -rf sanbox/"
fi

# 4. Consolidate docs
echo "Documentation cleanup..."
mkdir -p docs/archive
echo "Review and move old status files to docs/archive/"
echo "Keep only: REFACTORING_SUMMARY.md, *_plan.md, REQUIREMENTS.md"

echo "Cleanup complete! Review changes and commit."
```

---

## What to Keep

### Core Documentation
- ✅ `docs/REFACTORING_SUMMARY.md` - Current architecture state
- ✅ `docs/recursive_unification_plan.md` - Future work
- ✅ `docs/operator_state_formalization_plan.md` - Future work
- ✅ `docs/REQUIREMENTS.md` - Original requirements

### Test Files
- ✅ All test files in `test/dfdb/` (add complex_query_combinations_test.clj to git)

### Source Code
- ✅ All production code in `src/dfdb/`
- ✅ Keep `full_pipeline.clj` as compatibility shim
- ❌ Remove `try-require-namespace` from `storage.clj`

---

## Post-Cleanup Validation

After cleanup:
1. Run tests: `./run-tests.sh`
2. Verify all 188 tests pass
3. Check git status: `git status`
4. Commit cleaned code: `git add` relevant files
5. Create summary commit

---

## Summary

**Can Delete Immediately**: ~25 backup/temp files
**Should Consolidate**: 70+ redundant doc files
**Should Remove**: 1 deprecated function
**Should Track**: 1 test file

**Estimated Cleanup Time**: 15 minutes
**Risk Level**: Low (tests validate correctness)
