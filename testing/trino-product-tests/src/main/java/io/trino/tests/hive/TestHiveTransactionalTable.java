/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.tests.hive;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.trino.plugin.hive.metastore.thrift.ThriftHiveMetastoreClient;
import io.trino.tempto.assertions.QueryAssert;
import io.trino.tempto.hadoop.hdfs.HdfsClient;
import io.trino.tempto.query.QueryExecutor;
import io.trino.tempto.query.QueryResult;
import io.trino.testng.services.Flaky;
import io.trino.tests.hive.util.TemporaryHiveTable;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tempto.query.QueryExecutor.query;
import static io.trino.tests.TestGroups.HIVE_TRANSACTIONAL;
import static io.trino.tests.TestGroups.STORAGE_FORMATS;
import static io.trino.tests.hive.BucketingType.BUCKETED_V2;
import static io.trino.tests.hive.BucketingType.NONE;
import static io.trino.tests.hive.TestHiveTransactionalTable.CompactionMode.MAJOR;
import static io.trino.tests.hive.TestHiveTransactionalTable.CompactionMode.MINOR;
import static io.trino.tests.hive.TransactionalTableType.ACID;
import static io.trino.tests.hive.TransactionalTableType.INSERT_ONLY;
import static io.trino.tests.hive.util.TableLocationUtils.getTablePath;
import static io.trino.tests.hive.util.TemporaryHiveTable.randomTableSuffix;
import static io.trino.tests.utils.QueryExecutors.onHive;
import static io.trino.tests.utils.QueryExecutors.onTrino;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toUnmodifiableList;

