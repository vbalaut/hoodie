/*
 * Copyright (c) 2016 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.hoodie.index;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import com.uber.hoodie.WriteStatus;
import com.uber.hoodie.common.model.HoodieDataFile;
import com.uber.hoodie.common.model.HoodieKey;
import com.uber.hoodie.common.model.HoodieRecord;
import com.uber.hoodie.common.model.HoodieRecordLocation;
import com.uber.hoodie.common.model.HoodieRecordPayload;
import com.uber.hoodie.common.table.timeline.HoodieInstant;
import com.uber.hoodie.common.util.FSUtils;
import com.uber.hoodie.config.HoodieWriteConfig;
import com.uber.hoodie.table.HoodieTable;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import scala.Tuple2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Indexing mechanism based on bloom filter. Each parquet file includes its row_key bloom filter in
 * its metadata.
 */
public class HoodieBloomIndex<T extends HoodieRecordPayload> extends HoodieIndex<T> {

    private static Logger logger = LogManager.getLogger(HoodieBloomIndex.class);

    // we need to limit the join such that it stays within 1.5GB per Spark partition. (SPARK-1476)
    private static final int SPARK_MAXIMUM_BYTES_PER_PARTITION = 1500 * 1024 * 1024;
    // this is how much a triplet of (partitionPath, fileId, recordKey) costs.
    private static final int BYTES_PER_PARTITION_FILE_KEY_TRIPLET = 300;
    private static int MAX_ITEMS_PER_JOIN_PARTITION = SPARK_MAXIMUM_BYTES_PER_PARTITION / BYTES_PER_PARTITION_FILE_KEY_TRIPLET;

    public HoodieBloomIndex(HoodieWriteConfig config, JavaSparkContext jsc) {
        super(config, jsc);
    }

    @Override
    public JavaRDD<HoodieRecord<T>> tagLocation(JavaRDD<HoodieRecord<T>> recordRDD, final HoodieTable<T> hoodieTable) {

        // Step 1: Extract out thinner JavaPairRDD of (partitionPath, recordKey)
        JavaPairRDD<String, String> partitionRecordKeyPairRDD = recordRDD
                .mapToPair(record -> new Tuple2<>(record.getPartitionPath(), record.getRecordKey()));

        // Lookup indexes for all the partition/recordkey pair
        JavaPairRDD<String, String> rowKeyFilenamePairRDD = lookupIndex(partitionRecordKeyPairRDD, hoodieTable);

        // Cache the result, for subsequent stages.
        rowKeyFilenamePairRDD.cache();
        long totalTaggedRecords = rowKeyFilenamePairRDD.count();
        logger.info("Number of update records (ones tagged with a fileID): " + totalTaggedRecords);


        // Step 4: Tag the incoming records, as inserts or updates, by joining with existing record keys
        // Cost: 4 sec.
        return tagLocationBacktoRecords(rowKeyFilenamePairRDD, recordRDD);
    }

    public JavaPairRDD<HoodieKey, Optional<String>> fetchRecordLocation(
            JavaRDD<HoodieKey> hoodieKeys, final HoodieTable<T> hoodieTable) {
        JavaPairRDD<String, String> partitionRecordKeyPairRDD =
                hoodieKeys.mapToPair(key -> new Tuple2<>(key.getPartitionPath(), key.getRecordKey()));

        // Lookup indexes for all the partition/recordkey pair
        JavaPairRDD<String, String> rowKeyFilenamePairRDD =
                lookupIndex(partitionRecordKeyPairRDD, hoodieTable);

        JavaPairRDD<String, HoodieKey> rowKeyHoodieKeyPairRDD =
                hoodieKeys.mapToPair(key -> new Tuple2<>(key.getRecordKey(), key));

        return rowKeyHoodieKeyPairRDD.leftOuterJoin(rowKeyFilenamePairRDD)
                .mapToPair(keyPathTuple -> {
                    Optional<String> recordLocationPath;
                    if (keyPathTuple._2._2.isPresent()) {
                        String fileName = keyPathTuple._2._2.get();
                        String partitionPath = keyPathTuple._2._1.getPartitionPath();
                        recordLocationPath = Optional.of(new Path(
                                new Path(hoodieTable.getMetaClient().getBasePath(), partitionPath),
                                fileName).toUri().getPath());
                    } else {
                        recordLocationPath = Optional.absent();
                    }
                    return new Tuple2<>(keyPathTuple._2._1, recordLocationPath);
                });
    }

