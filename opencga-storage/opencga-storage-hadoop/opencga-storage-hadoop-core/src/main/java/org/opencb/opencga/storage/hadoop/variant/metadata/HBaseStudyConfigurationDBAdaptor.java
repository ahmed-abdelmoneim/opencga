/*
 * Copyright 2015-2017 OpenCB
 *
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

package org.opencb.opencga.storage.hadoop.variant.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.StopWatch;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.utils.CompressionUtils;
import org.opencb.opencga.storage.core.metadata.StudyConfiguration;
import org.opencb.opencga.storage.core.metadata.StudyConfigurationAdaptor;
import org.opencb.opencga.storage.core.variant.io.json.mixin.GenericRecordAvroJsonMixin;
import org.opencb.opencga.storage.hadoop.utils.HBaseLock;
import org.opencb.opencga.storage.hadoop.utils.HBaseManager;
import org.opencb.opencga.storage.hadoop.variant.GenomeHelper;
import org.opencb.opencga.storage.hadoop.variant.index.VariantTableHelper;
import org.opencb.opencga.storage.hadoop.variant.index.phoenix.VariantPhoenixKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.DataFormatException;

/**
 * Created on 12/11/15.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class HBaseStudyConfigurationDBAdaptor extends StudyConfigurationAdaptor {

    private static Logger logger = LoggerFactory.getLogger(HBaseStudyConfigurationDBAdaptor.class);

    private final byte[] studiesRow;
    private final byte[] studiesSummaryColumn;

    private final Configuration configuration;
    private final ObjectMap options;
    private final GenomeHelper genomeHelper;
    private final HBaseManager hBaseManager;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final HBaseLock lock;


    public HBaseStudyConfigurationDBAdaptor(String tableName, Configuration configuration, ObjectMap options) {
        this(tableName, configuration, options, null);
    }

    public HBaseStudyConfigurationDBAdaptor(String tableName, Configuration configuration, ObjectMap options, HBaseManager hBaseManager) {
        this.configuration = Objects.requireNonNull(configuration);
        this.tableName = Objects.requireNonNull(tableName);
        this.options = options;
        this.genomeHelper = new GenomeHelper(configuration);
        this.objectMapper = new ObjectMapper().addMixIn(GenericRecord.class, GenericRecordAvroJsonMixin.class);
        this.studiesRow = VariantPhoenixKeyFactory.generateVariantRowKey(GenomeHelper.DEFAULT_METADATA_ROW_KEY, 0);
        this.studiesSummaryColumn = VariantPhoenixKeyFactory.generateVariantRowKey(GenomeHelper.DEFAULT_METADATA_ROW_KEY, 0);
        if (hBaseManager == null) {
            this.hBaseManager = new HBaseManager(configuration);
        } else {
            // Create a new instance of HBaseManager to close only if needed
            this.hBaseManager = new HBaseManager(hBaseManager);
        }
        lock = new HBaseLock(this.hBaseManager, this.tableName, genomeHelper.getColumnFamily(), studiesRow);
    }

    @Override
    protected QueryResult<StudyConfiguration> getStudyConfiguration(int studyId, Long timeStamp, QueryOptions options) {
        logger.debug("Get StudyConfiguration " + studyId + " from DB " + tableName);
        return getStudyConfiguration(getStudies(options).inverse().get(studyId), timeStamp, options);
    }

    @Override
    public long lockStudy(int studyId, long lockDuration, long timeout) throws InterruptedException, TimeoutException {
        try {
            VariantTableHelper.createVariantTableIfNeeded(genomeHelper, tableName, hBaseManager.getConnection());
            return lock.lock(Bytes.toBytes(studyId + "_LOCK"), lockDuration, timeout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void unLockStudy(int studyId, long lockToken) {
        try {
            lock.unlock(Bytes.toBytes(studyId + "_LOCK"), lockToken);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public GenomeHelper getGenomeHelper() {
        return this.genomeHelper;
    }

    @Override
    protected QueryResult<StudyConfiguration> getStudyConfiguration(String studyName, Long timeStamp, QueryOptions options) {
        StopWatch watch = new StopWatch().start();
        String error = null;
        List<StudyConfiguration> studyConfigurationList = Collections.emptyList();
        logger.debug("Get StudyConfiguration {} from DB {}", studyName, tableName);
        if (StringUtils.isEmpty(studyName)) {
            return new QueryResult<>("", (int) watch.now(TimeUnit.MILLISECONDS),
                    studyConfigurationList.size(), studyConfigurationList.size(), "", "", studyConfigurationList);
        }
        Get get = new Get(studiesRow);
        byte[] columnQualifier = Bytes.toBytes(studyName);
        get.addColumn(genomeHelper.getColumnFamily(), columnQualifier);
        if (timeStamp != null) {
            try {
                get.setTimeRange(timeStamp + 1, Long.MAX_VALUE);
            } catch (IOException e) {
                //This should not happen ever.
                throw new IllegalArgumentException(e);
            }
        }

        try {
            if (hBaseManager.act(tableName, (table, admin) -> admin.tableExists(table.getName()))) {
                studyConfigurationList = hBaseManager.act(tableName, table -> {
                    Result result = table.get(get);
                    if (result.isEmpty()) {
                        return Collections.emptyList();
                    } else {
                        byte[] value = result.getValue(genomeHelper.getColumnFamily(), columnQualifier);
                        // Try to decompress value.
                        try {
                            value = CompressionUtils.decompress(value);
                        } catch (DataFormatException e) {
                            if (value[0] == '{') {
                                logger.debug("StudyConfiguration was not compressed", e);
                            } else {
                                throw new IllegalStateException("Problem reading StudyConfiguration "
                                        + studyName + " from table " + tableName, e);
                            }
                        }
                        StudyConfiguration studyConfiguration = objectMapper.readValue(value, StudyConfiguration.class);
                        return Collections.singletonList(studyConfiguration);
                    }
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Problem reading StudyConfiguration " + studyName + " from table " + tableName, e);
        }
        return new QueryResult<>("", (int) watch.now(TimeUnit.MILLISECONDS),
                studyConfigurationList.size(), studyConfigurationList.size(), "", error, studyConfigurationList);
    }

    @Override
    protected QueryResult updateStudyConfiguration(StudyConfiguration studyConfiguration, QueryOptions options) {
        long startTime = System.currentTimeMillis();
        String error = "";
        logger.info("Update StudyConfiguration {}", studyConfiguration.getStudyName());
        updateStudiesSummary(studyConfiguration.getStudyName(), studyConfiguration.getStudyId(), options);
        byte[] columnQualifier = Bytes.toBytes(studyConfiguration.getStudyName());

        studyConfiguration.getHeaders().clear(); // REMOVE: stored in Archive table

        try {
            hBaseManager.act(tableName, table -> {
                byte[] bytes = objectMapper.writeValueAsBytes(studyConfiguration);
                // Compress json
                // Avoid "java.lang.IllegalArgumentException: KeyValue size too large"
                bytes = CompressionUtils.compress(bytes);
                Put put = new Put(studiesRow);
                put.addColumn(genomeHelper.getColumnFamily(), columnQualifier, studyConfiguration.getTimeStamp(), bytes);
                table.put(put);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return new QueryResult<>("", (int) (System.currentTimeMillis() - startTime), 0, 0, "", error, Collections.emptyList());
    }

    @Override
    public BiMap<String, Integer> getStudies(QueryOptions options) {
        Get get = new Get(studiesRow);
        get.addColumn(genomeHelper.getColumnFamily(), studiesSummaryColumn);
        try {
            if (!hBaseManager.act(tableName, (table, admin) -> admin.tableExists(table.getName()))) {
                logger.debug("Get StudyConfiguration summary TABLE_NO_EXISTS");
                return HashBiMap.create();
            }
            return hBaseManager.act(tableName, table -> {
                Result result = table.get(get);
                if (result.isEmpty()) {
                    logger.debug("Get StudyConfiguration summary EMPTY");
                    return HashBiMap.create();
                } else {
                    byte[] value = result.getValue(genomeHelper.getColumnFamily(), studiesSummaryColumn);
                    Map<String, Integer> map = objectMapper.readValue(value, Map.class);
                    logger.debug("Get StudyConfiguration summary {}", map);

                    return HashBiMap.create(map);
                }
            });
        } catch (IOException e) {
            logger.warn("Get StudyConfiguration summary ERROR", e);
            throw new UncheckedIOException(e);
        }
    }

    private void updateStudiesSummary(String study, Integer studyId, QueryOptions options) {
        BiMap<String, Integer> studiesSummary = getStudies(options);
        if (study.isEmpty()) {
            throw new IllegalStateException("Can't save an study with empty StudyName");
        }
        if (studiesSummary.getOrDefault(study, Integer.MIN_VALUE).equals(studyId)) {
            //Nothing to update
            return;
        } else {
            studiesSummary.put(study, studyId);
            updateStudiesSummary(studiesSummary, options);
        }
    }

    private void updateStudiesSummary(BiMap<String, Integer> studies, QueryOptions options) {
        try {
            Connection connection = hBaseManager.getConnection();
            VariantTableHelper.createVariantTableIfNeeded(genomeHelper, tableName, connection);
            try (Table table = connection.getTable(TableName.valueOf(tableName))) {
                byte[] bytes = objectMapper.writeValueAsBytes(studies);
                Put put = new Put(studiesRow);
                put.addColumn(genomeHelper.getColumnFamily(), studiesSummaryColumn, bytes);
                table.put(put);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            hBaseManager.close();
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