public class TestHiveTransactionalTable
        extends HiveProductTest
{
    private static final Logger log = Logger.get(TestHiveTransactionalTable.class);

    private static final int TEST_TIMEOUT = 15 * 60 * 1000;

    @Inject
    private TestHiveMetastoreClientFactory testHiveMetastoreClientFactory;

    @Inject
    private HdfsClient hdfsClient;

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testReadFullAcid()
    {
        doTestReadFullAcid(false, BucketingType.NONE);
    }

    @Flaky(issue = "https://github.com/trinodb/trino/issues/4927", match = "Hive table .* is corrupt. Found sub-directory in bucket directory for partition")
    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testReadFullAcidBucketed()
    {
        doTestReadFullAcid(false, BucketingType.BUCKETED_DEFAULT);
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testReadFullAcidPartitioned()
    {
        doTestReadFullAcid(true, BucketingType.NONE);
    }

    // This test is in STORAGE_FORMATS group to ensure test coverage of transactional tables with various
    // metastore and HDFS setups (kerberized or not, impersonation or not).
    @Test(groups = {HIVE_TRANSACTIONAL, STORAGE_FORMATS}, timeOut = TEST_TIMEOUT)
    public void testReadFullAcidPartitionedBucketed()
    {
        doTestReadFullAcid(true, BucketingType.BUCKETED_DEFAULT);
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    @Flaky(issue = "https://github.com/trinodb/trino/issues/4927", match = "Hive table .* is corrupt. Found sub-directory in bucket directory for partition")
    public void testReadFullAcidBucketedV1()
    {
        doTestReadFullAcid(false, BucketingType.BUCKETED_V1);
    }

    @Flaky(issue = "https://github.com/trinodb/trino/issues/4927", match = "Hive table .* is corrupt. Found sub-directory in bucket directory for partition")
    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testReadFullAcidBucketedV2()
    {
        doTestReadFullAcid(false, BucketingType.BUCKETED_V2);
    }

    private void doTestReadFullAcid(boolean isPartitioned, BucketingType bucketingType)
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Hive transactional tables are supported with Hive version 3 or above");
        }

        try (TemporaryHiveTable table = TemporaryHiveTable.temporaryHiveTable(tableName("read_full_acid", isPartitioned, bucketingType))) {
            String tableName = table.getName();
            onHive().executeQuery("CREATE TABLE " + tableName + " (col INT, fcol INT) " +
                    (isPartitioned ? "PARTITIONED BY (part_col INT) " : "") +
                    bucketingType.getHiveClustering("fcol", 4) + " " +
                    "STORED AS ORC " +
                    hiveTableProperties(ACID, bucketingType));

            String hivePartitionString = isPartitioned ? " PARTITION (part_col=2) " : "";
            onHive().executeQuery("INSERT OVERWRITE TABLE " + tableName + hivePartitionString + " VALUES (21, 1)");

            String selectFromOnePartitionsSql = "SELECT col, fcol FROM " + tableName + " ORDER BY col";
            assertThat(query(selectFromOnePartitionsSql)).containsOnly(row(21, 1));

            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (22, 2)");
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(21, 1), row(22, 2));

            // test filtering
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE fcol = 1 ORDER BY col")).containsOnly(row(21, 1));

            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (24, 4)");
            onHive().executeQuery("DELETE FROM " + tableName + " where fcol=4");

            // test filtering
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE fcol = 1 ORDER BY col")).containsOnly(row(21, 1));

            // test minor compacted data read
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (20, 3)");

            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE col=20")).containsExactly(row(20, 3));

            compactTableAndWait(MINOR, tableName, hivePartitionString, Duration.valueOf("6m"));
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(20, 3), row(21, 1), row(22, 2));

            // delete a row
            onHive().executeQuery("DELETE FROM " + tableName + " WHERE fcol=2");
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(20, 3), row(21, 1));

            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE col=20")).containsExactly(row(20, 3));

            // update the existing row
            String predicate = "fcol = 1" + (isPartitioned ? " AND part_col = 2 " : "");
            onHive().executeQuery("UPDATE " + tableName + " SET col = 23 WHERE " + predicate);
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(20, 3), row(23, 1));

            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE col=20")).containsExactly(row(20, 3));

            // test major compaction
            compactTableAndWait(MAJOR, tableName, hivePartitionString, Duration.valueOf("6m"));
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(20, 3), row(23, 1));
        }
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "partitioningAndBucketingTypeDataProvider", timeOut = TEST_TIMEOUT)
    @Flaky(issue = "https://github.com/trinodb/trino/issues/4927", match = "Hive table .* is corrupt. Found sub-directory in bucket directory for partition")
    public void testReadInsertOnly(boolean isPartitioned, BucketingType bucketingType)
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Hive transactional tables are supported with Hive version 3 or above");
        }

        try (TemporaryHiveTable table = TemporaryHiveTable.temporaryHiveTable(tableName("insert_only", isPartitioned, bucketingType))) {
            String tableName = table.getName();

            onHive().executeQuery("CREATE TABLE " + tableName + " (col INT) " +
                    (isPartitioned ? "PARTITIONED BY (part_col INT) " : "") +
                    bucketingType.getHiveClustering("col", 4) + " " +
                    "STORED AS ORC " +
                    hiveTableProperties(INSERT_ONLY, bucketingType));

            String hivePartitionString = isPartitioned ? " PARTITION (part_col=2) " : "";
            String predicate = isPartitioned ? " WHERE part_col = 2 " : "";

            onHive().executeQuery("INSERT OVERWRITE TABLE " + tableName + hivePartitionString + " SELECT 1");
            String selectFromOnePartitionsSql = "SELECT col FROM " + tableName + predicate + " ORDER BY COL";
            assertThat(query(selectFromOnePartitionsSql)).containsOnly(row(1));

            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " SELECT 2");
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(1), row(2));

            assertThat(query("SELECT col FROM " + tableName + " WHERE col=2")).containsExactly(row(2));

            // test minor compacted data read
            compactTableAndWait(MINOR, tableName, hivePartitionString, Duration.valueOf("6m"));
            assertThat(query(selectFromOnePartitionsSql)).containsExactly(row(1), row(2));
            assertThat(query("SELECT col FROM " + tableName + " WHERE col=2")).containsExactly(row(2));

            onHive().executeQuery("INSERT OVERWRITE TABLE " + tableName + hivePartitionString + " SELECT 3");
            assertThat(query(selectFromOnePartitionsSql)).containsOnly(row(3));

            if (getHiveVersionMajor() >= 4) {
                // Major compaction on insert only table does not work prior to Hive 4:
                // https://issues.apache.org/jira/browse/HIVE-21280

                // test major compaction
                onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " SELECT 4");
                compactTableAndWait(MAJOR, tableName, hivePartitionString, Duration.valueOf("6m"));
                assertThat(query(selectFromOnePartitionsSql)).containsOnly(row(3), row(4));
            }
        }
    }

    @Test(groups = {STORAGE_FORMATS, HIVE_TRANSACTIONAL}, dataProvider = "partitioningAndBucketingTypeDataProvider", timeOut = TEST_TIMEOUT)
    @Flaky(issue = "https://github.com/trinodb/trino/issues/4927", match = "Hive table .* is corrupt. Found sub-directory in bucket directory for partition")
    public void testReadFullAcidWithOriginalFiles(boolean isPartitioned, BucketingType bucketingType)
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Trino Hive transactional tables are supported with Hive version 3 or above");
        }

        String tableName = "test_full_acid_acid_converted_table_read";
        onHive().executeQuery("DROP TABLE IF EXISTS " + tableName);
        verify(bucketingType.getHiveTableProperties().isEmpty()); // otherwise we would need to include that in the CREATE TABLE's TBLPROPERTIES
        onHive().executeQuery("CREATE TABLE " + tableName + " (col INT, fcol INT) " +
                (isPartitioned ? "PARTITIONED BY (part_col INT) " : "") +
                bucketingType.getHiveClustering("fcol", 4) + " " +
                "STORED AS ORC " +
                "TBLPROPERTIES ('transactional'='false')");

        try {
            String hivePartitionString = isPartitioned ? " PARTITION (part_col=2) " : "";
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (21, 1)");
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (22, 2)");
            onHive().executeQuery("ALTER TABLE " + tableName + " SET " + hiveTableProperties(ACID, bucketingType));

            // read with original files
            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(21, 1), row(22, 2));
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE fcol = 1")).containsOnly(row(21, 1));

            // read with original files and insert delta
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (20, 3)");
            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(20, 3), row(21, 1), row(22, 2));

            // read with original files and delete delta
            onHive().executeQuery("DELETE FROM " + tableName + " WHERE fcol = 2");
            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(20, 3), row(21, 1));

            // read with original files and insert+delete delta (UPDATE)
            onHive().executeQuery("UPDATE " + tableName + " SET col = 23 WHERE fcol = 1" + (isPartitioned ? " AND part_col = 2 " : ""));
            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(20, 3), row(23, 1));
        }
        finally {
            onHive().executeQuery("DROP TABLE " + tableName);
        }
    }

    @Test(groups = {STORAGE_FORMATS, HIVE_TRANSACTIONAL}, dataProvider = "partitioningAndBucketingTypeDataProvider", timeOut = TEST_TIMEOUT)
    @Flaky(issue = ERROR_COMMITTING_WRITE_TO_HIVE_ISSUE, match = ERROR_COMMITTING_WRITE_TO_HIVE_MATCH)
    public void testUpdateFullAcidWithOriginalFilesPrestoInserting(boolean isPartitioned, BucketingType bucketingType)
    {
        withTemporaryTable("trino_update_full_acid_acid_converted_table_read", true, isPartitioned, bucketingType, tableName -> {
            onHive().executeQuery("DROP TABLE IF EXISTS " + tableName);
            verify(bucketingType.getHiveTableProperties().isEmpty()); // otherwise we would need to include that in the CREATE TABLE's TBLPROPERTIES
            onHive().executeQuery("CREATE TABLE " + tableName + " (col INT, fcol INT) " +
                    (isPartitioned ? "PARTITIONED BY (part_col INT) " : "") +
                    bucketingType.getHiveClustering("fcol", 4) + " " +
                    "STORED AS ORC " +
                    "TBLPROPERTIES ('transactional'='false')");

            String hivePartitionString = isPartitioned ? " PARTITION (part_col=2) " : "";
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (21, 1)");
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (22, 2)");
            onHive().executeQuery("ALTER TABLE " + tableName + " SET " + hiveTableProperties(ACID, bucketingType));

            // read with original files
            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(21, 1), row(22, 2));
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE fcol = 1")).containsOnly(row(21, 1));

            if (isPartitioned) {
                query("INSERT INTO " + tableName + "(col, fcol, part_col) VALUES (20, 4, 2)");
            }
            else {
                query("INSERT INTO " + tableName + "(col, fcol) VALUES (20, 4)");
            }

            // read with original files and insert delta
            if (isPartitioned) {
                query("INSERT INTO " + tableName + "(col, fcol, part_col) VALUES (20, 3, 2)");
            }
            else {
                query("INSERT INTO " + tableName + "(col, fcol) VALUES (20, 3)");
            }

            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(20, 3), row(20, 4), row(21, 1), row(22, 2));

            // read with original files and delete delta
            onHive().executeQuery("DELETE FROM " + tableName + " WHERE fcol = 2");

            assertThat(query("SELECT col, fcol FROM " + tableName)).containsOnly(row(20, 3), row(20, 4), row(21, 1));
        });
    }

    @Test(groups = {STORAGE_FORMATS, HIVE_TRANSACTIONAL}, dataProvider = "partitioningAndBucketingTypeDataProvider", timeOut = TEST_TIMEOUT)
    @Flaky(issue = ERROR_COMMITTING_WRITE_TO_HIVE_ISSUE, match = ERROR_COMMITTING_WRITE_TO_HIVE_MATCH)
    public void testUpdateFullAcidWithOriginalFilesPrestoInsertingAndDeleting(boolean isPartitioned, BucketingType bucketingType)
    {
        withTemporaryTable("trino_update_full_acid_acid_converted_table_read", true, isPartitioned, bucketingType, tableName -> {
            onHive().executeQuery("DROP TABLE IF EXISTS " + tableName);
            verify(bucketingType.getHiveTableProperties().isEmpty()); // otherwise we would need to include that in the CREATE TABLE's TBLPROPERTIES
            onHive().executeQuery("CREATE TABLE " + tableName + " (col INT, fcol INT) " +
                    (isPartitioned ? "PARTITIONED BY (part_col INT) " : "") +
                    bucketingType.getHiveClustering("fcol", 4) + " " +
                    "STORED AS ORC " +
                    "TBLPROPERTIES ('transactional'='false')");

            String hivePartitionString = isPartitioned ? " PARTITION (part_col=2) " : "";
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (10, 100), (11, 110), (12, 120), (13, 130), (14, 140)");
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (15, 150), (16, 160), (17, 170), (18, 180), (19, 190)");

            onHive().executeQuery("ALTER TABLE " + tableName + " SET " + hiveTableProperties(ACID, bucketingType));

            // read with original files
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE col < 12")).containsOnly(row(10, 100), row(11, 110));

            String fields = isPartitioned ? "(col, fcol, part_col)" : "(col, fcol)";
            query(format("INSERT INTO %s %s VALUES %s", tableName, fields, makeValues(30, 5, 2, isPartitioned, 3)));
            query(format("INSERT INTO %s %s VALUES %s", tableName, fields, makeValues(40, 5, 2, isPartitioned, 3)));

            query("DELETE FROM " + tableName + " WHERE col IN (11, 12)");
            query("DELETE FROM " + tableName + " WHERE col IN (16, 17)");
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE fcol >= 100")).containsOnly(row(10, 100), row(13, 130), row(14, 140), row(15, 150), row(18, 180), row(19, 190));

            // read with original files and delete delta
            query("DELETE FROM " + tableName + " WHERE col = 18 OR col = 14 OR (fcol = 2 AND (col / 2) * 2 = col)");

            assertThat(onHive().executeQuery("SELECT col, fcol FROM " + tableName))
                    .containsOnly(row(10, 100), row(13, 130), row(15, 150), row(19, 190), row(31, 2), row(33, 2), row(41, 2), row(43, 2));

            assertThat(query("SELECT col, fcol FROM " + tableName))
                    .containsOnly(row(10, 100), row(13, 130), row(15, 150), row(19, 190), row(31, 2), row(33, 2), row(41, 2), row(43, 2));
        });
    }

    String makeValues(int colStart, int colCount, int fcol, boolean isPartitioned, int partCol)
    {
        return IntStream.range(colStart, colStart + colCount - 1)
                .boxed()
                .map(n -> isPartitioned ? format("(%s, %s, %s)", n, fcol, partCol) : format("(%s, %s)", n, fcol))
                .collect(Collectors.joining(", "));
    }

    @Test(groups = {STORAGE_FORMATS, HIVE_TRANSACTIONAL}, dataProvider = "partitioningAndBucketingTypeDataProvider", timeOut = TEST_TIMEOUT)
    @Flaky(issue = "https://github.com/trinodb/trino/issues/4927", match = "Hive table .* is corrupt. Found sub-directory in bucket directory for partition")
    public void testReadInsertOnlyWithOriginalFiles(boolean isPartitioned, BucketingType bucketingType)
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Trino Hive transactional tables are supported with Hive version 3 or above");
        }

        String tableName = "test_insert_only_acid_converted_table_read";
        onHive().executeQuery("DROP TABLE IF EXISTS " + tableName);
        verify(bucketingType.getHiveTableProperties().isEmpty()); // otherwise we would need to include that in the CREATE TABLE's TBLPROPERTIES
        onHive().executeQuery("CREATE TABLE " + tableName + " (col INT) " +
                (isPartitioned ? "PARTITIONED BY (part_col INT) " : "") +
                bucketingType.getHiveClustering("col", 4) + " " +
                "STORED AS ORC " +
                "TBLPROPERTIES ('transactional'='false')");
        try {
            String hivePartitionString = isPartitioned ? " PARTITION (part_col=2) " : "";

            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (1)");
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (2)");
            onHive().executeQuery("ALTER TABLE " + tableName + " SET " + hiveTableProperties(INSERT_ONLY, bucketingType));

            // read with original files
            assertThat(query("SELECT col FROM " + tableName + (isPartitioned ? " WHERE part_col = 2 " : "" + " ORDER BY col"))).containsOnly(row(1), row(2));

            // read with original files and delta
            onHive().executeQuery("INSERT INTO TABLE " + tableName + hivePartitionString + " VALUES (3)");
            assertThat(query("SELECT col FROM " + tableName + (isPartitioned ? " WHERE part_col = 2 " : "" + " ORDER BY col"))).containsOnly(row(1), row(2), row(3));
        }
        finally {
            onHive().executeQuery("DROP TABLE " + tableName);
        }
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testFailAcidBeforeHive3()
    {
        if (getHiveVersionMajor() >= 3) {
            throw new SkipException("This tests behavior of ACID table before Hive 3 ");
        }

        try (TemporaryHiveTable table = TemporaryHiveTable.temporaryHiveTable("test_fail_acid_before_hive3_" + randomTableSuffix())) {
            String tableName = table.getName();
            onHive().executeQuery("" +
                    "CREATE TABLE " + tableName + "(a bigint) " +
                    "CLUSTERED BY(a) INTO 4 BUCKETS " +
                    "STORED AS ORC " +
                    "TBLPROPERTIES ('transactional'='true')");

            assertThat(() -> query("SELECT * FROM " + tableName))
                    .failsWithMessage("Failed to open transaction. Transactional tables support requires Hive metastore version at least 3.0");
        }
    }

    @DataProvider
    public Object[][] partitioningAndBucketingTypeDataProvider()
    {
        return new Object[][] {
                {false, BucketingType.NONE},
                {false, BucketingType.BUCKETED_DEFAULT},
                {true, BucketingType.NONE},
                {true, BucketingType.BUCKETED_DEFAULT},
        };
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "testCreateAcidTableDataProvider")
    public void testCtasAcidTable(boolean isPartitioned, BucketingType bucketingType)
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Hive transactional tables are supported with Hive version 3 or above");
        }

        try (TemporaryHiveTable table = TemporaryHiveTable.temporaryHiveTable(format("ctas_transactional_%s", randomTableSuffix()))) {
            String tableName = table.getName();
            query("CREATE TABLE " + tableName + " " +
                    prestoTableProperties(ACID, isPartitioned, bucketingType) +
                    " AS SELECT * FROM (VALUES (21, 1, 1), (22, 1, 2), (23, 2, 2)) t(col, fcol, partcol)");

            // can we query from Presto
            assertThat(query("SELECT col, fcol FROM " + tableName + " WHERE partcol = 2 ORDER BY col"))
                    .containsOnly(row(22, 1), row(23, 2));

            // can we query from Hive
            assertThat(onHive().executeQuery("SELECT col, fcol FROM " + tableName + " WHERE partcol = 2 ORDER BY col"))
                    .containsOnly(row(22, 1), row(23, 2));
        }
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "testCreateAcidTableDataProvider")
    public void testCreateAcidTable(boolean isPartitioned, BucketingType bucketingType)
    {
        withTemporaryTable("create_transactional", true, isPartitioned, bucketingType, tableName -> {
            query("CREATE TABLE " + tableName + " (col INTEGER, fcol INTEGER, partcol INTEGER)" +
                    prestoTableProperties(ACID, isPartitioned, bucketingType));

            query("INSERT INTO " + tableName + " VALUES (1, 2, 3)");
            assertThat(query("SELECT * FROM " + tableName)).containsOnly(row(1, 2, 3));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testSimpleUnpartitionedTransactionalInsert()
    {
        withTemporaryTable("unpartitioned_transactional_insert", true, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true)", tableName));

            onTrino().executeQuery(format("INSERT INTO %s VALUES (11, 100), (12, 200), (13, 300)", tableName));

            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(11, 100L), row(12, 200L), row(13, 300L));

            onTrino().executeQuery(format("INSERT INTO %s VALUES (14, 400), (15, 500), (16, 600)", tableName));

            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(11, 100L), row(12, 200L), row(13, 300L), row(14, 400L), row(15, 500L), row(16, 600L));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testTransactionalPartitionInsert()
    {
        withTemporaryTable("transactional_partition_insert", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true, partitioned_by = ARRAY['column2'])", tableName));

            onTrino().executeQuery(format("INSERT INTO %s (column2, column1) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 1, 20),
                    makeInsertValues(2, 1, 20)));

            verifySelectForPrestoAndHive(format("SELECT COUNT(*) FROM %s", tableName), "column1 > 10", row(20));

            onTrino().executeQuery(format("INSERT INTO %s (column2, column1) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 21, 30),
                    makeInsertValues(2, 21, 30)));

            verifySelectForPrestoAndHive(format("SELECT COUNT(*) FROM %s", tableName), "column1 > 15 AND column1 <= 25", row(20));

            onHive().executeQuery(format("DELETE FROM %s WHERE column1 > 15 AND column1 <= 25", tableName));

            verifySelectForPrestoAndHive(format("SELECT COUNT(*) FROM %s", tableName), "column1 > 15 AND column1 <= 25", row(0));

            onTrino().executeQuery(format("INSERT INTO %s (column2, column1) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 20, 23),
                    makeInsertValues(2, 20, 23)));

            verifySelectForPrestoAndHive(format("SELECT COUNT(*) FROM %s", tableName), "column1 > 15 AND column1 <= 25", row(8));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testTransactionalBucketedPartitionedInsert()
    {
        testTransactionalBucketedPartitioned(false);
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testTransactionalBucketedPartitionedInsertOnly()
    {
        testTransactionalBucketedPartitioned(true);
    }

    private void testTransactionalBucketedPartitioned(boolean insertOnly)
    {
        withTemporaryTable("bucketed_partitioned_insert_only", true, true, BUCKETED_V2, tableName -> {
            String insertOnlyProperty = insertOnly ? ", 'transactional_properties'='insert_only'" : "";
            onHive().executeQuery(format("CREATE TABLE %s (purchase STRING) PARTITIONED BY (customer STRING) CLUSTERED BY (purchase) INTO 3 BUCKETS" +
                            " STORED AS ORC TBLPROPERTIES ('transactional' = 'true'%s)",
                    tableName, insertOnlyProperty));

            onTrino().executeQuery(format("INSERT INTO %s (customer, purchase) VALUES", tableName) +
                    " ('Fred', 'cards'), ('Fred', 'cereal'), ('Fred', 'limes'), ('Fred', 'chips')," +
                    " ('Ann', 'cards'), ('Ann', 'cereal'), ('Ann', 'lemons'), ('Ann', 'chips')," +
                    " ('Lou', 'cards'), ('Lou', 'cereal'), ('Lou', 'lemons'), ('Lou', 'chips')");

            verifySelectForPrestoAndHive(format("SELECT customer FROM %s", tableName), "purchase = 'lemons'", row("Ann"), row("Lou"));

            verifySelectForPrestoAndHive(format("SELECT purchase FROM %s", tableName), "customer = 'Fred'", row("cards"), row("cereal"), row("limes"), row("chips"));

            onTrino().executeQuery(format("INSERT INTO %s (customer, purchase) VALUES", tableName) +
                    " ('Ernie', 'cards'), ('Ernie', 'cereal')," +
                    " ('Debby', 'corn'), ('Debby', 'chips')," +
                    " ('Joe', 'corn'), ('Joe', 'lemons'), ('Joe', 'candy')");

            verifySelectForPrestoAndHive(format("SELECT customer FROM %s", tableName), "purchase = 'corn'", row("Debby"), row("Joe"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testTransactionalUnpartitionedDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("unpartitioned_delete", true, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INTEGER, column2 BIGINT) WITH (format = 'ORC', transactional = true)", tableName));
            execute(inserter, format("INSERT INTO %s (column1, column2) VALUES (1, 100), (2, 200), (3, 300), (4, 400), (5, 500)", tableName));
            execute(deleter, format("DELETE FROM %s WHERE column2 = 100", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(2, 200), row(3, 300), row(4, 400), row(5, 500));

            execute(inserter, format("INSERT INTO %s VALUES (6, 600), (7, 700)", tableName));
            execute(deleter, format("DELETE FROM %s WHERE column1 = 4", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(2, 200), row(3, 300), row(5, 500), row(6, 600), row(7, 700));

            execute(deleter, format("DELETE FROM %s WHERE column1 <= 3 OR column1 = 6", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(5, 500), row(7, 700));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testMultiDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("unpartitioned_multi_delete", true, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true)", tableName));
            execute(inserter, format("INSERT INTO %s VALUES (1, 100), (2, 200), (3, 300), (4, 400), (5, 500)", tableName));
            execute(inserter, format("INSERT INTO %s VALUES (6, 600), (7, 700), (8, 800), (9, 900), (10, 1000)", tableName));

            execute(deleter, format("DELETE FROM %s WHERE column1 = 9", tableName));
            execute(deleter, format("DELETE FROM %s WHERE column1 = 2 OR column1 = 3", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 100), row(4, 400), row(5, 500), row(6, 600), row(7, 700), row(8, 800), row(10, 1000));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testTransactionalMetadataDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("metadata_delete", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true, partitioned_by = ARRAY['column2'])", tableName));
            execute(inserter, format("INSERT INTO %s (column2, column1) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 1, 20),
                    makeInsertValues(2, 1, 20)));

            execute(deleter, format("DELETE FROM %s WHERE column2 = 1", tableName));
            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "column2 = 1", row(0));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testNonTransactionalMetadataDelete()
    {
        withTemporaryTable("non_transactional_metadata_delete", false, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column2 BIGINT, column1 INT) WITH (partitioned_by = ARRAY['column1'])", tableName));

            execute(HiveOrPresto.PRESTO, format("INSERT INTO %s (column1, column2) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 1, 10),
                    makeInsertValues(2, 1, 10)));

            execute(HiveOrPresto.PRESTO, format("INSERT INTO %s (column1, column2) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 11, 20),
                    makeInsertValues(2, 11, 20)));

            execute(HiveOrPresto.PRESTO, format("DELETE FROM %s WHERE column1 = 1", tableName));
            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "column1 = 1", row(0));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testUnpartitionedDeleteAll(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("unpartitioned_delete_all", true, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true)", tableName));
            execute(inserter, format("INSERT INTO %s VALUES (1, 100), (2, 200), (3, 300), (4, 400), (5, 500)", tableName));
            execute(deleter, "DELETE FROM " + tableName);
            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "true", row(0));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testMultiColumnDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("multi_column_delete", true, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true)", tableName));
            execute(inserter, format("INSERT INTO %s VALUES (1, 100), (2, 200), (3, 300), (4, 400), (5, 500)", tableName));
            String where = " WHERE column1 >= 2 AND column2 <= 400";
            execute(deleter, format("DELETE FROM %s %s", tableName, where));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "column1 IN (1, 5)", row(1, 100), row(5, 500));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testPartitionAndRowsDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("partition_and_rows_delete", true, true, NONE, tableName -> {
            onTrino().executeQuery("CREATE TABLE " + tableName +
                    " (column2 BIGINT, column1 INT) WITH (transactional = true, partitioned_by = ARRAY['column1'])");
            execute(inserter, format("INSERT INTO %s (column1, column2) VALUES (1, 100), (1, 200), (2, 300), (2, 400), (2, 500)", tableName));
            String where = " WHERE column1 = 2 OR column2 = 200";
            execute(deleter, format("DELETE FROM %s %s", tableName, where));
            verifySelectForPrestoAndHive("SELECT column1, column2 FROM " + tableName, "true", row(1, 100));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testPartitionedInsertAndRowLevelDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("partitioned_row_level_delete", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column2 INT, column1 BIGINT) WITH (transactional = true, partitioned_by = ARRAY['column1'])", tableName));

            execute(inserter, format("INSERT INTO %s (column1, column2) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 1, 20),
                    makeInsertValues(2, 1, 20)));
            execute(inserter, format("INSERT INTO %s (column1, column2) VALUES %s, %s",
                    tableName,
                    makeInsertValues(1, 21, 40),
                    makeInsertValues(2, 21, 40)));

            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "column2 > 10 AND column2 <= 30", row(40));

            execute(deleter, format("DELETE FROM %s WHERE column2 > 10 AND column2 <= 30", tableName));
            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "column2 > 10 AND column2 <= 30", row(0));
            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "true", row(40));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testBucketedPartitionedDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("bucketed_partitioned_delete", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (purchase STRING) PARTITIONED BY (customer STRING) CLUSTERED BY (purchase) INTO 3 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional' = 'true')", tableName));

            execute(inserter, format("INSERT INTO %s (customer, purchase) VALUES", tableName) +
                    " ('Fred', 'cards'), ('Fred', 'cereal'), ('Fred', 'limes'), ('Fred', 'chips')," +
                    " ('Ann', 'cards'), ('Ann', 'cereal'), ('Ann', 'lemons'), ('Ann', 'chips')," +
                    " ('Lou', 'cards'), ('Lou', 'cereal'), ('Lou', 'lemons'), ('Lou', 'chips')");

            verifySelectForPrestoAndHive(format("SELECT customer FROM %s", tableName), "purchase = 'lemons'", row("Ann"), row("Lou"));

            verifySelectForPrestoAndHive(format("SELECT purchase FROM %s", tableName), "customer = 'Fred'", row("cards"), row("cereal"), row("limes"), row("chips"));

            execute(inserter, format("INSERT INTO %s (customer, purchase) VALUES", tableName) +
                    " ('Ernie', 'cards'), ('Ernie', 'cereal')," +
                    " ('Debby', 'corn'), ('Debby', 'chips')," +
                    " ('Joe', 'corn'), ('Joe', 'lemons'), ('Joe', 'candy')");

            verifySelectForPrestoAndHive("SELECT customer FROM " + tableName, "purchase = 'corn'", row("Debby"), row("Joe"));

            execute(deleter, format("DELETE FROM %s WHERE purchase = 'lemons'", tableName));
            verifySelectForPrestoAndHive("SELECT purchase FROM " + tableName, "customer = 'Ann'", row("cards"), row("cereal"), row("chips"));

            execute(deleter, format("DELETE FROM %s WHERE purchase like('c%%')", tableName));
            verifySelectForPrestoAndHive("SELECT customer, purchase FROM " + tableName, "true", row("Fred", "limes"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testDeleteAllRowsInPartition()
    {
        withTemporaryTable("bucketed_partitioned_delete", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (purchase STRING) PARTITIONED BY (customer STRING) STORED AS ORC TBLPROPERTIES ('transactional' = 'true')", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (customer, purchase) VALUES ('Fred', 'cards'), ('Fred', 'cereal'), ('Ann', 'lemons'), ('Ann', 'chips')", tableName));

            log.info("About to delete");
            onTrino().executeQuery(format("DELETE FROM %s WHERE customer = 'Fred'", tableName));

            log.info("About to verify");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row("lemons", "Ann"), row("chips", "Ann"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testBucketedUnpartitionedDelete(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("bucketed_unpartitioned_delete", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (customer STRING, purchase STRING) CLUSTERED BY (purchase) INTO 3 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional' = 'true')", tableName));

            execute(inserter, format("INSERT INTO %s (customer, purchase) VALUES", tableName) +
                    " ('Fred', 'cards'), ('Fred', 'cereal'), ('Fred', 'limes'), ('Fred', 'chips')," +
                    " ('Ann', 'cards'), ('Ann', 'cereal'), ('Ann', 'lemons'), ('Ann', 'chips')," +
                    " ('Lou', 'cards'), ('Lou', 'cereal'), ('Lou', 'lemons'), ('Lou', 'chips')");

            verifySelectForPrestoAndHive(format("SELECT customer FROM %s", tableName), "purchase = 'lemons'", row("Ann"), row("Lou"));

            verifySelectForPrestoAndHive(format("SELECT purchase FROM %s", tableName), "customer = 'Fred'", row("cards"), row("cereal"), row("limes"), row("chips"));

            execute(inserter, format("INSERT INTO %s (customer, purchase) VALUES", tableName) +
                    " ('Ernie', 'cards'), ('Ernie', 'cereal')," +
                    " ('Debby', 'corn'), ('Debby', 'chips')," +
                    " ('Joe', 'corn'), ('Joe', 'lemons'), ('Joe', 'candy')");

            verifySelectForPrestoAndHive("SELECT customer FROM " + tableName, "purchase = 'corn'", row("Debby"), row("Joe"));

            execute(deleter, format("DELETE FROM %s WHERE purchase = 'lemons'", tableName));
            verifySelectForPrestoAndHive("SELECT purchase FROM " + tableName, "customer = 'Ann'", row("cards"), row("cereal"), row("chips"));

            execute(deleter, format("DELETE FROM %s WHERE purchase like('c%%')", tableName));
            verifySelectForPrestoAndHive("SELECT customer, purchase FROM " + tableName, "true", row("Fred", "limes"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "inserterAndDeleterProvider", timeOut = TEST_TIMEOUT)
    public void testCorrectSelectCountStar(HiveOrPresto inserter, HiveOrPresto deleter)
    {
        withTemporaryTable("select_count_star_delete", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (col1 INT, col2 BIGINT) PARTITIONED BY (col3 STRING) STORED AS ORC TBLPROPERTIES ('transactional'='true')", tableName));

            execute(inserter, format("INSERT INTO %s VALUES (1, 100, 'a'), (2, 200, 'b'), (3, 300, 'c'), (4, 400, 'a'), (5, 500, 'b'), (6, 600, 'c')", tableName));
            execute(deleter, format("DELETE FROM %s WHERE col2 = 200", tableName));
            verifySelectForPrestoAndHive("SELECT COUNT(*) FROM " + tableName, "true", row(5));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "insertersProvider", timeOut = TEST_TIMEOUT)
    public void testInsertOnlyMultipleWriters(boolean bucketed, HiveOrPresto inserter1, HiveOrPresto inserter2)
    {
        log.info("testInsertOnlyMultipleWriters bucketed %s, inserter1 %s, inserter2 %s", bucketed, inserter1, inserter2);
        withTemporaryTable("insert_only_partitioned", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (col1 INT, col2 BIGINT) PARTITIONED BY (col3 STRING) %s STORED AS ORC TBLPROPERTIES ('transactional'='true', 'transactional_properties'='insert_only')",
                    tableName, bucketed ? "CLUSTERED BY (col2) INTO 3 BUCKETS" : ""));

            execute(inserter1, format("INSERT INTO %s VALUES (1, 100, 'a'), (2, 200, 'b')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 100, "a"), row(2, 200, "b"));

            execute(inserter2, format("INSERT INTO %s VALUES (3, 300, 'c'), (4, 400, 'a')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 100, "a"), row(2, 200, "b"), row(3, 300, "c"), row(4, 400, "a"));

            execute(inserter1, format("INSERT INTO %s VALUES (5, 500, 'b'), (6, 600, 'c')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 100, "a"), row(2, 200, "b"), row(3, 300, "c"), row(4, 400, "a"), row(5, 500, "b"), row(6, 600, "c"));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "col2 > 300", row(4, 400, "a"), row(5, 500, "b"), row(6, 600, "c"));

            execute(inserter2, format("INSERT INTO %s VALUES (7, 700, 'b'), (8, 800, 'c')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 100, "a"), row(2, 200, "b"), row(3, 300, "c"), row(4, 400, "a"), row(5, 500, "b"), row(6, 600, "c"), row(7, 700, "b"), row(8, 800, "c"));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "col3 = 'c'", row(3, 300, "c"), row(6, 600, "c"), row(8, 800, "c"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "transactionModeProvider")
    public void testColumnRenamesOrcPartitioned(boolean transactional)
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_column_renames_partitioned", transactional, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (id BIGINT, old_name VARCHAR, age INT, old_state VARCHAR)" +
                    " WITH (format = 'ORC', transactional = %s, partitioned_by = ARRAY['old_state'])", tableName, transactional));
            testOrcColumnRenames(tableName);

            log.info("About to rename partition column old_state to new_state");
            assertThat(() -> onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN old_state TO new_state", tableName)))
                    .failsWithMessage("Renaming partition columns is not supported");
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "transactionModeProvider")
    public void testColumnRenamesOrcNotPartitioned(boolean transactional)
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_orc_column_renames_not_partitioned", transactional, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (id BIGINT, old_name VARCHAR, age INT, old_state VARCHAR)" +
                    " WITH (format = 'ORC', transactional = %s)", tableName, transactional));
            testOrcColumnRenames(tableName);
        });
    }

    private void testOrcColumnRenames(String tableName)
    {
        onTrino().executeQuery(format("INSERT INTO %s VALUES (111, 'Katy', 57, 'CA'), (222, 'Joe', 72, 'WA')", tableName));
        verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"));

        onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN old_name TO new_name", tableName));
        log.info("This shows that Presto and Hive can still query old data after a single rename");
        verifySelectForPrestoAndHive("SELECT age FROM " + tableName, "new_name = 'Katy'", row(57));

        onTrino().executeQuery(format("INSERT INTO %s VALUES(333, 'Joan', 23, 'OR')", tableName));
        verifySelectForPrestoAndHive("SELECT age FROM " + tableName, "new_name != 'Joe'", row(57), row(23));

        onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN new_name TO newer_name", tableName));
        log.info("This shows that Presto and Hive can still query old data after a double rename");
        verifySelectForPrestoAndHive("SELECT age FROM " + tableName, "newer_name = 'Katy'", row(57));

        onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN newer_name TO old_name", tableName));
        log.info("This shows that Presto and Hive can still query old data after a rename back to the original name");
        verifySelectForPrestoAndHive("SELECT age FROM " + tableName, "old_name = 'Katy'", row(57));
        verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"), row(333, "Joan", 23, "OR"));
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "transactionModeProvider")
    public void testOrcColumnSwap(boolean transactional)
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_orc_column_renames", transactional, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (name VARCHAR, state VARCHAR) WITH (format = 'ORC', transactional = %s)", tableName, transactional));
            onTrino().executeQuery(format("INSERT INTO %s VALUES ('Katy', 'CA'), ('Joe', 'WA')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row("Katy", "CA"), row("Joe", "WA"));

            onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN name TO new_name", tableName));
            onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN state TO name", tableName));
            onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN new_name TO state", tableName));
            log.info("This shows that Presto and Hive can still query old data, but because of the renames, columns are swapped!");
            verifySelectForPrestoAndHive("SELECT state, name FROM " + tableName, "TRUE", row("Katy", "CA"), row("Joe", "WA"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testBehaviorOnParquetColumnRenames()
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_parquet_column_renames", false, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (id BIGINT, old_name VARCHAR, age INT, old_state VARCHAR) WITH (format = 'PARQUET', transactional = false)", tableName));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (111, 'Katy', 57, 'CA'), (222, 'Joe', 72, 'WA')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"));

            onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN old_name TO new_name", tableName));

            onTrino().executeQuery(format("INSERT INTO %s VALUES (333, 'Fineas', 31, 'OR')", tableName));

            log.info("This shows that Hive and Trino do not see old data after a rename");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(111, null, 57, "CA"), row(222, null, 72, "WA"), row(333, "Fineas", 31, "OR"));

            onTrino().executeQuery(format("ALTER TABLE %s RENAME COLUMN new_name TO old_name", tableName));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (444, 'Gladys', 47, 'WA')", tableName));
            log.info("This shows that Presto and Hive both see data in old data files after renaming back to the original column name");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"), row(333, null, 31, "OR"), row(444, "Gladys", 47, "WA"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "transactionModeProvider")
    public void testOrcColumnDropAdd(boolean transactional)
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_orc_add_drop", transactional, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (id BIGINT, old_name VARCHAR, age INT, old_state VARCHAR) WITH (transactional = %s)", tableName, transactional));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (111, 'Katy', 57, 'CA'), (222, 'Joe', 72, 'WA')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"));

            onTrino().executeQuery(format("ALTER TABLE %s DROP COLUMN old_state", tableName));
            log.info("This shows that neither Presto nor Hive see the old data after a column is dropped");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57), row(222, "Joe", 72));

            onTrino().executeQuery(format("INSERT INTO %s VALUES (333, 'Kelly', 45)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57), row(222, "Joe", 72), row(333, "Kelly", 45));

            onTrino().executeQuery(format("ALTER TABLE %s ADD COLUMN new_state VARCHAR", tableName));
            log.info("This shows that for ORC, Presto and Hive both see data inserted into a dropped column when a column of the same type but different name is added");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"), row(333, "Kelly", 45, null));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, dataProvider = "transactionModeProvider")
    public void testOrcColumnTypeChange(boolean transactional)
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_orc_column_type_change", transactional, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (id INT, old_name VARCHAR, age TINYINT, old_state VARCHAR) WITH (transactional = %s)", tableName, transactional));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (111, 'Katy', 57, 'CA'), (222, 'Joe', 72, 'WA')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"));

            onHive().executeQuery(format("ALTER TABLE %s CHANGE COLUMN age age INT", tableName));
            log.info("This shows that Hive see the old data after a column is widened");
            assertThat(onHive().executeQuery("SELECT * FROM " + tableName))
                    .containsOnly(row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"));
            log.info("This shows that Trino gets an exception trying to widen the type");
            assertThat(() -> onTrino().executeQuery("SELECT * FROM " + tableName))
                    .failsWithMessageMatching(".*Malformed ORC file. Cannot read SQL type 'integer' from ORC stream '.*.age' of type BYTE with attributes.*");
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    public void testParquetColumnDropAdd()
    {
        ensureSchemaEvolutionSupported();
        withTemporaryTable("test_parquet_add_drop", false, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (id BIGINT, old_name VARCHAR, age INT, state VARCHAR) WITH (format = 'PARQUET')", tableName));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (111, 'Katy', 57, 'CA'), (222, 'Joe', 72, 'WA')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"));

            onTrino().executeQuery(format("ALTER TABLE %s DROP COLUMN state", tableName));
            log.info("This shows that neither Presto nor Hive see the old data after a column is dropped");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57), row(222, "Joe", 72));

            onTrino().executeQuery(format("INSERT INTO %s VALUES (333, 'Kelly', 45)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57), row(222, "Joe", 72), row(333, "Kelly", 45));

            onTrino().executeQuery(format("ALTER TABLE %s ADD COLUMN state VARCHAR", tableName));
            log.info("This shows that for Parquet, Presto and Hive both see data inserted into a dropped column when a column of the same name and type is added");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(111, "Katy", 57, "CA"), row(222, "Joe", 72, "WA"), row(333, "Kelly", 45, null));

            onTrino().executeQuery(format("ALTER TABLE %s DROP COLUMN state", tableName));
            onTrino().executeQuery(format("ALTER TABLE %s ADD COLUMN new_state VARCHAR", tableName));

            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(111, "Katy", 57, null), row(222, "Joe", 72, null), row(333, "Kelly", 45, null));
        });
    }

    @DataProvider
    public Object[][] transactionModeProvider()
    {
        return new Object[][] {
                {true},
                {false},
        };
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateFailNonTransactional()
    {
        withTemporaryTable("update_fail_nontransactional", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (customer VARCHAR, purchase VARCHAR)", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (customer, purchase) VALUES ('Fred', 'cards')", tableName));

            log.info("About to fail update");
            assertThat(() -> onTrino().executeQuery(format("UPDATE %s SET purchase = 'bread' WHERE customer = 'Fred'", tableName)))
                    .failsWithMessage("Hive update is only supported for transactional tables");
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateFailUpdatePartitionKey()
    {
        withTemporaryTable("fail_update_partition_key", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 INT, col2 VARCHAR, col3 BIGINT) WITH (transactional = true, partitioned_by = ARRAY['col3'])", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3) VALUES (17, 'S1', 7)", tableName));

            log.info("About to fail update");
            assertThat(() -> onTrino().executeQuery(format("UPDATE %s SET col3 = 17 WHERE col3 = 7", tableName)))
                    .failsWithMessage("Updating Hive table partition columns is not supported");
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateFailUpdateBucketColumn()
    {
        withTemporaryTable("fail_update_bucket_column", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (customer STRING, purchase STRING) CLUSTERED BY (purchase) INTO 3 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional' = 'true')", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (customer, purchase) VALUES ('Fred', 'cards')", tableName));

            log.info("About to fail update");
            assertThat(() -> onTrino().executeQuery(format("UPDATE %s SET purchase = 'bread' WHERE customer = 'Fred'", tableName)))
                    .failsWithMessage("Updating Hive table bucket columns is not supported");
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateFailOnIllegalCast()
    {
        withTemporaryTable("fail_update_on_illegal_cast", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 INT, col2 VARCHAR, col3 BIGINT) WITH (transactional = true)", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3) VALUES (17, 'S1', 7)", tableName));

            log.info("About to fail update");
            assertThat(() -> onTrino().executeQuery(format("UPDATE %s SET col1 = col2 WHERE col3 = 7", tableName)))
                    .failsWithMessage("UPDATE table column types don't match SET expressions: Table: [integer], Expressions: [varchar]");
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateSimple()
    {
        withTemporaryTable("acid_update_simple", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (7, 'ONE', 1000, true, 101), (13, 'TWO', 2000, false, 202)", tableName));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col2 = 'DEUX', col3 = col3 + 20 + col1 + col5 WHERE col1 = 13", tableName));
            log.info("Finished update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(7, "ONE", 1000, true, 101), row(13, "DEUX", 2235, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateSelectedValues()
    {
        withTemporaryTable("acid_update_simple", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (7, 'ONE', 1000, true, 101), (13, 'TWO', 2000, false, 202)", tableName));
            log.info("About to update %s", tableName);
            onTrino().executeQuery(format("UPDATE %s SET col2 = 'DEUX', col3 = col3 + 20 + col1 + col5 WHERE col1 = 13", tableName));
            log.info("Finished update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(7, "ONE", 1000, true, 101), row(13, "DEUX", 2235, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateCopyColumn()
    {
        withTemporaryTable("acid_update_copy_column", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 int, col2 int, col3 VARCHAR) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3) VALUES (7, 15, 'ONE'), (13, 17, 'DEUX')", tableName));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col1 = col2 WHERE col1 = 13", tableName));
            log.info("Finished update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(7, 15, "ONE"), row(17, 17, "DEUX"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateSomeLiteralNullColumnValues()
    {
        withTemporaryTable("update_some_literal_null_columns", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 'ONE', 1000, true, 101), (2, 'TWO', 2000, false, 202)", tableName));
            log.info("About to run first update");
            onTrino().executeQuery(format("UPDATE %s SET col2 = NULL, col3 = NULL WHERE col1 = 2", tableName));
            log.info("Finished first update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, "ONE", 1000, true, 101), row(2, null, null, false, 202));
            log.info("About to run second update");
            onTrino().executeQuery(format("UPDATE %s SET col1 = NULL, col2 = NULL, col3 = NULL, col4 = NULL WHERE col1 = 1", tableName));
            log.info("Finished first update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(null, null, null, null, 101), row(2, null, null, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateSomeComputedNullColumnValues()
    {
        withTemporaryTable("update_some_computed_null_columns", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 'ONE', 1000, true, 101), (2, 'TWO', 2000, false, 202)", tableName));
            log.info("About to run first update");
            // Use IF(RAND()<0, NULL) as a way to compute null
            onTrino().executeQuery(format("UPDATE %s SET col2 = IF(RAND()<0, NULL), col3 = IF(RAND()<0, NULL) WHERE col1 = 2", tableName));
            log.info("Finished first update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, "ONE", 1000, true, 101), row(2, null, null, false, 202));
            log.info("About to run second update");
            onTrino().executeQuery(format("UPDATE %s SET col1 = IF(RAND()<0, NULL), col2 = IF(RAND()<0, NULL), col3 = IF(RAND()<0, NULL), col4 = IF(RAND()<0, NULL) WHERE col1 = 1", tableName));
            log.info("Finished first update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(null, null, null, null, 101), row(2, null, null, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateAllLiteralNullColumnValues()
    {
        withTemporaryTable("update_all_literal_null_columns", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 'ONE', 1000, true, 101), (2, 'TWO', 2000, false, 202)", tableName));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col1 = NULL, col2 = NULL, col3 = NULL, col4 = NULL, col5 = null WHERE col1 = 1", tableName));
            log.info("Finished first update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(null, null, null, null, null), row(2, "TWO", 2000, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateAllComputedNullColumnValues()
    {
        withTemporaryTable("update_all_computed_null_columns", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 'ONE', 1000, true, 101), (2, 'TWO', 2000, false, 202)", tableName));
            log.info("About to update");
            // Use IF(RAND()<0, NULL) as a way to compute null
            onTrino().executeQuery(format("UPDATE %s SET col1 = IF(RAND()<0, NULL), col2 = IF(RAND()<0, NULL), col3 = IF(RAND()<0, NULL), col4 = IF(RAND()<0, NULL), col5 = IF(RAND()<0, NULL) WHERE col1 = 1", tableName));
            log.info("Finished first update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(null, null, null, null, null), row(2, "TWO", 2000, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateReversed()
    {
        withTemporaryTable("update_reversed", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 'ONE', 1000, true, 101), (2, 'TWO', 2000, false, 202)", tableName));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col3 = col3 + 20 + col1 + col5, col1 = 3, col2 = 'DEUX' WHERE col1 = 2", tableName));
            log.info("Finished update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, "ONE", 1000, true, 101), row(3, "DEUX", 2224, false, 202));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdatePermuted()
    {
        withTemporaryTable("update_permuted", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 VARCHAR, col3 BIGINT, col4 BOOLEAN, col5 INT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 'ONE', 1000, true, 101), (2, 'TWO', 2000, false, 202)", tableName));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col5 = 303, col1 = 3, col3 = col3 + 20 + col1 + col5, col4 = true, col2 = 'DUO' WHERE col1 = 2", tableName));
            log.info("Finished update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, "ONE", 1000, true, 101), row(3, "DUO", 2224, true, 303));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateAllColumnsSetAndDependencies()
    {
        withTemporaryTable("update_all_columns_set", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 INT, col3 BIGINT, col4 INT, col5 TINYINT) WITH (transactional = true)", tableName));
            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 2, 3, 4, 5), (21, 22, 23, 24, 25)", tableName));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col5 = col4, col1 = col3, col3 = col2, col4 = col5, col2 = col1 WHERE col1 = 21", tableName));
            log.info("Finished update");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 2, 3, 4, 5), row(23, 21, 22, 25, 24));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdatePartitioned()
    {
        withTemporaryTable("update_partitioned", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 INT, col2 VARCHAR, col3 BIGINT) WITH (transactional = true, partitioned_by = ARRAY['col3'])", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3) VALUES (13, 'T1', 3), (23, 'T2', 3), (17, 'S1', 7)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(13, "T1", 3), row(23, "T2", 3), row(17, "S1", 7));

            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET col1 = col1 + 1 WHERE col3 = 3 AND col1 > 15", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(13, "T1", 3), row(24, "T2", 3), row(17, "S1", 7));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateBucketed()
    {
        withTemporaryTable("update_bucketed", true, true, NONE, tableName -> {
            onHive().executeQuery(format("CREATE TABLE %s (customer STRING, purchase STRING) CLUSTERED BY (customer) INTO 3 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional' = 'true')", tableName));

            log.info("About to insert");
            onTrino().executeQuery(format("INSERT INTO %s (customer, purchase) VALUES ('Fred', 'cards'), ('Fred', 'limes'), ('Ann', 'lemons')", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row("Fred", "cards"), row("Fred", "limes"), row("Ann", "lemons"));

            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET purchase = 'bread' WHERE customer = 'Ann'", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row("Fred", "cards"), row("Fred", "limes"), row("Ann", "bread"));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testAcidUpdateMajorCompaction()
    {
        withTemporaryTable("schema_evolution_column_addition", true, false, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (column1 INT, column2 BIGINT) WITH (transactional = true)", tableName));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (11, 100)", tableName));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (22, 200)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(11, 100L), row(22, 200L));
            log.info("About to compact");
            compactTableAndWait(MAJOR, tableName, "", Duration.valueOf("6m"));
            log.info("About to update");
            onTrino().executeQuery(format("UPDATE %s SET column1 = 33 WHERE column2 = 200", tableName));
            log.info("About to select");
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(11, 100L), row(33, 200L));
            onTrino().executeQuery(format("INSERT INTO %s VALUES (44, 400), (55, 500)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(11, 100L), row(33, 200L), row(44, 400L), row(55, 500L));
            onTrino().executeQuery(format("DELETE FROM %s WHERE column2 IN (100, 500)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(33, 200L), row(44, 400L));
        });
    }

    @Flaky(issue = ERROR_COMMITTING_WRITE_TO_HIVE_ISSUE, match = ERROR_COMMITTING_WRITE_TO_HIVE_MATCH)
    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testInsertDeletUpdateWithPrestoAndHive()
    {
        withTemporaryTable("update_insert_delete_presto_hive", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s (col1 TINYINT, col2 INT, col3 BIGINT, col4 INT, col5 TINYINT) WITH (transactional = true)", tableName));

            log.info("Performing first insert on Presto");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (1, 2, 3, 4, 5), (21, 22, 23, 24, 25)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(1, 2, 3, 4, 5), row(21, 22, 23, 24, 25));

            log.info("Performing first update on Presto");
            onTrino().executeQuery(format("UPDATE %s SET col5 = col4, col1 = col3, col3 = col2, col4 = col5, col2 = col1 WHERE col1 = 21", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 2, 3, 4, 5), row(23, 21, 22, 25, 24));

            log.info("Performing second insert on Hive");
            onHive().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (31, 32, 33, 34, 35)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 2, 3, 4, 5), row(23, 21, 22, 25, 24), row(31, 32, 33, 34, 35));

            log.info("Performing first delete on Presto");
            onTrino().executeQuery(format("DELETE FROM %s WHERE col1 = 23", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 2, 3, 4, 5), row(31, 32, 33, 34, 35));

            log.info("Performing second update on Hive");
            onHive().executeQuery(format("UPDATE %s SET col5 = col4, col1 = col3, col3 = col2, col4 = col5, col2 = col1 WHERE col1 = 31", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "true", row(1, 2, 3, 4, 5), row(33, 31, 32, 35, 34));

            log.info("Performing more inserts on Presto");
            onTrino().executeQuery(format("INSERT INTO %s (col1, col2, col3, col4, col5) VALUES (41, 42, 43, 44, 45), (51, 52, 53, 54, 55)", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(1, 2, 3, 4, 5), row(33, 31, 32, 35, 34), row(41, 42, 43, 44, 45), row(51, 52, 53, 54, 55));

            log.info("Performing second delete on Hive");
            onHive().executeQuery(format("DELETE FROM %s WHERE col5 = 5", tableName));
            verifySelectForPrestoAndHive("SELECT * FROM " + tableName, "TRUE", row(33, 31, 32, 35, 34), row(41, 42, 43, 44, 45), row(51, 52, 53, 54, 55));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testDeletePartitionedTable()
    {
        withTemporaryTable("delete_partitioned", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s WITH (transactional = true, partitioned_by = ARRAY['regionkey'])" +
                    " AS SELECT nationkey, name, regionkey FROM tpch.tiny.nation", tableName));
            // SELECT before deletion may prime the cache on the Hive side
            verifySelectForPrestoAndHive("SELECT count(*) FROM " + tableName, "true", row(25));
            onTrino().executeQuery(format("DELETE FROM %s WHERE regionkey = 4 AND nationkey %% 10 = 3", tableName));
            verifySelectForPrestoAndHive("SELECT count(*) FROM " + tableName, "true", row(24));
        });
    }

    @Test(groups = HIVE_TRANSACTIONAL, timeOut = TEST_TIMEOUT)
    public void testDeleteWholePartition()
    {
        withTemporaryTable("delete_partitioned", true, true, NONE, tableName -> {
            onTrino().executeQuery(format("CREATE TABLE %s WITH (transactional = true, partitioned_by = ARRAY['regionkey'])" +
                    " AS SELECT nationkey, name, regionkey FROM tpch.tiny.nation", tableName));
            // SELECT before deletion may prime the cache on the Hive side
            verifySelectForPrestoAndHive("SELECT count(*) FROM " + tableName, "true", row(25));
            onTrino().executeQuery(format("DELETE FROM %s WHERE regionkey = 4", tableName));
            verifySelectForPrestoAndHive("SELECT count(*) FROM " + tableName, "true", row(20));
        });
    }

    @DataProvider
    public Object[][] insertersProvider()
    {
        return new Object[][] {
                {false, HiveOrPresto.HIVE, HiveOrPresto.PRESTO},
                {false, HiveOrPresto.PRESTO, HiveOrPresto.PRESTO},
                {true, HiveOrPresto.HIVE, HiveOrPresto.PRESTO},
                {true, HiveOrPresto.PRESTO, HiveOrPresto.PRESTO},
        };
    }

    private enum HiveOrPresto
    {
        HIVE,
        PRESTO
    }

    private static QueryResult execute(HiveOrPresto hiveOrPresto, String sql, QueryExecutor.QueryParam... params)
    {
        return executorFor(hiveOrPresto).executeQuery(sql, params);
    }

    private static QueryExecutor executorFor(HiveOrPresto hiveOrPresto)
    {
        switch (hiveOrPresto) {
            case HIVE:
                return onHive();
            case PRESTO:
                return onTrino();
        }
        throw new IllegalStateException("Unknown enum value " + hiveOrPresto);
    }

    @DataProvider
    public Object[][] inserterAndDeleterProvider()
    {
        return new Object[][] {
                {HiveOrPresto.HIVE, HiveOrPresto.PRESTO},
                {HiveOrPresto.PRESTO, HiveOrPresto.PRESTO},
                {HiveOrPresto.PRESTO, HiveOrPresto.HIVE}
        };
    }

    void withTemporaryTable(String rootName, boolean transactional, boolean isPartitioned, BucketingType bucketingType, Consumer<String> testRunner)
    {
        if (transactional) {
            ensureTransactionalHive();
        }
        String tableName = null;
        try (TemporaryHiveTable table = TemporaryHiveTable.temporaryHiveTable(tableName(rootName, isPartitioned, bucketingType))) {
            tableName = table.getName();
            testRunner.accept(tableName);
        }
    }

    @Test(groups = HIVE_TRANSACTIONAL)
    @Flaky(issue = "https://github.com/trinodb/trino/issues/5463", match = "Expected row count to be <4>, but was <6>")
    public void testFilesForAbortedTransactionsIgnored()
            throws Exception
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Hive transactional tables are supported with Hive version 3 or above");
        }

        String tableName = "test_aborted_transaction_table";
        onHive().executeQuery("" +
                "CREATE TABLE " + tableName + " (col INT) " +
                "STORED AS ORC " +
                "TBLPROPERTIES ('transactional'='true')");

        ThriftHiveMetastoreClient client = testHiveMetastoreClientFactory.createMetastoreClient();
        try {
            String selectFromOnePartitionsSql = "SELECT col FROM " + tableName + " ORDER BY COL";

            // Create `delta-A` file
            onHive().executeQuery("INSERT INTO TABLE " + tableName + " VALUES (1),(2)");
            QueryResult onePartitionQueryResult = query(selectFromOnePartitionsSql);
            assertThat(onePartitionQueryResult).containsExactly(row(1), row(2));

            String tableLocation = getTablePath(tableName);

            // Insert data to create a valid delta, which creates `delta-B`
            onHive().executeQuery("INSERT INTO TABLE " + tableName + " SELECT 3");

            // Simulate aborted transaction in Hive which has left behind a write directory and file (`delta-C` i.e `delta_0000003_0000003_0000`)
            long transaction = client.openTransaction("test");
            client.allocateTableWriteIds("default", tableName, Collections.singletonList(transaction)).get(0).getWriteId();
            client.abortTransaction(transaction);

            String deltaA = tableLocation + "/delta_0000001_0000001_0000";
            String deltaB = tableLocation + "/delta_0000002_0000002_0000";
            String deltaC = tableLocation + "/delta_0000003_0000003_0000";

            // Delete original `delta-B`, `delta-C`
            hdfsDeleteAll(deltaB);
            hdfsDeleteAll(deltaC);

            // Copy content of `delta-A` to `delta-B`
            hdfsCopyAll(deltaA, deltaB);

            // Verify that data from delta-A and delta-B is visible
            onePartitionQueryResult = query(selectFromOnePartitionsSql);
            assertThat(onePartitionQueryResult).containsOnly(row(1), row(1), row(2), row(2));

            // Copy content of `delta-A` to `delta-C` (which is an aborted transaction)
            hdfsCopyAll(deltaA, deltaC);

            // Verify that delta, corresponding to aborted transaction, is not getting read
            onePartitionQueryResult = query(selectFromOnePartitionsSql);
            assertThat(onePartitionQueryResult).containsOnly(row(1), row(1), row(2), row(2));
        }
        finally {
            client.close();
            onHive().executeQuery("DROP TABLE " + tableName);
        }
    }

    private void hdfsDeleteAll(String directory)
    {
        if (!hdfsClient.exist(directory)) {
            return;
        }
        for (String file : hdfsClient.listDirectory(directory)) {
            hdfsClient.delete(directory + "/" + file);
        }
    }

    private void hdfsCopyAll(String source, String target)
    {
        if (!hdfsClient.exist(target)) {
            hdfsClient.createDirectory(target);
        }
        for (String file : hdfsClient.listDirectory(source)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            hdfsClient.loadFile(source + "/" + file, bos);
            hdfsClient.saveFile(target + "/" + file, new ByteArrayInputStream(bos.toByteArray()));
        }
    }

    @DataProvider
    public Object[][] testCreateAcidTableDataProvider()
    {
        return new Object[][] {
                {false, BucketingType.NONE},
                {false, BucketingType.BUCKETED_DEFAULT},
                {false, BucketingType.BUCKETED_V1},
                {false, BucketingType.BUCKETED_V2},
                {true, BucketingType.NONE},
                {true, BucketingType.BUCKETED_DEFAULT},
        };
    }

    private static String hiveTableProperties(TransactionalTableType transactionalTableType, BucketingType bucketingType)
    {
        ImmutableList.Builder<String> tableProperties = ImmutableList.builder();
        tableProperties.addAll(transactionalTableType.getHiveTableProperties());
        tableProperties.addAll(bucketingType.getHiveTableProperties());
        tableProperties.add("'NO_AUTO_COMPACTION'='true'");
        return tableProperties.build().stream().collect(joining(",", "TBLPROPERTIES (", ")"));
    }

    private static String prestoTableProperties(TransactionalTableType transactionalTableType, boolean isPartitioned, BucketingType bucketingType)
    {
        ImmutableList.Builder<String> tableProperties = ImmutableList.builder();
        tableProperties.addAll(transactionalTableType.getPrestoTableProperties());
        tableProperties.addAll(bucketingType.getPrestoTableProperties("fcol", 4));
        if (isPartitioned) {
            tableProperties.add("partitioned_by = ARRAY['partcol']");
        }
        return tableProperties.build().stream().collect(joining(",", "WITH (", ")"));
    }

    private static void compactTableAndWait(CompactionMode compactMode, String tableName, String partitionString, Duration timeout)
    {
        log.info("Running %s compaction on %s", compactMode, tableName);

        Failsafe.with(
                new RetryPolicy<>()
                        .withMaxDuration(java.time.Duration.ofMillis(timeout.toMillis()))
                        .withMaxAttempts(Integer.MAX_VALUE))  // limited by MaxDuration
                .onFailure(event -> {
                    throw new IllegalStateException(format("Could not compact table %s in %d retries", tableName, event.getAttemptCount()), event.getFailure());
                })
                .onSuccess(event -> log.info("Finished %s compaction on %s in %s (%d tries)", compactMode, tableName, event.getElapsedTime(), event.getAttemptCount()))
                .run(() -> tryCompactingTable(compactMode, tableName, partitionString, Duration.valueOf("2m")));
    }

    private static void tryCompactingTable(CompactionMode compactMode, String tableName, String partitionString, Duration timeout)
            throws TimeoutException
    {
        Instant beforeCompactionStart = Instant.now();
        onHive().executeQuery(format("ALTER TABLE %s %s COMPACT '%s'", tableName, partitionString, compactMode.name())).getRowsCount();

        log.info("Started compactions after %s: %s", beforeCompactionStart, getTableCompactions(compactMode, tableName, Optional.empty()));

        long loopStart = System.nanoTime();
        while (true) {
            try {
                // Compaction takes couple of second so there is no need to check state more frequent than 1s
                Thread.sleep(1000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            // Since we disabled auto compaction for uniquely named table and every compaction is triggered in this test
            // we can expect that single compaction in given mode should complete before proceeding.
            List<Map<String, String>> startedCompactions = getTableCompactions(compactMode, tableName, Optional.of(beforeCompactionStart));
            verify(startedCompactions.size() < 2, "Expected at most 1 compaction");

            if (startedCompactions.isEmpty()) {
                log.info("Compaction has not started yet. Existing compactions: " + getTableCompactions(compactMode, tableName, Optional.empty()));
                continue;
            }

            String compactionState = startedCompactions.get(0).get("state");

            if (compactionState.equals("failed")) {
                log.info("Compaction has failed: %s", startedCompactions.get(0));
                // This will retry compacting table
                throw new IllegalStateException("Compaction has failed");
            }

            if (compactionState.equals("succeeded")) {
                return;
            }

            if (Duration.nanosSince(loopStart).compareTo(timeout) > 0) {
                log.info("Waiting for compaction has timed out: %s", startedCompactions.get(0));
                throw new TimeoutException("Compaction has timed out");
            }
        }
    }

    private static List<Map<String, String>> getTableCompactions(CompactionMode compactionMode, String tableName, Optional<Instant> startedAfter)
    {
        return Stream.of(onHive().executeQuery("SHOW COMPACTIONS")).flatMap(TestHiveTransactionalTable::mapRows)
                .filter(row -> isCompactionForTable(compactionMode, tableName, row))
                .filter(row -> {
                    if (startedAfter.isPresent()) {
                        try {
                            // start time is expressed in milliseconds
                            return Long.parseLong(row.get("start time")) >= startedAfter.get().truncatedTo(ChronoUnit.SECONDS).toEpochMilli();
                        }
                        catch (NumberFormatException ignored) {
                        }
                    }

                    return true;
                }).collect(toImmutableList());
    }

    private static Stream<Map<String, String>> mapRows(QueryResult result)
    {
        if (result.getRowsCount() == 0) {
            return Stream.of();
        }

        List<?> columnNames = result.row(0).stream()
                .filter(Objects::nonNull)
                .collect(toUnmodifiableList());

        ImmutableList.Builder<Map<String, String>> rows = ImmutableList.builder();
        for (int rowIndex = 1; rowIndex < result.getRowsCount(); rowIndex++) {
            ImmutableMap.Builder<String, String> singleRow = ImmutableMap.builder();
            List<?> row = result.row(rowIndex);

            for (int column = 0; column < columnNames.size(); column++) {
                String columnName = ((String) columnNames.get(column)).toLowerCase(ENGLISH);
                singleRow.put(columnName, (String) row.get(column));
            }

            rows.add(singleRow.build());
        }

        return rows.build().stream();
    }

    private static String tableName(String testName, boolean isPartitioned, BucketingType bucketingType)
    {
        return format("test_%s_%b_%s_%s", testName, isPartitioned, bucketingType.name(), randomTableSuffix());
    }

    private static boolean isCompactionForTable(CompactionMode compactMode, String tableName, Map<String, String> row)
    {
        return row.get("table").equals(tableName.toLowerCase(ENGLISH)) &&
                row.get("type").equals(compactMode.name());
    }

    public enum CompactionMode
    {
        MAJOR,
        MINOR,
        /**/;
    }

    private String makeInsertValues(int col1Value, int col2First, int col2Last)
    {
        checkArgument(col2First <= col2Last, "The first value %s must be less or equal to the last %s", col2First, col2Last);
        return IntStream.rangeClosed(col2First, col2Last).mapToObj(i -> format("(%s, %s)", col1Value, i)).collect(Collectors.joining(", "));
    }

    private void ensureTransactionalHive()
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Hive transactional tables are supported with Hive version 3 or above");
        }
    }

    private void ensureSchemaEvolutionSupported()
    {
        if (getHiveVersionMajor() < 3) {
            throw new SkipException("Hive schema evolution requires Hive version 3 or above");
        }
    }

    private static void verifySelectForPrestoAndHive(String select, String whereClause, QueryAssert.Row... rows)
    {
        verifySelect("onPresto", onTrino(), select, whereClause, rows);
        verifySelect("onHive", onHive(), select, whereClause, rows);
    }

    private static void verifySelect(String name, QueryExecutor executor, String select, String whereClause, QueryAssert.Row... rows)
    {
        String fullQuery = format("%s WHERE %s", select, whereClause);

        assertThat(executor.executeQuery(fullQuery))
                .describedAs(name)
                .containsOnly(rows);
    }
}