    /**
     * Lookup the location for each record key and return the pair<record_key,location> for all
     * record keys already present and drop the record keys if not present
     */
    private JavaPairRDD<String, String> lookupIndex(
            JavaPairRDD<String, String> partitionRecordKeyPairRDD, final HoodieTable<T> hoodieTable) {
        // Obtain records per partition, in the incoming records
        Map<String, Long> recordsPerPartition = partitionRecordKeyPairRDD.countByKey();
        List<String> affectedPartitionPathList = new ArrayList<>(recordsPerPartition.keySet());

        // Step 2: Load all involved files as <Partition, filename> pairs
        JavaPairRDD<String, String> partitionFilePairRDD =
                loadInvolvedFiles(affectedPartitionPathList, hoodieTable);
        Map<String, Long> filesPerPartition = partitionFilePairRDD.countByKey();

        // Compute total subpartitions, to split partitions into.
        Map<String, Long> subpartitionCountMap =
                computeSubPartitions(recordsPerPartition, filesPerPartition);

        // Step 3: Obtain a RDD, for each incoming record, that already exists, with the file id, that contains it.
        return findMatchingFilesForRecordKeys(partitionFilePairRDD, partitionRecordKeyPairRDD,
                subpartitionCountMap);
    }

    /**
     * The index lookup can be skewed in three dimensions : #files, #partitions, #records
     *
     * To be able to smoothly handle skews, we need to compute how to split each partitions into
     * subpartitions. We do it here, in a way that keeps the amount of each Spark join partition to
     * < 2GB.
     */
    private Map<String, Long> computeSubPartitions(Map<String, Long> recordsPerPartition, Map<String, Long> filesPerPartition) {
        Map<String, Long> subpartitionCountMap = new HashMap<>();
        long totalRecords = 0;
        long totalFiles = 0;

        for (String partitionPath : recordsPerPartition.keySet()) {
            long numRecords = recordsPerPartition.get(partitionPath);
            long numFiles = filesPerPartition.containsKey(partitionPath) ? filesPerPartition.get(partitionPath) : 1L;
            subpartitionCountMap.put(partitionPath, ((numFiles * numRecords) / MAX_ITEMS_PER_JOIN_PARTITION) + 1);

            totalFiles += filesPerPartition.containsKey(partitionPath) ? filesPerPartition.get(partitionPath) : 0L;
            totalRecords += numRecords;
        }
        logger.info("TotalRecords: " + totalRecords + ", TotalFiles: " + totalFiles + ", TotalAffectedPartitions:" + recordsPerPartition.size());
        logger.info("Sub Partition Counts : " + subpartitionCountMap);
        return subpartitionCountMap;
    }

    /**
     * Load the input records as <Partition, RowKeys> in memory.
     */
    @VisibleForTesting
    Map<String, Iterable<String>> getPartitionToRowKeys(JavaRDD<HoodieRecord<T>> recordRDD) {
        // Have to wrap the map into a hashmap becuase of the need to braoadcast (see: http://php.sabscape.com/blog/?p=671)
        return recordRDD.mapToPair(record -> new Tuple2<>(record.getPartitionPath(), record.getRecordKey()))
                .groupByKey().collectAsMap();
    }

