package chronicle.db.service;

import static chronicle.db.service.ChronicleDb.CHRONICLE_DB;
import static chronicle.db.service.MapDb.MAP_DB;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.mapdb.DBException;
import org.mapdb.DBMaker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import chronicle.db.dao.ChronicleDao;
import chronicle.db.entity.PutStatus;
import chronicle.db.entity.Search;
import chronicle.db.entity.Search.SearchType;

/**
 * Comprehensive tests for ChronicleDao methods.
 * Tests both single-file (no keymap) and multi-file (with keymap) scenarios.
 */
@SuppressWarnings("unchecked")
@Timeout(120)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ChronicleDaoTest {
    private static final String DAO = "chronicle.db.service.LeadDao";
    @TempDir
    static Path suiteDirectory;
    private static String SINGLE_FILE_PATH;
    private static String MULTI_FILE_PATH;

    private static ChronicleDao<Lead> singleFileDao;
    private static ChronicleDao<Lead> multiFileDao;

    @BeforeAll
    static void setup() throws Throwable {
        SINGLE_FILE_PATH = suiteDirectory.resolve("single").toString() + "/";
        MULTI_FILE_PATH = suiteDirectory.resolve("multi").toString() + "/";
        VACUUM_PATH = suiteDirectory.resolve("vacuum").toString() + "/";
        singleFileDao = CHRONICLE_DB.getChronicleDao(DAO, SINGLE_FILE_PATH);
        multiFileDao = CHRONICLE_DB.getChronicleDao(DAO, MULTI_FILE_PATH);
    }

    private static Lead createLead(final String name, final String email) {
        return new Lead(name, "linkedin-" + name, "fb-" + name, "twitter-" + name,
                email, "012-" + name, "Engineer", "Location-" + name, new ArrayList<>());
    }

    // ==================== SINGLE FILE DAO TESTS ====================

    @Test
    @Order(1)
    void singleFile_insertAndGet() {
        final String key = "single-test-1";
        final Lead lead = createLead("SingleTest1", "single1@test.com");

        singleFileDao.put(key, lead);

        final Lead retrieved = singleFileDao.get(key);
        assertNotNull(retrieved);
        assertEquals("SingleTest1", retrieved.fullName);
        assertEquals("single1@test.com", retrieved.email);
    }

    @Test
    @Order(2)
    void singleFile_size() {
        // Ensure idempotent across runs: drop the key if it was inserted by a
        // prior run, otherwise put() upserts and size stays unchanged.
        singleFileDao.delete("single-size-test");
        final int initialSize = singleFileDao.size();

        singleFileDao.put("single-size-test", createLead("SizeTest", "size@test.com"));

        assertEquals(initialSize + 1, singleFileDao.size());
    }

    @Test
    @Order(3)
    void singleFile_exists() {
        final String existingKey = "single-exists-test";
        singleFileDao.put(existingKey, createLead("ExistsTest", "exists@test.com"));

        assertTrue(singleFileDao.exists(existingKey));
        assertFalse(singleFileDao.exists("non-existent-key-12345"));
    }

    @Test
    @Order(4)
    void singleFile_existsCollection() {
        final List<String> insertedKeys = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final String key = "single-exists-coll-" + i;
            singleFileDao.put(key, createLead("ExistsColl" + i, "ec" + i + "@test.com"));
            insertedKeys.add(key);
        }

        final List<String> testKeys = new ArrayList<>(insertedKeys);
        testKeys.add("non-existent-1");
        testKeys.add("non-existent-2");

        final Map<String, Boolean> result = singleFileDao.exists(testKeys);

        assertEquals(testKeys.size(), result.size());
        for (final String key : insertedKeys) {
            assertTrue(result.get(key), "Key should exist: " + key);
        }
        assertFalse(result.get("non-existent-1"));
        assertFalse(result.get("non-existent-2"));
    }

    @Test
    @Order(5)
    void singleFile_existsList() {
        final List<String> insertedKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String key = "single-existslist-" + i;
            singleFileDao.put(key, createLead("ExistsList" + i, "el" + i + "@test.com"));
            insertedKeys.add(key);
        }

        final List<String> testKeys = new ArrayList<>(insertedKeys);
        testKeys.add("fake-key-1");
        testKeys.add("fake-key-2");

        final List<String> existingKeys = singleFileDao.existsList(testKeys);

        assertEquals(insertedKeys.size(), existingKeys.size());
        assertTrue(existingKeys.containsAll(insertedKeys));
    }

    @Test
    @Order(6)
    void singleFile_notExists() {
        final List<String> insertedKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String key = "single-notexists-" + i;
            singleFileDao.put(key, createLead("NotExists" + i, "ne" + i + "@test.com"));
            insertedKeys.add(key);
        }

        final List<String> nonExistentKeys = List.of("missing-1", "missing-2", "missing-3");
        final List<String> testKeys = new ArrayList<>(insertedKeys);
        testKeys.addAll(nonExistentKeys);

        final Set<String> notExisting = singleFileDao.notExists(testKeys);

        assertEquals(nonExistentKeys.size(), notExisting.size());
        assertTrue(notExisting.containsAll(nonExistentKeys));
        for (final String key : insertedKeys) {
            assertFalse(notExisting.contains(key));
        }
    }

    @Test
    @Order(7)
    void singleFile_notExistsList() {
        final List<String> insertedKeys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String key = "single-notexistslist-" + i;
            singleFileDao.put(key, createLead("NotExistsList" + i, "nel" + i + "@test.com"));
            insertedKeys.add(key);
        }

        final List<String> nonExistentKeys = List.of("absent-1", "absent-2");
        final List<String> testKeys = new ArrayList<>(insertedKeys);
        testKeys.addAll(nonExistentKeys);

        final List<String> notExisting = singleFileDao.notExistsList(testKeys);

        assertEquals(nonExistentKeys.size(), notExisting.size());
        assertTrue(notExisting.containsAll(nonExistentKeys));
    }

    @Test
    @Order(8)
    void singleFile_delete() {
        final String key = "single-delete-test";
        singleFileDao.put(key, createLead("DeleteTest", "delete@test.com"));

        assertTrue(singleFileDao.exists(key));

        final boolean deleted = singleFileDao.delete(key);

        assertTrue(deleted);
        assertFalse(singleFileDao.exists(key));
        assertNull(singleFileDao.get(key));
    }

    @Test
    @Order(9)
    void singleFile_batchGet() throws InterruptedException {
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String key = "single-batch-get-" + i;
            singleFileDao.put(key, createLead("BatchGet" + i, "bg" + i + "@test.com"));
            keys.add(key);
        }

        final Map<String, Lead> results = singleFileDao.get(keys);

        assertEquals(keys.size(), results.size());
        for (final String key : keys) {
            assertNotNull(results.get(key));
        }
    }

    @Test
    @Order(10)
    void singleFile_batchGetWithLimit() throws InterruptedException {
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String key = "single-batch-limit-" + i;
            singleFileDao.put(key, createLead("BatchLimit" + i, "bl" + i + "@test.com"));
            keys.add(key);
        }

        final Map<String, Lead> results = singleFileDao.get(keys, 5);

        assertEquals(5, results.size());
    }

    @Test
    @Order(11)
    void singleFile_search() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            singleFileDao.put("single-search-" + i, createLead("SearchableLead" + i, "search" + i + "@test.com"));
        }

        final Search search = new Search("fullName", SearchType.STARTS_WITH, "SearchableLead");
        final Map<String, Lead> results = singleFileDao.search(search);

        assertTrue(results.size() >= 5);
        for (final Lead lead : results.values()) {
            assertTrue(lead.fullName.startsWith("SearchableLead"));
        }
    }

    @Test
    @Order(12)
    void singleFile_searchKeys() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            singleFileDao.put("single-searchkeys-" + i, createLead("KeySearchTest" + i, "ks" + i + "@test.com"));
        }

        final Search search = new Search("fullName", SearchType.STARTS_WITH, "KeySearchTest");
        final Set<String> keys = singleFileDao.searchKeys(List.of(search));

        assertTrue(keys.size() >= 3);
    }

    // ==================== MULTI FILE DAO TESTS ====================

    @Test
    @Order(20)
    void multiFile_insertMany() throws InterruptedException {
        final Map<String, Lead> batch = new HashMap<>();
        for (int i = 0; i < 500; i++) {
            batch.put("multi-insert-" + i, createLead("MultiInsert" + i, "mi" + i + "@test.com"));
        }

        multiFileDao.put(batch);

        assertTrue(multiFileDao.size() >= 500);
    }

    @Test
    @Order(21)
    void multiFile_getAcrossRecords() throws InterruptedException {
        final List<String> keys = IntStream.range(0, 100)
                .mapToObj(i -> "multi-insert-" + i)
                .collect(Collectors.toList());

        final Map<String, Lead> results = multiFileDao.get(keys);

        assertEquals(100, results.size());
    }

    @Test
    @Order(22)
    void multiFile_exists() {
        assertTrue(multiFileDao.exists("multi-insert-0"));
        assertTrue(multiFileDao.exists("multi-insert-50"));
        assertTrue(multiFileDao.exists("multi-insert-200"));
        assertFalse(multiFileDao.exists("non-existent-multi"));
    }

    @Test
    @Order(23)
    void multiFile_existsCollection() {
        final List<String> existingKeys = List.of("multi-insert-0", "multi-insert-50", "multi-insert-150");
        final List<String> nonExistingKeys = List.of("fake-multi-1", "fake-multi-2");

        final List<String> allKeys = new ArrayList<>(existingKeys);
        allKeys.addAll(nonExistingKeys);

        final Map<String, Boolean> result = multiFileDao.exists(allKeys);

        for (final String key : existingKeys) {
            assertTrue(result.get(key), "Key should exist: " + key);
        }
        for (final String key : nonExistingKeys) {
            assertFalse(result.get(key), "Key should not exist: " + key);
        }
    }

    @Test
    @Order(24)
    void multiFile_notExists() {
        final List<String> existingKeys = List.of("multi-insert-10", "multi-insert-20");
        final List<String> nonExistingKeys = List.of("ghost-1", "ghost-2", "ghost-3");

        final List<String> allKeys = new ArrayList<>(existingKeys);
        allKeys.addAll(nonExistingKeys);

        final Set<String> notExisting = multiFileDao.notExists(allKeys);

        assertEquals(nonExistingKeys.size(), notExisting.size());
        assertTrue(notExisting.containsAll(nonExistingKeys));
    }

    @Test
    @Order(25)
    void multiFile_indexedSearch() {
        final Search search = new Search("fullName", SearchType.STARTS_WITH, "MultiInsert1");
        final Map<String, Lead> results = multiFileDao.indexedSearch(search);

        assertTrue(results.size() > 0);
        for (final Lead lead : results.values()) {
            assertTrue(lead.fullName.startsWith("MultiInsert1"));
        }
    }

    @Test
    @Order(26)
    void multiFile_indexedSearchKeys() {
        final Search search = new Search("fullName", SearchType.EQUAL, "MultiInsert50");
        final Set<String> keys = multiFileDao.indexedSearchKeys(search);

        assertEquals(1, keys.size());
        assertTrue(keys.contains("multi-insert-50"));
    }

    @Test
    @Order(27)
    void multiFile_deleteMultiple() {
        final List<String> keysToDelete = List.of(
                "multi-insert-5",
                "multi-insert-105",
                "multi-insert-205");

        final int sizeBefore = multiFileDao.size();
        final boolean deleted = multiFileDao.delete(keysToDelete);

        assertTrue(deleted);
        assertEquals(sizeBefore - 3, multiFileDao.size());

        for (final String key : keysToDelete) {
            assertFalse(multiFileDao.exists(key));
        }
    }

    @Test
    @Order(28)
    void multiFile_size() {
        // Ensure idempotent across runs (see singleFile_size).
        multiFileDao.delete("multi-size-check");
        final int size = multiFileDao.size();
        assertTrue(size > 0);

        multiFileDao.put("multi-size-check", createLead("SizeCheck", "sizecheck@test.com"));
        assertEquals(size + 1, multiFileDao.size());
    }

    // ==================== EDGE CASES ====================

    @Test
    @Order(40)
    void edgeCase_getNonExistentKey() {
        assertNull(singleFileDao.get("this-key-definitely-does-not-exist"));
    }

    @Test
    @Order(41)
    void edgeCase_deleteNonExistentKey() {
        final boolean result = singleFileDao.delete("another-non-existent-key");
        assertFalse(result);
    }

    @Test
    @Order(42)
    void edgeCase_existsEmptyCollection() {
        final Map<String, Boolean> result = singleFileDao.exists(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(43)
    void edgeCase_notExistsEmptyCollection() {
        final Set<String> result = singleFileDao.notExists(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(44)
    void edgeCase_searchWithLimit() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            singleFileDao.put("limit-test-" + i, createLead("LimitTest" + i, "lt" + i + "@test.com"));
        }

        final Search search = new Search("fullName", SearchType.STARTS_WITH, "LimitTest", 5);
        final Map<String, Lead> results = singleFileDao.search(search);

        assertEquals(5, results.size());
    }

    @Test
    @Order(45)
    void edgeCase_multiSearchWithMultipleCriteria() throws InterruptedException {
        final String uniquePrefix = "MultiCrit" + System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            singleFileDao.put("multicrit-" + i, createLead(uniquePrefix + i, "mc" + i + "@test.com"));
        }

        final List<Search> searches = List.of(
                new Search("fullName", SearchType.STARTS_WITH, uniquePrefix),
                new Search("email", SearchType.STARTS_WITH, "mc"));

        final Map<String, Lead> results = singleFileDao.multiSearch(searches);

        assertEquals(10, results.size());
    }

    @Test
    @Order(46)
    void edgeCase_getSubset() throws InterruptedException {
        final String key = "subset-test-key";
        singleFileDao.put(key, createLead("SubsetLead", "subset@test.com"));

        final Map<String, Map<String, Object>> result = singleFileDao.getSubset(
                List.of(key),
                new String[] { "fullName", "email" });

        assertEquals(1, result.size());
        final Map<String, Object> fields = result.get(key);
        assertEquals("SubsetLead", fields.get("fullName"));
        assertEquals("subset@test.com", fields.get("email"));
        assertFalse(fields.containsKey("linkedin"));
    }

    @Test
    @Order(47)
    void edgeCase_updateExisting() {
        final String key = "update-test-key";
        singleFileDao.put(key, createLead("OriginalName", "original@test.com"));

        final Lead original = singleFileDao.get(key);
        assertEquals("OriginalName", original.fullName);

        singleFileDao.put(key, createLead("UpdatedName", "updated@test.com"));

        final Lead updated = singleFileDao.get(key);
        assertEquals("UpdatedName", updated.fullName);
        assertEquals("updated@test.com", updated.email);
    }

    @Test
    @Order(48)
    void edgeCase_batchGetWithExclusions() throws InterruptedException {
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String key = "exclusion-test-" + i;
            singleFileDao.put(key, createLead("ExclusionTest" + i, "ex" + i + "@test.com"));
            keys.add(key);
        }

        final Set<String> excludedKeys = Set.of("exclusion-test-0", "exclusion-test-5", "exclusion-test-9");

        System.out.println("=== Exclusion Test Debug ===");
        System.out.println("Total keys to query: " + keys.size() + " -> " + keys);
        System.out.println("Excluded keys: " + excludedKeys);

        final Map<String, Lead> results = singleFileDao.get(keys, excludedKeys, 100);

        System.out.println("Results count: " + results.size());
        System.out.println("Returned keys: " + results.keySet());
        System.out.println("Missing from results (should be excluded): ");
        for (final String key : keys) {
            if (!results.containsKey(key)) {
                System.out.println("  - " + key + (excludedKeys.contains(key) ? " (EXCLUDED)" : " (UNEXPECTED!)"));
            }
        }
        System.out.println("=== End Debug ===");

        assertEquals(7, results.size());
        for (final String excluded : excludedKeys) {
            assertFalse(results.containsKey(excluded));
        }
    }

    @Test
    @Order(481)
    void edgeCase_multiSearchWithExclusions() throws InterruptedException {
        final String prefix = "MultiExclude" + System.currentTimeMillis();
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final String key = "multi-exclude-" + prefix + "-" + i;
            singleFileDao.put(key, createLead(prefix + i, "me" + i + "@test.com"));
            keys.add(key);
        }

        final Set<String> excludedKeys = Set.of(
                "multi-exclude-" + prefix + "-0",
                "multi-exclude-" + prefix + "-5",
                "multi-exclude-" + prefix + "-9");

        final List<Search> searches = List.of(new Search("fullName", SearchType.STARTS_WITH, prefix));

        System.out.println("=== MultiSearch Exclusion Test Debug ===");
        System.out.println("Total records created: " + keys.size());
        System.out.println("Excluded keys: " + excludedKeys);

        final Map<String, Lead> results = singleFileDao.multiSearch(searches, excludedKeys);

        System.out.println("Results count: " + results.size());
        System.out.println("Returned keys: " + results.keySet());
        System.out.println("Missing from results (should be excluded): ");
        for (final String key : keys) {
            if (!results.containsKey(key)) {
                System.out.println("  - " + key + (excludedKeys.contains(key) ? " (EXCLUDED)" : " (UNEXPECTED!)"));
            }
        }
        System.out.println("=== End Debug ===");

        assertEquals(7, results.size());
        for (final String excluded : excludedKeys) {
            assertFalse(results.containsKey(excluded), "Excluded key should not be in results: " + excluded);
        }
    }

    @Test
    @Order(49)
    void edgeCase_multiSearchCount() throws InterruptedException {
        final String prefix = "CountTest" + System.currentTimeMillis();
        for (int i = 0; i < 15; i++) {
            singleFileDao.put("count-" + prefix + "-" + i, createLead(prefix + i, "ct" + i + "@test.com"));
        }

        final List<Search> searches = List.of(new Search("fullName", SearchType.STARTS_WITH, prefix));
        final long count = singleFileDao.multiSearchCount(searches);

        assertEquals(15, count);
    }

    // ==================== SEARCH TYPE TESTS ====================

    @Test
    @Order(60)
    void searchType_equal() throws InterruptedException {
        final String uniqueName = "EqualSearch" + System.currentTimeMillis();
        singleFileDao.put("equal-search-key", createLead(uniqueName, "equal@test.com"));

        final Search search = new Search("fullName", SearchType.EQUAL, uniqueName);
        final Map<String, Lead> results = singleFileDao.search(search);

        assertEquals(1, results.size());
        assertEquals(uniqueName, results.values().iterator().next().fullName);
    }

    @Test
    @Order(61)
    void searchType_like() throws InterruptedException {
        final String uniquePart = "LikeTest" + System.currentTimeMillis();
        singleFileDao.put("like-key-1", createLead("Prefix" + uniquePart + "Suffix", "like1@test.com"));
        singleFileDao.put("like-key-2", createLead("Another" + uniquePart + "End", "like2@test.com"));

        final Search search = new Search("fullName", SearchType.LIKE, uniquePart);
        final Map<String, Lead> results = singleFileDao.search(search);

        assertEquals(2, results.size());
    }

    @Test
    @Order(62)
    void searchType_endsWith() throws InterruptedException {
        final String uniqueSuffix = "EndsWith" + System.currentTimeMillis();
        singleFileDao.put("ends-key-1", createLead("Lead" + uniqueSuffix, "ends1@test.com"));
        singleFileDao.put("ends-key-2", createLead("Other" + uniqueSuffix, "ends2@test.com"));

        final Search search = new Search("fullName", SearchType.ENDS_WITH, uniqueSuffix);
        final Map<String, Lead> results = singleFileDao.search(search);

        assertEquals(2, results.size());
    }

    @Test
    @Order(63)
    void searchType_in() throws InterruptedException {
        final String prefix = "InSearch" + System.currentTimeMillis();
        final List<String> names = List.of(prefix + "A", prefix + "B", prefix + "C");

        for (int i = 0; i < names.size(); i++) {
            singleFileDao.put("in-search-" + prefix + "-" + i, createLead(names.get(i), "in" + i + "@test.com"));
        }

        final Search search = new Search("fullName", SearchType.IN, names);
        final Map<String, Lead> results = singleFileDao.search(search);

        assertEquals(3, results.size());
    }

    @Test
    @Order(64)
    void searchType_notIn() throws InterruptedException {
        final String prefix = "NotIn" + System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            singleFileDao.put("notin-" + prefix + "-" + i, createLead(prefix + i, "notin" + i + "@test.com"));
        }

        final List<String> excluded = List.of(prefix + "0", prefix + "1");

        final List<Search> searches = List.of(
                new Search("fullName", SearchType.STARTS_WITH, prefix),
                new Search("fullName", SearchType.NOT_IN, excluded));

        final Map<String, Lead> results = singleFileDao.multiSearch(searches);

        assertEquals(3, results.size());
    }

    // ==================== CSV OUTPUT TESTS ====================

    @Test
    @Order(70)
    void csvOutput_multiSearchCsv() throws InterruptedException {
        final String prefix = "CsvTest" + System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            singleFileDao.put("csv-" + prefix + "-" + i, createLead(prefix + i, "csv" + i + "@test.com"));
        }

        final List<Search> searches = List.of(new Search("fullName", SearchType.STARTS_WITH, prefix));
        final var csvResult = singleFileDao.multiSearchCsv(searches);

        assertNotNull(csvResult.headers());
        assertEquals(5, csvResult.rows().size());
    }

    @Test
    @Order(71)
    void csvOutput_getSubsetCsv() {
        final String prefix = "SubsetCsv" + System.currentTimeMillis();
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final String key = "subsetcsv-" + prefix + "-" + i;
            singleFileDao.put(key, createLead(prefix + i, "scsv" + i + "@test.com"));
            keys.add(key);
        }

        final var csvResult = singleFileDao.getSubsetCsv(keys, new String[] { "fullName", "email" });

        assertNotNull(csvResult.headers());
        assertEquals(3, csvResult.rows().size());
        assertTrue(csvResult.headers().length <= 3);
    }

    // ==================== INSERT/UPDATE SPECIFIC TESTS ====================

    @Test
    @Order(80)
    void insertUpdate_insertNew() {
        final String key = "insert-new-" + System.currentTimeMillis();

        final var result = singleFileDao.insert(key, createLead("InsertNew", "insertnew@test.com"));

        assertEquals(PutStatus.INSERTED, result);
        assertNotNull(singleFileDao.get(key));
    }

    @Test
    @Order(81)
    void insertUpdate_insertExistingFails() {
        final String key = "insert-existing-" + System.currentTimeMillis();
        singleFileDao.put(key, createLead("First", "first@test.com"));

        final var result = singleFileDao.insert(key, createLead("Second", "second@test.com"));

        assertEquals(PutStatus.FAILED, result);
        assertEquals("First", singleFileDao.get(key).fullName);
    }

    @Test
    @Order(82)
    void insertUpdate_updateExisting() {
        final String key = "update-existing-" + System.currentTimeMillis();
        singleFileDao.put(key, createLead("Original", "original@test.com"));

        final var result = singleFileDao.update(key, createLead("Updated", "updated@test.com"));

        assertEquals(PutStatus.UPDATED, result);
        assertEquals("Updated", singleFileDao.get(key).fullName);
    }

    @Test
    @Order(83)
    void insertUpdate_updateNonExistingFails() {
        final String key = "update-nonexistent-" + System.currentTimeMillis();

        final var result = singleFileDao.update(key, createLead("ShouldFail", "fail@test.com"));

        assertEquals(PutStatus.FAILED, result);
        assertNull(singleFileDao.get(key));
    }

    // ==================== VACUUM ====================

    private static final String VACUUM_DAO = "chronicle.db.service.VacuumTestDao";
    private static String VACUUM_PATH;

    private static void deleteDirRecursive(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (final var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (final IOException ignored) {
                }
            });
        }
    }

    /**
     * Vacuum scenario: 5 rotated files, then deletes half of data-2 (sparse),
     * half of data-3 (sparse), and 5% of data-4 (dense). Verifies:
     * <ul>
     * <li>data-2 and data-3 records get repacked into one new file at slot 2
     * (lowest sparse slot, since max-N=5 is dense and untouched)</li>
     * <li>data-3 slot becomes a gap</li>
     * <li>dense files (data, data-4, data-5) keep their original suffixes</li>
     * <li>all surviving records remain retrievable by key</li>
     * <li>deleted records stay deleted</li>
     * <li>indexed search still finds moved records (indexes weren't rebuilt
     * but remain valid because record values didn't change)</li>
     * </ul>
     */
    @Test
    @Order(90)
    void vacuum_partialCompaction() throws Throwable {
        // Fresh data dir so test is fully reproducible.
        deleteDirRecursive(Path.of(VACUUM_PATH));

        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(VACUUM_DAO, VACUUM_PATH);

        // Insert exactly 500 records (entries() = 100 → produces 5 rotated files).
        // Batched so rotation happens during insert, like production traffic.
        for (int batchStart = 0; batchStart < 500; batchStart += 50) {
            final Map<String, Lead> batch = new HashMap<>();
            for (int i = batchStart + 1; i <= batchStart + 50; i++) {
                batch.put("vac-" + i, createLead("Vac" + i, "vac" + i + "@test.com"));
            }
            dao.put(batch);
        }
        assertEquals(500, dao.size());
        final var preFiles = dao.getDataFileState().fileNames();
        assertEquals(5, preFiles.size(), "Expected 5 rotated files; got " + preFiles);
        assertTrue(preFiles.containsAll(Set.of("data", "data-2", "data-3", "data-4", "data-5")));

        // Delete to produce: data unchanged (dense), data-2 50% (sparse),
        // data-3 50% (sparse), data-4 95% (dense), data-5 unchanged (dense).
        // Record-to-file mapping is deterministic because put(Map) inserts
        // sequentially across the rotation boundary: vac-1..100 → data,
        // vac-101..200 → data-2, vac-201..300 → data-3, vac-301..400 → data-4,
        // vac-401..500 → data-5. We delete from each file's key range.
        for (int i = 101; i <= 150; i++) {
            dao.delete("vac-" + i);
        }
        for (int i = 201; i <= 250; i++) {
            dao.delete("vac-" + i);
        }
        for (int i = 301; i <= 305; i++) {
            dao.delete("vac-" + i);
        }
        assertEquals(500 - 50 - 50 - 5, dao.size());

        // Run vacuum.
        dao.vacuum();

        // Post-vacuum: data (dense, untouched), data-2 (new packed of 100
        // sparse survivors), data-4 (dense, untouched), data-5 (dense,
        // untouched). data-3 slot becomes a gap. So 4 files total.
        final var postFiles = dao.getDataFileState().fileNames();
        assertEquals(4, postFiles.size(), "Expected 4 files after vacuum; got " + postFiles);
        assertTrue(postFiles.contains("data"));
        assertTrue(postFiles.contains("data-2"));
        assertFalse(postFiles.contains("data-3"), "data-3 should be a gap");
        assertTrue(postFiles.contains("data-4"));
        assertTrue(postFiles.contains("data-5"));

        // Total size unchanged after vacuum.
        assertEquals(500 - 50 - 50 - 5, dao.size());

        // All surviving records retrievable.
        for (int i = 1; i <= 100; i++) {
            assertNotNull(dao.get("vac-" + i), "vac-" + i + " should be retrievable (was in data)");
        }
        for (int i = 151; i <= 200; i++) {
            assertNotNull(dao.get("vac-" + i), "vac-" + i + " should be retrievable (moved from data-2)");
        }
        for (int i = 251; i <= 300; i++) {
            assertNotNull(dao.get("vac-" + i), "vac-" + i + " should be retrievable (moved from data-3)");
        }
        for (int i = 306; i <= 400; i++) {
            assertNotNull(dao.get("vac-" + i), "vac-" + i + " should be retrievable (was in data-4)");
        }
        for (int i = 401; i <= 500; i++) {
            assertNotNull(dao.get("vac-" + i), "vac-" + i + " should be retrievable (was in data-5)");
        }

        // Deleted records remain deleted.
        for (int i = 101; i <= 150; i++) {
            assertNull(dao.get("vac-" + i), "vac-" + i + " should still be deleted");
        }
        for (int i = 201; i <= 250; i++) {
            assertNull(dao.get("vac-" + i), "vac-" + i + " should still be deleted");
        }
        for (int i = 301; i <= 305; i++) {
            assertNull(dao.get("vac-" + i), "vac-" + i + " should still be deleted");
        }

        // Indexed search still works for a record that was moved across files
        // (validates that the index entries — never rebuilt during vacuum —
        // remain correct because the record value didn't change).
        final var movedKeyResults = dao.multiSearch(
                List.of(new Search("fullName", SearchType.EQUAL, "Vac180")));
        assertTrue(movedKeyResults.containsKey("vac-180"),
                "Indexed search should find moved record 'vac-180'");
    }

    // ==================== CRASH SAFETY (TMP/MARKER) ====================

    @Test
    @Order(100)
    void crashSafe_staleKeyMapTmp_cleanedAndRebuiltOnStartup() throws Throwable {
        final var path = suiteDirectory.resolve("crash-km-stale").toString() + "/";
        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(DAO, path);
        dao.put("km-1", createLead("KmOne", "km1@test.com"));

        final var keyMapPath = dao.getKeyMapPath();
        final var tmpPath = keyMapPath + ChronicleDao.TMP_SUFFIX;

        // Simulate interrupted rebuild: close keymap, delete it, leave stale .tmp
        MAP_DB.closeMap(keyMapPath);
        Files.deleteIfExists(Path.of(keyMapPath));
        Files.writeString(Path.of(tmpPath), "stale");

        assertTrue(Files.exists(Path.of(tmpPath)));
        assertFalse(Files.exists(Path.of(keyMapPath)));

        // New startup: createDataDirs() must clean .tmp and rebuild keys
        CHRONICLE_DB.getChronicleDao(DAO, path);

        assertFalse(Files.exists(Path.of(tmpPath)), "stale keys.tmp should be deleted on startup");
        assertTrue(Files.exists(Path.of(keyMapPath)), "keymap should be rebuilt after stale tmp cleanup");
    }

    @Test
    @Order(101)
    void crashSafe_successfulKeyMapBuild_leavesNoTmp() throws Throwable {
        final var path = suiteDirectory.resolve("crash-km-clean").toString() + "/";
        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(DAO, path);
        dao.put("km-2", createLead("KmTwo", "km2@test.com"));

        assertTrue(Files.exists(Path.of(dao.getKeyMapPath())), "keys should exist after build");
        assertFalse(Files.exists(Path.of(dao.getKeyMapPath() + ChronicleDao.TMP_SUFFIX)),
                "keys.tmp should not remain after a successful keymap build");
    }

    @Test
    @Order(102)
    void crashSafe_refreshKeyMap_leavesNoTmp() throws Throwable {
        final var path = suiteDirectory.resolve("crash-km-refresh").toString() + "/";
        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(DAO, path);
        dao.put("km-3", createLead("KmThree", "km3@test.com"));

        dao.refreshKeyMap();

        assertTrue(Files.exists(Path.of(dao.getKeyMapPath())), "keys should exist after refresh");
        assertFalse(Files.exists(Path.of(dao.getKeyMapPath() + ChronicleDao.TMP_SUFFIX)),
                "keys.tmp should not remain after refreshKeyMap completes");
    }

    @Test
    @Order(103)
    void crashSafe_rebuildingMarker_indexWipedAndRebuiltOnStartup() throws Throwable {
        final var path = suiteDirectory.resolve("crash-idx-marker").toString() + "/";
        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(DAO, path);
        dao.put("idx-1", createLead("IdxOne", "idx1@test.com"));

        final var indexPath = dao.getIndexPath("fullName");
        final var markerPath = dao.dataPath() + ChronicleDao.INDEX_DIR + ChronicleDao.INDEX_REBUILDING_MARKER;

        assertTrue(Files.exists(Path.of(indexPath)), "index should exist after initial build");
        assertFalse(Files.exists(Path.of(markerPath)), "no marker should exist after clean build");

        // Simulate interrupted rebuild: leave marker alongside existing index
        Files.writeString(Path.of(markerPath), "");

        // New startup: initDefaultIndexes() must detect marker, wipe index, and rebuild
        CHRONICLE_DB.getChronicleDao(DAO, path);

        assertFalse(Files.exists(Path.of(markerPath)), "rebuilding marker should be gone after startup recovery");
        assertTrue(Files.exists(Path.of(indexPath)), "index should be rebuilt after marker-triggered wipe");
    }

    @Test
    @Order(104)
    void crashSafe_successfulIndexBuild_leavesNoMarker() throws Throwable {
        final var path = suiteDirectory.resolve("crash-idx-clean").toString() + "/";
        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(DAO, path);
        dao.put("idx-2", createLead("IdxTwo", "idx2@test.com"));

        final var markerPath = dao.dataPath() + ChronicleDao.INDEX_DIR + ChronicleDao.INDEX_REBUILDING_MARKER;
        assertFalse(Files.exists(Path.of(markerPath)),
                "rebuilding marker should not exist after a successful index build");
    }

    @Test
    @Order(105)
    void crashSafe_recoverAllData_allFilesIntactAfterRecovery() throws Throwable {
        final var path = suiteDirectory.resolve("crash-recover").toString() + "/";
        final ChronicleDao<Lead> dao = CHRONICLE_DB.getChronicleDao(DAO, path);
        dao.put("rec-1", createLead("RecOne", "rec1@test.com"));
        dao.put("rec-2", createLead("RecTwo", "rec2@test.com"));

        final int sizeBefore = dao.size();

        // Close the open map so recoverDb can open the file in recovery mode
        CHRONICLE_DB.close(dao.dataPath() + ChronicleDao.DATA_DIR + "data");

        dao.recoverAllData();

        assertEquals(sizeBefore, dao.size(), "all records should still be present after recoverAllData");
        assertNotNull(dao.get("rec-1"), "rec-1 should be accessible after recovery");
        assertNotNull(dao.get("rec-2"), "rec-2 should be accessible after recovery");
    }

    /**
     * Child-JVM fixture: halt deliberately bypasses shutdown hooks to reproduce
     * an unclean store shutdown or interruption before atomic publication.
     * Only invoked with paths supplied by @TempDir tests.
     */
    public static class RecoveryProcess {
        private static boolean stopDuringScan;

        public static void main(final String[] args) {
            if ("dirty-index".equals(args[0])) {
                final var index = MAP_DB.openIndex(args[1]);
                index.index.add(MAP_DB.createIndexKey("uncommitted", new byte[16]));
                Runtime.getRuntime().halt(23);
            } else if ("dirty-primary".equals(args[0])) {
                // Die while iterating: the entry's segment stays locked in the mmap'd file.
                final var dao = new VacuumTestDao(args[1]);
                dao.openDb().forEachEntry(entry -> Runtime.getRuntime().halt(23));
                throw new AssertionError("No entry was available to hold a segment lock");
            } else if ("dirty-keymap".equals(args[0])) {
                final var map = MAP_DB.openMap(args[1]);
                map.put(new byte[16], new MapDb.KeyMapValue("uncommitted", "data"));
                Runtime.getRuntime().halt(23);
            } else {
                final var dao = new VacuumTestDao(args[1]) {
                    @Override
                    public ChronicleDb.SharedChronicleMap<String, Lead> openDb(final String file) {
                        if (stopDuringScan) {
                            Runtime.getRuntime().halt(23);
                        }
                        return super.openDb(file);
                    }
                };
                stopDuringScan = true;
                if ("interrupt-keymap".equals(args[0])) {
                    dao.size();
                } else {
                    dao.indexedSearch(new Search("fullName", SearchType.EQUAL, "Alice"));
                }
                throw new AssertionError("Recovery never reached the primary scan");
            }
        }
    }

    @Nested
    class AutomaticRecovery {
        @TempDir
        Path directory;

        private RecoveryDao seededDao() {
            final var dao = new RecoveryDao(directory.toString() + "/");
            dao.put("one", createLead("Alice", "alice@test.com"));
            dao.put("two", createLead("Bob", "bob@test.com"));
            return dao;
        }

        private class RecoveryDao extends VacuumTestDao {
            volatile boolean failScan;
            volatile String failFile;
            final AtomicInteger scans = new AtomicInteger();

            RecoveryDao(final String path) {
                super(path);
            }

            @Override
            public long recoveryLockWaitSeconds() {
                return 1; // production waits 30s; keep the busy-writer test fast
            }

            @Override
            public ChronicleDb.SharedChronicleMap<String, Lead> openDb(final String file) {
                if (failScan || file.equals(failFile)) {
                    throw new IllegalStateException("Injected primary scan failure");
                }
                // Constructor calls can arrive before field initialization.
                if (scans != null) {
                    scans.incrementAndGet();
                }
                return super.openDb(file);
            }
        }

        private void breakHeaderChecksum(final Path path) throws Exception {
            // MapDB 3.1.0 stores the header checksum at offset 20.
            try (final var file = new RandomAccessFile(path.toFile(), "rw")) {
                file.seek(20);
                final int checksum = file.readInt();
                file.seek(20);
                file.writeInt(checksum ^ 1);
            }
            final var error = assertThrows(DBException.DataCorruption.class,
                    () -> DBMaker.fileDB(path.toFile()).make());
            assertTrue(error.getMessage().contains("Header checksum broken"));
        }

        private void runCrashProcess(final String mode, final String path) throws Exception {
            final var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(arg -> arg.startsWith("--add-") || arg.startsWith("--enable-native-access")
                            || arg.startsWith("--sun-misc-unsafe-memory-access"))
                    .forEach(command::add);
            command.add("-cp");
            command.add(System.getProperty("chronicle.test.classpath"));
            command.add(RecoveryProcess.class.getName());
            command.add(mode);
            command.add(path);
            final var output = directory.resolve(mode + ".log");
            final var process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "child JVM did not finish");
                assertEquals(23, process.exitValue(), () -> {
                    try {
                        return Files.readString(output);
                    } catch (final IOException e) {
                        return e.toString();
                    }
                });
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        }

        @Test
        void indexedSearch_abruptJvmExit_reproducesChecksumFailureAndRecovers() throws Exception {
            final var dao = seededDao();
            final var path = Path.of(dao.getIndexPath("fullName"));
            runCrashProcess("dirty-index", path.toString());
            final var failure = assertThrows(DBException.DataCorruption.class,
                    () -> DBMaker.fileDB(path.toFile()).make());
            assertTrue(failure.getMessage().contains("Header checksum broken"));
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
            assertEquals(2, dao.size());
        }

        @Test
        void size_abruptJvmExit_reproducesChecksumFailureAndRecovers() throws Exception {
            final var dao = seededDao();
            final var path = Path.of(dao.getKeyMapPath());
            runCrashProcess("dirty-keymap", path.toString());
            final var failure = assertThrows(DBException.DataCorruption.class,
                    () -> DBMaker.fileDB(path.toFile()).make());
            assertTrue(failure.getMessage().contains("Header checksum broken"));
            assertEquals(2, dao.size());
        }

        @Test
        void indexedSearch_interruptedRecovery_nextDaoRebuildsCompleteIndex() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            runCrashProcess("interrupt-index", directory.toString() + "/");
            final var restarted = new VacuumTestDao(directory.toString() + "/");
            assertEquals(Set.of("one"), restarted.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
            assertEquals(Set.of("two"), restarted.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Bob")).keySet());
        }

        @Test
        void size_interruptedRecovery_nextDaoRebuildsCompleteKeyMap() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            runCrashProcess("interrupt-keymap", directory.toString() + "/");
            final var restarted = new VacuumTestDao(directory.toString() + "/");
            assertEquals(2, restarted.size());
            assertEquals(Set.of("one"), restarted.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
        }

        @Test
        void size_secondShardScanFails_doesNotPublishPartialKeyMap() throws Exception {
            final var dao = seededDao();
            final var values = new HashMap<String, Lead>();
            for (int i = 0; i < 120; i++) {
                values.put("record-" + i, createLead("Person" + i, "p" + i + "@test.com"));
            }
            dao.put(values);
            final var path = Path.of(dao.getKeyMapPath());
            breakHeaderChecksum(path);
            final var corrupt = directory.resolve("keymap-snapshot");
            Files.copy(path, corrupt);
            dao.failFile = "data-2";
            assertThrows(IllegalStateException.class, dao::size);
            assertEquals(-1, Files.mismatch(corrupt, path));
            dao.failFile = null;
            assertEquals(122, dao.size());
            assertEquals(values.keySet(), dao.get(values.keySet()).keySet());
        }

        @Test
        void size_concurrentRecovery_allCallersSeeCompleteKeyMap() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            final int before = dao.scans.get();
            final var start = new CountDownLatch(1);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
                for (int i = 0; i < 12; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return dao.size();
                    }));
                }
                start.countDown();
                for (final var future : futures) {
                    assertEquals(2, future.get(15, TimeUnit.SECONDS));
                }
            }
            assertEquals(1, dao.scans.get() - before, "concurrent callers should share one rebuild");
        }

        @Test
        void indexedSearch_writerBusy_failsPromptlyAndRecoversAfterWriterFinishes() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            final var lock = ChronicleDao.WRITE_LOCKS.get(dao.dataPath());
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                lock.lock();
                try {
                    final var result = executor.submit(() -> assertThrows(IllegalStateException.class,
                            () -> dao.indexedSearch(new Search("fullName", SearchType.EQUAL, "Alice"))));
                    assertTrue(result.get(5, TimeUnit.SECONDS).getMessage().contains("retry request"));
                } finally {
                    lock.unlock();
                }
            }
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
        }

        @Test
        void put_firstRecords_indexIsPopulatedBeforeSearch() {
            final var dao = seededDao();
            assertTrue(Files.isRegularFile(Path.of(dao.getIndexPath("fullName"))));
            final int before = dao.scans.get();
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
            assertEquals(1, dao.scans.get() - before, "lookup must not need an index rebuild");
        }

        @Test
        void put_corruptIndex_recoversBeforeReportingInsertSuccess() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            assertEquals(PutStatus.INSERTED, dao.put("three", createLead("Charlie", "c@test.com")));
            final int before = dao.scans.get();
            assertEquals(Set.of("three"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Charlie")).keySet());
            assertEquals(1, dao.scans.get() - before, "put must already have repaired the index");
        }

        @Test
        void put_corruptIndex_recoversBeforeReportingUpdateSuccess() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            assertEquals(PutStatus.UPDATED, dao.put("one", createLead("Changed", "a@test.com")));
            final int before = dao.scans.get();
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Changed")).keySet());
            assertEquals(1, dao.scans.get() - before);
            assertTrue(dao.indexedSearch(new Search("fullName", SearchType.EQUAL, "Alice")).isEmpty());
        }

        @Test
        void put_corruptIndex_recoversBeforeReportingBatchSuccess() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            dao.put(Map.of("three", createLead("Charlie", "c@test.com"),
                    "one", createLead("Changed", "a@test.com")));
            final int before = dao.scans.get();
            assertEquals(Set.of("three"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Charlie")).keySet());
            assertEquals(1, dao.scans.get() - before);
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Changed")).keySet());
        }

        // ---- write paths against a corrupt keymap (the 01:32 "values do not exist" symptom) ----

        private ArrayList<Lead> leads(final String... names) {
            final var result = new ArrayList<Lead>();
            for (final var name : names) {
                result.add(createLead(name, name.toLowerCase() + "@test.com"));
            }
            return result;
        }

        private Set<String> keysFor(final ChronicleDao<Lead> dao, final String field, final String value) {
            return dao.indexedSearch(new Search(field, SearchType.EQUAL, value)).keySet();
        }

        @Test
        void update_corruptKeyMap_updatesInsteadOfReportingMissingKeys() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            final var status = dao.update(Map.of(
                    "one", createLead("Changed1", "c1@test.com"),
                    "two", createLead("Changed2", "c2@test.com")));
            assertEquals(PutStatus.UPDATED, status);
            assertEquals("Changed1", dao.get("one").fullName);
            assertEquals("Changed2", dao.get("two").fullName);
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Changed1"));
            assertTrue(keysFor(dao, "fullName", "Alice").isEmpty());
            assertEquals(2, dao.size());
        }

        @Test
        void update_singleKey_corruptKeyMap_updates() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            assertEquals(PutStatus.UPDATED, dao.update("one", createLead("Changed", "c@test.com")));
            assertEquals("Changed", dao.get("one").fullName);
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Changed"));
            assertEquals(2, dao.size());
        }

        @Test
        void put_existingKey_corruptKeyMap_updatesInPlace() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            assertEquals(PutStatus.UPDATED, dao.put("one", createLead("Changed", "c@test.com")));
            assertEquals(2, dao.size());
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Changed"));
        }

        @Test
        void put_newKey_corruptKeyMap_insertsAndKeepsExistingKeys() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            assertEquals(PutStatus.INSERTED, dao.put("three", createLead("Charlie", "c@test.com")));
            assertEquals(3, dao.size());
            assertEquals(Set.of("one", "two", "three"), dao.get(Set.of("one", "two", "three")).keySet());
            assertEquals(PutStatus.UPDATED, dao.put("one", createLead("Alice2", "a2@test.com")),
                    "existing key must still be recognised as existing after the rebuild");
        }

        @Test
        void put_batch_corruptKeyMap_insertsAndUpdates() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            dao.put(Map.of("three", createLead("Charlie", "c@test.com"),
                    "one", createLead("Changed", "a@test.com")));
            assertEquals(3, dao.size());
            assertEquals(Set.of("three"), keysFor(dao, "fullName", "Charlie"));
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Changed"));
        }

        @Test
        void delete_corruptKeyMap_deletesOnlyRequestedKey() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            assertTrue(dao.delete("one"));
            assertNull(dao.get("one"));
            assertNotNull(dao.get("two"));
            assertEquals(1, dao.size());
            assertTrue(keysFor(dao, "fullName", "Alice").isEmpty());
            assertEquals(Set.of("two"), keysFor(dao, "fullName", "Bob"));
        }

        @Test
        void delete_batch_corruptKeyMap_deletesOnlyRequestedKeys() throws Exception {
            final var dao = seededDao();
            dao.put("three", createLead("Charlie", "c@test.com"));
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            assertTrue(dao.delete(List.of("one", "three")));
            assertEquals(1, dao.size());
            assertEquals(Set.of("two"), keysFor(dao, "fullName", "Bob"));
            assertTrue(keysFor(dao, "fullName", "Charlie").isEmpty());
        }

        @Test
        void delete_corruptIndex_removesRecordFromRebuiltIndex() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            assertTrue(dao.delete("one"));
            assertTrue(keysFor(dao, "fullName", "Alice").isEmpty());
            assertEquals(Set.of("two"), keysFor(dao, "fullName", "Bob"));
            assertEquals(1, dao.size());
        }

        @Test
        void delete_batch_corruptIndex_removesRecordsFromRebuiltIndex() throws Exception {
            final var dao = seededDao();
            dao.put("three", createLead("Charlie", "c@test.com"));
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            assertTrue(dao.delete(List.of("one", "two")));
            assertTrue(keysFor(dao, "fullName", "Alice").isEmpty());
            assertTrue(keysFor(dao, "fullName", "Bob").isEmpty());
            assertEquals(Set.of("three"), keysFor(dao, "fullName", "Charlie"));
        }

        @Test
        void put_keyMapAndIndexBothCorrupt_recoversBothBeforeReportingSuccess() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            assertEquals(PutStatus.INSERTED, dao.put("three", createLead("Charlie", "c@test.com")));
            assertEquals(3, dao.size());
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Alice"));
            assertEquals(Set.of("three"), keysFor(dao, "fullName", "Charlie"));
        }

        // ---- several indexes ----

        private class TwoIndexDao extends RecoveryDao {
            TwoIndexDao(final String path) {
                super(path);
            }

            @Override
            public Set<String> indexFileNames() {
                return Set.of("fullName", "email");
            }
        }

        private TwoIndexDao seededTwoIndexDao() {
            final var dao = new TwoIndexDao(directory.toString() + "/");
            dao.put("one", createLead("Alice", "alice@test.com"));
            dao.put("two", createLead("Bob", "bob@test.com"));
            return dao;
        }

        @Test
        void multipleIndexes_bothMissingAtStartup_areBuiltTogether() throws Exception {
            final var dao = seededTwoIndexDao();
            Files.delete(Path.of(dao.getIndexPath("fullName")));
            Files.delete(Path.of(dao.getIndexPath("email")));
            final var restarted = new TwoIndexDao(directory.toString() + "/");
            assertTrue(Files.isRegularFile(Path.of(restarted.getIndexPath("fullName"))));
            assertTrue(Files.isRegularFile(Path.of(restarted.getIndexPath("email"))));
            assertEquals(Set.of("one"), keysFor(restarted, "fullName", "Alice"));
            assertEquals(Set.of("two"), keysFor(restarted, "email", "bob@test.com"));
            assertFalse(Files.exists(Path.of(restarted.dataPath() + ChronicleDao.RECOVERY_STAGING_DIR)
                    .resolve("anything")), "staging must not be left behind");
        }

        /** Streamed, because index files are preallocated to hundreds of MB. */
        private byte[] sha256(final Path file) throws Exception {
            final var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (final var in = Files.newInputStream(file)) {
                final var buffer = new byte[1 << 20];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        }

        @Test
        void multipleIndexes_oneCorrupt_rebuildsItAndLeavesTheHealthyOneUntouched() throws Exception {
            final var dao = seededTwoIndexDao();
            final var healthy = Path.of(dao.getIndexPath("email"));
            final var before = sha256(healthy);
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Alice"));
            assertEquals(Set.of("two"), keysFor(dao, "email", "bob@test.com"));
            assertTrue(java.util.Arrays.equals(before, sha256(healthy)),
                    "recovering one index must not rewrite the healthy one");
        }

        @Test
        void put_multipleIndexesBothCorrupt_recoversBothAndIndexesNewRecord() throws Exception {
            final var dao = seededTwoIndexDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            breakHeaderChecksum(Path.of(dao.getIndexPath("email")));
            assertEquals(PutStatus.INSERTED, dao.put("three", createLead("Charlie", "charlie@test.com")));
            assertEquals(Set.of("one"), keysFor(dao, "fullName", "Alice"));
            assertEquals(Set.of("three"), keysFor(dao, "fullName", "Charlie"));
            assertEquals(Set.of("two"), keysFor(dao, "email", "bob@test.com"));
            assertEquals(Set.of("three"), keysFor(dao, "email", "charlie@test.com"));
        }

        @Test
        void recovery_manyDataFiles_rebuildsEveryRecordInKeyMapAndIndexes() throws Exception {
            final var dao = new TwoIndexDao(directory.toString() + "/");
            final var values = new HashMap<String, Lead>();
            for (int i = 0; i < 250; i++) {
                values.put("record-" + i, createLead("Person" + i, "p" + i + "@test.com"));
            }
            dao.put(values);
            assertTrue(dao.getDataFileState().fileNames().size() > 1, "test needs several rotated data files");
            breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            breakHeaderChecksum(Path.of(dao.getIndexPath("email")));

            assertEquals(250, dao.size());
            assertEquals(values.keySet(), dao.get(values.keySet()).keySet());
            for (final int i : new int[] { 0, 99, 100, 101, 199, 200, 249 }) {
                assertEquals(Set.of("record-" + i), keysFor(dao, "fullName", "Person" + i));
                assertEquals(Set.of("record-" + i), keysFor(dao, "email", "p" + i + "@test.com"));
            }
        }

        // ---- primary data: segment lock left behind by a dead process ----

        @Test
        void primaryData_segmentLockedByDeadProcess_recoversInProcessWithoutLosingRecords() throws Exception {
            final var dao = seededDao();
            final var values = new HashMap<String, Lead>();
            for (int i = 0; i < 60; i++) {
                values.put("record-" + i, createLead("Person" + i, "p" + i + "@test.com"));
            }
            dao.put(values);
            final int expected = 62;

            runCrashProcess("dirty-primary", directory.toString() + "/");

            // Every write must succeed: whichever hits the abandoned segment triggers
            // ChronicleDb's in-place deadlock recovery, the rest proceed normally.
            for (final var key : values.keySet()) {
                assertEquals(PutStatus.UPDATED, dao.put(key, createLead("Again-" + key, key + "@test.com")));
            }
            assertEquals(PutStatus.INSERTED, dao.put("after-crash", createLead("After", "after@test.com")));
            assertTrue(dao.delete("one"));

            assertEquals(expected, dao.size());
            assertEquals("Bob", dao.get("two").fullName);
            assertEquals("Again-record-7", dao.get("record-7").fullName);
            assertEquals(Set.of("after-crash"), keysFor(dao, "fullName", "After"));
            assertTrue(Files.exists(Path.of(dao.dataPath() + ChronicleDao.BACKUP_DIR + "corrupted-data")),
                    "deadlock recovery should have taken place and preserved the corrupt file");
        }

        @Test
        void createDataDirs_scanFails_doesNotPublishEmptyKeyMap() throws Exception {
            final var dao = seededDao();
            Files.delete(Path.of(dao.getKeyMapPath()));
            dao.failScan = true;
            assertThrows(IllegalStateException.class, dao::createDataDirs);
            assertFalse(Files.exists(Path.of(dao.getKeyMapPath())));
            dao.failScan = false;
            dao.createDataDirs();
            assertEquals(2, dao.size());
        }

        @Test
        void indexedSearch_checksumFailure_repeatedlyRecoversAndPreservesEvidence() throws Exception {
            final var dao = seededDao();
            for (int attempt = 0; attempt < 3; attempt++) {
                breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
                assertEquals(Set.of("one"), dao.indexedSearch(
                        new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
                assertEquals(Set.of("two"), dao.indexedSearch(
                        new Search("fullName", SearchType.EQUAL, "Bob")).keySet());
            }
            try (final var backups = Files.list(Path.of(dao.dataPath() + ChronicleDao.BACKUP_DIR))) {
                assertEquals(3, backups.filter(path -> path.getFileName().toString()
                        .startsWith("fullName-corrupt-")).count());
            }
        }

        @Test
        void get_checksumFailure_repeatedlyRecoversAllShards() throws Exception {
            final var dao = seededDao();
            final var values = new HashMap<String, Lead>();
            for (int i = 0; i < 120; i++) {
                values.put("record-" + i, createLead("Person" + i, "person" + i + "@test.com"));
            }
            dao.put(values);
            assertTrue(dao.getDataFileState().fileNames().size() > 1);
            for (int attempt = 0; attempt < 3; attempt++) {
                breakHeaderChecksum(Path.of(dao.getKeyMapPath()));
                assertNotNull(dao.get("one"));
                assertEquals(122, dao.size());
                assertEquals(values.keySet(), dao.get(values.keySet()).keySet());
            }
        }

        @Test
        void indexedSearch_missingIndex_rebuildsInsteadOfReturningEmpty() throws Exception {
            final var dao = seededDao();
            Files.delete(Path.of(dao.getIndexPath("fullName")));
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
        }

        @Test
        void size_missingKeyMap_rebuildsInsteadOfReturningZero() throws Exception {
            final var dao = seededDao();
            Files.delete(Path.of(dao.getKeyMapPath()));
            assertEquals(2, dao.size());
        }

        @Test
        void indexedSearch_concurrentRecovery_allCallersSeeCompleteIndex() throws Exception {
            final var dao = seededDao();
            breakHeaderChecksum(Path.of(dao.getIndexPath("fullName")));
            final int before = dao.scans.get();
            final var start = new CountDownLatch(1);
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var futures = new ArrayList<java.util.concurrent.Future<Set<String>>>();
                for (int i = 0; i < 12; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return dao.indexedSearch(new Search("fullName", SearchType.EQUAL, "Alice")).keySet();
                    }));
                }
                start.countDown();
                for (final var future : futures) {
                    assertEquals(Set.of("one"), future.get(15, TimeUnit.SECONDS));
                }
            }
            // One recovery scan plus one result lookup per caller.
            assertEquals(13, dao.scans.get() - before);
        }

        @Test
        void indexedSearch_scanFails_keepsCorruptFileAndRetriesOnNextRequest() throws Exception {
            final var dao = seededDao();
            final var path = Path.of(dao.getIndexPath("fullName"));
            breakHeaderChecksum(path);
            final var corrupt = directory.resolve("corrupt-snapshot");
            Files.copy(path, corrupt);
            dao.failScan = true;
            assertThrows(IllegalStateException.class, () -> dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")));
            assertEquals(-1, Files.mismatch(corrupt, path));
            dao.failScan = false;
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
        }

        @Test
        void indexedSearch_missingPrimary_failsWithoutCreatingEmptyPrimaryOrIndex() throws Exception {
            final var dao = seededDao();
            final var source = Path.of(dao.dataPath() + ChronicleDao.DATA_DIR + "data");
            final var saved = directory.resolve("saved-data");
            Files.move(source, saved);
            Files.delete(Path.of(dao.getIndexPath("fullName")));
            assertThrows(java.io.UncheckedIOException.class, () -> dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")));
            assertFalse(Files.exists(source));
            assertFalse(Files.exists(Path.of(dao.getIndexPath("fullName"))));
            Files.move(saved, source);
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
        }

        @Test
        void indexedSearch_corruptIndex_recoversAllRecordsOnFirstRequest() throws Exception {
            final var dao = seededDao();
            Files.write(Path.of(dao.getIndexPath("fullName")), new byte[32]);
            assertEquals(Set.of("one"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Alice")).keySet());
            assertEquals(Set.of("two"), dao.indexedSearch(
                    new Search("fullName", SearchType.EQUAL, "Bob")).keySet());
        }

        @Test
        void get_corruptKeyMap_recoversAllRecordsOnFirstRequest() throws Exception {
            final var dao = seededDao();
            Files.write(Path.of(dao.getKeyMapPath()), new byte[32]);
            assertEquals(2, dao.size());
            assertEquals(Set.of("one", "two"), dao.get(Set.of("one", "two")).keySet());
        }
    }
}