    /**
     * Load all involved files as <Partition, filename> pair RDD.
     */
    @VisibleForTesting
    JavaPairRDD<String, String> loadInvolvedFiles(List<String> partitions,
                                                  final HoodieTable<T> hoodieTable) {
        return jsc.parallelize(partitions, Math.max(partitions.size(), 1))
                .flatMapToPair(partitionPath -> {
                    java.util.Optional<HoodieInstant> latestCommitTime =
                            hoodieTable.getCommitTimeline().filterCompletedInstants().lastInstant();
                    List<Tuple2<String, String>> list = new ArrayList<>();
                    if (latestCommitTime.isPresent()) {
                        List<HoodieDataFile> filteredFiles =
                                hoodieTable.getFileSystemView().getLatestVersionInPartition(partitionPath,
                                        latestCommitTime.get().getTimestamp()).collect(Collectors.toList());
                        for (HoodieDataFile file : filteredFiles) {
                            list.add(new Tuple2<>(partitionPath, file.getFileName()));
                        }
                    }
                    return list.iterator();
                });
    }


    @Override
    public boolean rollbackCommit(String commitTime) {
        // Nope, don't need to do anything.
        return true;
    }


    /**
     * When we subpartition records going into a partition, we still need to check them against all
     * the files within the partition. Thus, we need to explode the (partition, file) pairs to
     * (partition_subpartnum, file), so we can later join.
     */
    private JavaPairRDD<String, String> explodePartitionFilePairRDD(JavaPairRDD<String, String> partitionFilePairRDD,
                                                                    final Map<String, Long> subpartitionCountMap) {
        return partitionFilePairRDD
                .map(partitionFilePair -> {
                    List<Tuple2<String, String>> explodedPartitionFilePairs = new ArrayList<>();
                    for (long l = 0; l < subpartitionCountMap.get(partitionFilePair._1); l++) {
                        explodedPartitionFilePairs.add(new Tuple2<>(
                                String.format("%s#%d", partitionFilePair._1, l),
                                partitionFilePair._2));
                    }
                    return explodedPartitionFilePairs;
                })
                .flatMapToPair(exploded -> exploded.iterator());
    }

    /**
     * To handle tons of incoming records to a partition, we need to split them into groups or
     * create subpartitions. Here, we do a simple hash mod splitting, based on computed sub
     * partitions.
     */
    private JavaPairRDD<String, String> splitPartitionRecordKeysPairRDD(JavaPairRDD<String, String> partitionRecordKeyPairRDD,
                                                                        final Map<String, Long> subpartitionCountMap) {
        return partitionRecordKeyPairRDD
                .mapToPair(partitionRecordKeyPair -> {
                    long subpart = Math.abs(partitionRecordKeyPair._2.hashCode()) % subpartitionCountMap.get(partitionRecordKeyPair._1);
                    return new Tuple2<>(
                            String.format("%s#%d", partitionRecordKeyPair._1, subpart),
                            partitionRecordKeyPair._2);
                });
    }


    /**
     * Its crucial to pick the right parallelism.
     *
     * totalSubPartitions : this is deemed safe limit, to be nice with Spark. inputParallelism :
     * typically number of input files.
     *
     * We pick the max such that, we are always safe, but go higher if say a there are a lot of
     * input files. (otherwise, we will fallback to number of partitions in input and end up with
     * slow performance)
     */
    private int determineParallelism(int inputParallelism, final Map<String, Long> subpartitionCountMap) {
        // size the join parallelism to max(total number of sub partitions, total number of files).
        int totalSubparts = 0;
        for (long subparts : subpartitionCountMap.values()) {
            totalSubparts += (int) subparts;
        }
        // If bloom index parallelism is set, use it to to check against the input parallelism and take the max
        int indexParallelism = Math.max(inputParallelism, config.getBloomIndexParallelism());
        int joinParallelism = Math.max(totalSubparts, indexParallelism);
        logger.info("InputParallelism: ${" + inputParallelism + "}, " +
                "IndexParallelism: ${" + config.getBloomIndexParallelism() + "}, " +
                "TotalSubParts: ${" + totalSubparts + "}, " +
                "Join Parallelism set to : " + joinParallelism);
        return joinParallelism;
    }


    /**
     * Find out <RowKey, filename> pair. All workload grouped by file-level.
     *
     * // Join PairRDD(PartitionPath, RecordKey) and PairRDD(PartitionPath, File) & then repartition
     * such that // each RDD partition is a file, then for each file, we do (1) load bloom filter,
     * (2) load rowKeys, (3) Tag rowKey // Make sure the parallelism is atleast the groupby
     * parallelism for tagging location
     */
    private JavaPairRDD<String, String> findMatchingFilesForRecordKeys(JavaPairRDD<String, String> partitionFilePairRDD,
                                                                       JavaPairRDD<String, String> partitionRecordKeyPairRDD,
                                                                       final Map<String, Long> subpartitionCountMap) {

        // prepare the two RDDs and their join parallelism
        JavaPairRDD<String, String> subpartitionFilePairRDD = explodePartitionFilePairRDD(partitionFilePairRDD, subpartitionCountMap);
        JavaPairRDD<String, String> subpartitionRecordKeyPairRDD = splitPartitionRecordKeysPairRDD(partitionRecordKeyPairRDD,
                subpartitionCountMap);
        int joinParallelism = determineParallelism(partitionRecordKeyPairRDD.partitions().size(), subpartitionCountMap);

        // Perform a join, to bring all the files in each subpartition ,together with the record keys to be tested against them
        JavaPairRDD<String, Tuple2<String, String>> joinedTripletRDD = subpartitionFilePairRDD
                .join(subpartitionRecordKeyPairRDD, joinParallelism);

        // sort further based on filename, such that all checking for the file can happen within a single partition, on-the-fly
        JavaPairRDD<String, Tuple2<String, HoodieKey>> fileSortedTripletRDD = joinedTripletRDD
                /**
                 * Incoming triplet is (partitionPath_subpart) => (file, recordKey)
                 */
                .mapToPair(joinedTriplet -> {
                    String partitionPath = joinedTriplet._1.split("#")[0]; // throw away the subpart
                    String fileName = joinedTriplet._2._1;
                    String recordKey = joinedTriplet._2._2;

                    // make a sort key as <file>#<recordKey>, to handle skews
                    return new Tuple2<>(String.format("%s#%s", fileName, recordKey),
                            new Tuple2<>(fileName, new HoodieKey(recordKey, partitionPath)));
                }).sortByKey(true, joinParallelism);

        return fileSortedTripletRDD
                .mapPartitionsWithIndex(new HoodieBloomIndexCheckFunction(config.getBasePath()), true)
                .flatMap(indexLookupResults -> indexLookupResults.iterator())
                .filter(lookupResult -> lookupResult.getMatchingRecordKeys().size() > 0)
                .flatMapToPair(lookupResult -> {
                    List<Tuple2<String, String>> vals = new ArrayList<>();
                    for (String recordKey : lookupResult.getMatchingRecordKeys()) {
                        vals.add(new Tuple2<>(recordKey, lookupResult.getFileName()));
                    }
                    return vals.iterator();
                });
    }

    /**
     * Tag the <rowKey, filename> back to the original HoodieRecord RDD.
     */
    private JavaRDD<HoodieRecord<T>> tagLocationBacktoRecords(JavaPairRDD<String, String> rowKeyFilenamePairRDD,
                                                              JavaRDD<HoodieRecord<T>> recordRDD) {
        JavaPairRDD<String, HoodieRecord<T>> rowKeyRecordPairRDD = recordRDD
                .mapToPair(record -> new Tuple2<>(record.getRecordKey(), record));

        // Here as the recordRDD might have more data than rowKeyRDD (some rowKeys' fileId is null), so we do left outer join.
        return rowKeyRecordPairRDD.leftOuterJoin(rowKeyFilenamePairRDD).values().map(
                v1 -> {
                    HoodieRecord<T> record = v1._1();
                    if (v1._2().isPresent()) {
                        String filename = v1._2().get();
                        if (filename != null && !filename.isEmpty()) {
                            record.setCurrentLocation(new HoodieRecordLocation(FSUtils.getCommitTime(filename),
                                    FSUtils.getFileId(filename)));
                        }
                    }
                    return record;
                }
        );
    }

    @Override
    public JavaRDD<WriteStatus> updateLocation(JavaRDD<WriteStatus> writeStatusRDD, HoodieTable<T> hoodieTable) {
        return writeStatusRDD;
    }
}
