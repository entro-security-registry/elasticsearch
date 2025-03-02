/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.datastreams.lifecycle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.admin.cluster.settings.ClusterGetSettingsAction;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.admin.indices.flush.FlushResponse;
import org.elasticsearch.action.admin.indices.forcemerge.ForceMergeAction;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsAction;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.admin.indices.template.put.PutComposableIndexTemplateAction;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.datastreams.CreateDataStreamAction;
import org.elasticsearch.action.datastreams.GetDataStreamAction;
import org.elasticsearch.action.datastreams.ModifyDataStreamsAction;
import org.elasticsearch.action.datastreams.lifecycle.ErrorEntry;
import org.elasticsearch.action.datastreams.lifecycle.ExplainIndexDataStreamLifecycle;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.metadata.ComposableIndexTemplate;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamAction;
import org.elasticsearch.cluster.metadata.DataStreamLifecycle;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.datastreams.DataStreamsPlugin;
import org.elasticsearch.datastreams.lifecycle.action.ExplainDataStreamLifecycleAction;
import org.elasticsearch.datastreams.lifecycle.action.PutDataStreamLifecycleAction;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.MergePolicyConfig;
import org.elasticsearch.index.mapper.DateFieldMapper;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.xcontent.XContentType;
import org.junit.After;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.backingIndexEqualTo;
import static org.elasticsearch.cluster.metadata.IndexMetadata.APIBlock.READ_ONLY;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.DEFAULT_TIMESTAMP_FIELD;
import static org.elasticsearch.datastreams.lifecycle.DataStreamLifecycleService.DATA_STREAM_MERGE_POLICY_TARGET_FACTOR_SETTING;
import static org.elasticsearch.datastreams.lifecycle.DataStreamLifecycleService.DATA_STREAM_MERGE_POLICY_TARGET_FLOOR_SEGMENT_SETTING;
import static org.elasticsearch.datastreams.lifecycle.DataStreamLifecycleService.ONE_HUNDRED_MB;
import static org.elasticsearch.datastreams.lifecycle.DataStreamLifecycleService.TARGET_MERGE_FACTOR_VALUE;
import static org.elasticsearch.index.IndexSettings.LIFECYCLE_ORIGINATION_DATE;
import static org.elasticsearch.indices.ShardLimitValidator.SETTING_CLUSTER_MAX_SHARDS_PER_NODE;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class DataStreamLifecycleServiceIT extends ESIntegTestCase {
    private static final Logger logger = LogManager.getLogger(DataStreamLifecycleServiceIT.class);

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(DataStreamsPlugin.class, MockTransportService.TestPlugin.class);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        Settings.Builder settings = Settings.builder().put(super.nodeSettings(nodeOrdinal, otherSettings));
        settings.put(DataStreamLifecycleService.DATA_STREAM_LIFECYCLE_POLL_INTERVAL, "1s");
        settings.put(DataStreamLifecycle.CLUSTER_LIFECYCLE_DEFAULT_ROLLOVER_SETTING.getKey(), "min_docs=1,max_docs=1");
        return settings.build();
    }

    @After
    public void cleanup() {
        // we change SETTING_CLUSTER_MAX_SHARDS_PER_NODE in a test so let's make sure we clean it up even when the test fails
        updateClusterSettings(Settings.builder().putNull("*"));
    }

    public void testRolloverLifecycle() throws Exception {
        // empty lifecycle contains the default rollover
        DataStreamLifecycle lifecycle = new DataStreamLifecycle();

        putComposableIndexTemplate("id1", null, List.of("metrics-foo*"), null, null, lifecycle);
        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();

        indexDocs(dataStreamName, 1);

        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
            List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
            assertThat(backingIndices.size(), equalTo(2));
            String backingIndex = backingIndices.get(0).getName();
            assertThat(backingIndex, backingIndexEqualTo(dataStreamName, 1));
            String writeIndex = backingIndices.get(1).getName();
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));
        });
    }

    public void testRolloverAndRetention() throws Exception {
        DataStreamLifecycle lifecycle = DataStreamLifecycle.newBuilder().dataRetention(0).build();

        putComposableIndexTemplate("id1", null, List.of("metrics-foo*"), null, null, lifecycle);

        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();

        indexDocs(dataStreamName, 1);

        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
            List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
            assertThat(backingIndices.size(), equalTo(1));
            // we expect the data stream to have only one backing index, the write one, with generation 2
            // as generation 1 would've been deleted by the data stream lifecycle given the configuration
            String writeIndex = backingIndices.get(0).getName();
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));
        });
    }

    public void testOriginationDate() throws Exception {
        /*
         * In this test, we set up a datastream with 7 day retention. Then we add two indices to it -- one with an origination date 365
         * days ago, and one with an origination date 1 day ago. After data stream lifecycle runs, we expect the one with the old
         * origination date to have been deleted, and the one with the newer origination date to remain.
         */
        DataStreamLifecycle lifecycle = DataStreamLifecycle.newBuilder().dataRetention(TimeValue.timeValueDays(7)).build();

        putComposableIndexTemplate("id1", null, List.of("metrics-foo*"), null, null, lifecycle);

        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();

        indexDocs(dataStreamName, 1);

        String mapping = """
             {
                "properties":{
                    "@timestamp": {
                        "type": "date"
                    }
                }
            }""";
        PutComposableIndexTemplateAction.Request request = new PutComposableIndexTemplateAction.Request("id2");
        request.indexTemplate(
            new ComposableIndexTemplate(
                List.of("index_*"),
                new Template(null, CompressedXContent.fromJSON(mapping), null, null),
                null,
                null,
                null,
                null,
                null,
                null
            )
        );
        client().execute(PutComposableIndexTemplateAction.INSTANCE, request).actionGet();

        String indexWithOldOriginationDate = "index_old";
        long originTimeMillis = System.currentTimeMillis() - TimeValue.timeValueDays(365).millis();
        createIndex(indexWithOldOriginationDate, Settings.builder().put(LIFECYCLE_ORIGINATION_DATE, originTimeMillis).build());
        client().execute(
            ModifyDataStreamsAction.INSTANCE,
            new ModifyDataStreamsAction.Request(List.of(DataStreamAction.addBackingIndex(dataStreamName, indexWithOldOriginationDate)))
        ).get();

        String indexWithNewOriginationDate = "index_new";
        originTimeMillis = System.currentTimeMillis() - TimeValue.timeValueDays(1).millis();
        createIndex(indexWithNewOriginationDate, Settings.builder().put(LIFECYCLE_ORIGINATION_DATE, originTimeMillis).build());
        client().execute(
            ModifyDataStreamsAction.INSTANCE,
            new ModifyDataStreamsAction.Request(List.of(DataStreamAction.addBackingIndex(dataStreamName, indexWithNewOriginationDate)))
        ).get();

        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
            List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
            Set<String> indexNames = backingIndices.stream().map(index -> index.getName()).collect(Collectors.toSet());
            assertTrue(indexNames.contains("index_new"));
            assertFalse(indexNames.contains("index_old"));
        });
    }

    public void testUpdatingLifecycleAppliesToAllBackingIndices() throws Exception {
        DataStreamLifecycle lifecycle = new DataStreamLifecycle();

        putComposableIndexTemplate("id1", null, List.of("metrics-foo*"), null, null, lifecycle);

        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();
        client().admin().indices().rolloverIndex(new RolloverRequest(dataStreamName, null)).actionGet();
        client().admin().indices().rolloverIndex(new RolloverRequest(dataStreamName, null)).actionGet();
        int finalGeneration = 3;

        // Update the lifecycle of the data stream
        updateLifecycle(dataStreamName, TimeValue.timeValueMillis(1));
        // Verify that the retention has changed for all backing indices
        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            DataStream dataStream = getDataStreamResponse.getDataStreams().get(0).getDataStream();
            assertThat(dataStream.getName(), equalTo(dataStreamName));
            List<Index> backingIndices = dataStream.getIndices();
            assertThat(backingIndices.size(), equalTo(1));
            String writeIndex = dataStream.getWriteIndex().getName();
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, finalGeneration));
        });
    }

    public void testAutomaticForceMerge() throws Exception {
        /*
         * This test makes sure that (1) data stream lifecycle does _not_ call forcemerge on an index in the same data stream lifecycle
         * pass when it rolls over the index and that (2) it _does_ call forcemerge on an index that was rolled over in a previous data
         * stream lifecycle pass. It's harder than you would think to detect through the REST API that forcemerge has been called. The
         * reason is that segment merging happens automatically during indexing, and when forcemerge is called it likely does nothing
         * because all necessary merging has already happened automatically. So in order to detect whether forcemerge has been called, we
         * use a SendRequestBehavior in the MockTransportService to detect it.
         */
        DataStreamLifecycle lifecycle = new DataStreamLifecycle();
        disableDataStreamLifecycle();
        String dataStreamName = "metrics-foo";
        putComposableIndexTemplate(
            "id1",
            null,
            List.of(dataStreamName + "*"),
            Settings.builder()
                .put("index.number_of_replicas", 1)
                .put("index.number_of_shards", 1)
                .put(MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING.getKey(), ONE_HUNDRED_MB)
                .put(MergePolicyConfig.INDEX_MERGE_POLICY_MERGE_FACTOR_SETTING.getKey(), TARGET_MERGE_FACTOR_VALUE)
                .build(),
            null,
            lifecycle
        );
        // This is the set of all indices against which a ForceMergeAction has been run:
        final Set<String> forceMergedIndices = new HashSet<>();
        // Here we update the transport service on each node to record when a forcemerge action is called for an index:
        for (DiscoveryNode node : internalCluster().getInstance(ClusterService.class, internalCluster().getMasterName())
            .state()
            .getNodes()) {
            MockTransportService.getInstance(node.getName())
                .addRequestHandlingBehavior(ForceMergeAction.NAME + "[n]", (handler, request, channel, task) -> {
                    String index = ((IndicesRequest) request).indices()[0];
                    forceMergedIndices.add(index);
                    logger.info("Force merging {}", index);
                    handler.messageReceived(request, channel, task);
                });
        }

        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();
        int finalGeneration = randomIntBetween(3, 4);
        for (int currentGeneration = 1; currentGeneration < finalGeneration; currentGeneration++) {
            // This is currently the write index, but it will be rolled over as soon as data stream lifecycle runs:
            final String toBeRolledOverIndex = getBackingIndices(dataStreamName).get(currentGeneration - 1);
            for (int i = 0; i < randomIntBetween(10, 50); i++) {
                indexDocs(dataStreamName, randomIntBetween(1, 300));
                // Make sure the segments get written:
                FlushResponse flushResponse = indicesAdmin().flush(new FlushRequest(toBeRolledOverIndex)).actionGet();
                assertThat(flushResponse.getStatus(), equalTo(RestStatus.OK));
            }

            final String toBeForceMergedIndex;
            if (currentGeneration == 1) {
                toBeForceMergedIndex = null; // Not going to be used
            } else {
                toBeForceMergedIndex = getBackingIndices(dataStreamName).get(currentGeneration - 2);
            }
            int currentBackingIndexCount = currentGeneration;
            DataStreamLifecycleService dataStreamLifecycleService = internalCluster().getInstance(
                DataStreamLifecycleService.class,
                internalCluster().getMasterName()
            );
            ClusterService clusterService = internalCluster().getInstance(ClusterService.class, internalCluster().getMasterName());
            // run data stream lifecycle once
            dataStreamLifecycleService.run(clusterService.state());
            assertBusy(() -> {
                GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
                GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                    .actionGet();
                assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
                DataStream dataStream = getDataStreamResponse.getDataStreams().get(0).getDataStream();
                assertThat(dataStream.getName(), equalTo(dataStreamName));
                List<Index> backingIndices = dataStream.getIndices();
                assertThat(backingIndices.size(), equalTo(currentBackingIndexCount + 1));
                String writeIndex = dataStream.getWriteIndex().getName();
                assertThat(writeIndex, backingIndexEqualTo(dataStreamName, currentBackingIndexCount + 1));
                /*
                 * We only expect forcemerge to happen on the 2nd data stream lifecycle run and later, since on the first there's only the
                 *  single write index to be rolled over.
                 */
                if (currentBackingIndexCount > 1) {
                    assertThat(
                        "The segments for " + toBeForceMergedIndex + " were not merged",
                        forceMergedIndices.contains(toBeForceMergedIndex),
                        equalTo(true)
                    );
                }
                // We want to assert that when data stream lifecycle rolls over the write index it, it doesn't forcemerge it on that
                // iteration:
                assertThat(
                    "The segments for " + toBeRolledOverIndex + " were unexpectedly merged",
                    forceMergedIndices.contains(toBeRolledOverIndex),
                    equalTo(false)
                );
            });
        }
    }

    private static void disableDataStreamLifecycle() {
        updateClusterSettings(Settings.builder().put(DataStreamLifecycleService.DATA_STREAM_LIFECYCLE_POLL_INTERVAL, TimeValue.MAX_VALUE));
    }

    public void testErrorRecordingOnRollover() throws Exception {
        // empty lifecycle contains the default rollover
        DataStreamLifecycle lifecycle = new DataStreamLifecycle();
        /*
         * We set index.auto_expand_replicas to 0-1 so that if we get a single-node cluster it is not yellow. The cluster being yellow
         * could result in data stream lifecycle's automatic forcemerge failing, which would result in an unexpected error in the error
         * store.
         */
        putComposableIndexTemplate(
            "id1",
            null,
            List.of("metrics-foo*"),
            Settings.builder().put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1").build(),
            null,
            lifecycle
        );

        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();

        indexDocs(dataStreamName, 1);

        // let's allow one rollover to go through
        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
            List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
            assertThat(backingIndices.size(), equalTo(2));
            String backingIndex = backingIndices.get(0).getName();
            assertThat(backingIndex, backingIndexEqualTo(dataStreamName, 1));
            String writeIndex = backingIndices.get(1).getName();
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));
        });

        // prevent new indices from being created (ie. future rollovers)
        updateClusterSettings(Settings.builder().put(SETTING_CLUSTER_MAX_SHARDS_PER_NODE.getKey(), 1));

        indexDocs(dataStreamName, 1);

        assertBusy(() -> {
            String writeIndexName = getBackingIndices(dataStreamName).get(1);
            ErrorEntry writeIndexRolloverError = null;
            Iterable<DataStreamLifecycleService> lifecycleServices = internalCluster().getInstances(DataStreamLifecycleService.class);

            for (DataStreamLifecycleService lifecycleService : lifecycleServices) {
                writeIndexRolloverError = lifecycleService.getErrorStore().getError(writeIndexName);
                if (writeIndexRolloverError != null) {
                    break;
                }
            }

            assertThat(writeIndexRolloverError, is(notNullValue()));
            assertThat(writeIndexRolloverError.error(), containsString("maximum normal shards open"));
        });

        // let's reset the cluster max shards per node limit to allow rollover to proceed and check the error store is empty
        updateClusterSettings(Settings.builder().putNull("*"));

        assertBusy(() -> {
            List<String> backingIndices = getBackingIndices(dataStreamName);
            assertThat(backingIndices.size(), equalTo(3));
            String writeIndex = backingIndices.get(2);
            // rollover was successful and we got to generation 3
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 3));

            // we recorded the error against the previous write index (generation 2)
            // let's check there's no error recorded against it anymore
            String previousWriteInddex = backingIndices.get(1);
            Iterable<DataStreamLifecycleService> lifecycleServices = internalCluster().getInstances(DataStreamLifecycleService.class);

            for (DataStreamLifecycleService lifecycleService : lifecycleServices) {
                assertThat(lifecycleService.getErrorStore().getError(previousWriteInddex), nullValue());
            }
        });
    }

    public void testErrorRecordingOnRetention() throws Exception {
        // starting with a lifecycle without retention so we can rollover the data stream and manipulate the second generation index such
        // that its retention execution fails
        DataStreamLifecycle lifecycle = new DataStreamLifecycle();

        /*
         * We set index.auto_expand_replicas to 0-1 so that if we get a single-node cluster it is not yellow. The cluster being yellow
         * could result in data stream lifecycle's automatic forcemerge failing, which would result in an unexpected error in the error
         * store.
         */
        putComposableIndexTemplate(
            "id1",
            null,
            List.of("metrics-foo*"),
            Settings.builder().put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1").build(),
            null,
            lifecycle
        );

        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();
        indexDocs(dataStreamName, 1);

        // let's allow one rollover to go through
        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
            List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
            assertThat(backingIndices.size(), equalTo(2));
            String backingIndex = backingIndices.get(0).getName();
            assertThat(backingIndex, backingIndexEqualTo(dataStreamName, 1));
            String writeIndex = backingIndices.get(1).getName();
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));
        });

        String firstGenerationIndex = getBackingIndices(dataStreamName).get(0);

        // mark the first generation index as read-only so deletion fails when we enable the retention configuration
        updateIndexSettings(Settings.builder().put(READ_ONLY.settingName(), true), firstGenerationIndex);
        try {
            updateLifecycle(dataStreamName, TimeValue.timeValueSeconds(1));

            assertBusy(() -> {
                GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
                GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                    .actionGet();
                assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
                assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
                List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
                assertThat(backingIndices.size(), equalTo(2));
                String writeIndex = backingIndices.get(1).getName();
                assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));

                ErrorEntry recordedRetentionExecutionError = null;
                Iterable<DataStreamLifecycleService> lifecycleServices = internalCluster().getInstances(DataStreamLifecycleService.class);

                for (DataStreamLifecycleService lifecycleService : lifecycleServices) {
                    recordedRetentionExecutionError = lifecycleService.getErrorStore().getError(firstGenerationIndex);
                    if (recordedRetentionExecutionError != null) {
                        break;
                    }
                }

                assertThat(recordedRetentionExecutionError, is(notNullValue()));
                assertThat(recordedRetentionExecutionError.error(), containsString("blocked by: [FORBIDDEN/5/index read-only (api)"));
            });

            // let's mark the index as writeable and make sure it's deleted and the error store is empty
            updateIndexSettings(Settings.builder().put(READ_ONLY.settingName(), false), firstGenerationIndex);

            assertBusy(() -> {
                GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
                GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                    .actionGet();
                assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
                assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
                List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
                // data stream only has one index now
                assertThat(backingIndices.size(), equalTo(1));

                // error stores don't contain anything for the first generation index anymore
                Iterable<DataStreamLifecycleService> lifecycleServices = internalCluster().getInstances(DataStreamLifecycleService.class);
                for (DataStreamLifecycleService lifecycleService : lifecycleServices) {
                    assertThat(lifecycleService.getErrorStore().getError(firstGenerationIndex), nullValue());
                }
            });
        } finally {
            // when the test executes successfully this will not be needed however, otherwise we need to make sure the index is
            // "delete-able" for test cleanup
            try {
                updateIndexSettings(Settings.builder().put(READ_ONLY.settingName(), false), firstGenerationIndex);
            } catch (Exception e) {
                // index would be deleted if the test is successful
            }
        }
    }

    public void testDataLifecycleServiceConfiguresTheMergePolicy() throws Exception {
        DataStreamLifecycle lifecycle = new DataStreamLifecycle();

        putComposableIndexTemplate(
            "id1",
            null,
            List.of("metrics-foo*"),
            Settings.builder().put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1").build(),
            null,
            lifecycle
        );

        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();
        indexDocs(dataStreamName, 1);

        // let's allow one rollover to go through
        assertBusy(() -> {
            GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
            GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
                .actionGet();
            assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
            assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
            List<Index> backingIndices = getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices();
            assertThat(backingIndices.size(), equalTo(2));
            String backingIndex = backingIndices.get(0).getName();
            assertThat(backingIndex, backingIndexEqualTo(dataStreamName, 1));
            String writeIndex = backingIndices.get(1).getName();
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));
        });

        String firstGenerationIndex = getBackingIndices(dataStreamName).get(0);
        ClusterGetSettingsAction.Response response = client().execute(
            ClusterGetSettingsAction.INSTANCE,
            new ClusterGetSettingsAction.Request()
        ).get();
        Settings clusterSettings = response.persistentSettings();

        Integer targetFactor = DATA_STREAM_MERGE_POLICY_TARGET_FACTOR_SETTING.get(clusterSettings);
        ByteSizeValue targetFloor = DATA_STREAM_MERGE_POLICY_TARGET_FLOOR_SEGMENT_SETTING.get(clusterSettings);

        assertBusy(() -> {
            GetSettingsRequest getSettingsRequest = new GetSettingsRequest().indices(firstGenerationIndex).includeDefaults(true);
            GetSettingsResponse getSettingsResponse = client().execute(GetSettingsAction.INSTANCE, getSettingsRequest).actionGet();
            assertThat(
                getSettingsResponse.getSetting(firstGenerationIndex, MergePolicyConfig.INDEX_MERGE_POLICY_MERGE_FACTOR_SETTING.getKey()),
                is(targetFactor.toString())
            );
            assertThat(
                getSettingsResponse.getSetting(firstGenerationIndex, MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING.getKey()),
                is(targetFloor.getStringRep())
            );
        });

        // let's configure the data stream lifecycle service to configure a different merge policy for indices
        updateClusterSettings(
            Settings.builder()
                .put(DATA_STREAM_MERGE_POLICY_TARGET_FACTOR_SETTING.getKey(), 5)
                .put(DATA_STREAM_MERGE_POLICY_TARGET_FLOOR_SEGMENT_SETTING.getKey(), ByteSizeValue.ofMb(5))
        );

        // rollover to assert the second generation is configured with the new setting values (note that the first index _might_ pick up
        // the new settings as well if the data stream lifecycle runs often enough - every second in tests - and the index has not yet been
        // forcemerged)

        indexDocs(dataStreamName, 1);

        // let's allow one rollover to go through
        assertBusy(() -> {
            List<String> backingIndices = getBackingIndices(dataStreamName);
            assertThat(backingIndices.size(), equalTo(3));
        });

        String secondGenerationIndex = getBackingIndices(dataStreamName).get(1);
        // check the 2nd generation index picked up the new setting values
        assertBusy(() -> {
            GetSettingsRequest getSettingsRequest = new GetSettingsRequest().indices(secondGenerationIndex).includeDefaults(true);
            GetSettingsResponse getSettingsResponse = client().execute(GetSettingsAction.INSTANCE, getSettingsRequest).actionGet();
            assertThat(
                getSettingsResponse.getSetting(secondGenerationIndex, MergePolicyConfig.INDEX_MERGE_POLICY_MERGE_FACTOR_SETTING.getKey()),
                is("5")
            );
            assertThat(
                getSettingsResponse.getSetting(secondGenerationIndex, MergePolicyConfig.INDEX_MERGE_POLICY_FLOOR_SEGMENT_SETTING.getKey()),
                is(ByteSizeValue.ofMb(5).getStringRep())
            );
        });
    }

    private static List<String> getBackingIndices(String dataStreamName) {
        GetDataStreamAction.Request getDataStreamRequest = new GetDataStreamAction.Request(new String[] { dataStreamName });
        GetDataStreamAction.Response getDataStreamResponse = client().execute(GetDataStreamAction.INSTANCE, getDataStreamRequest)
            .actionGet();
        assertThat(getDataStreamResponse.getDataStreams().size(), equalTo(1));
        assertThat(getDataStreamResponse.getDataStreams().get(0).getDataStream().getName(), equalTo(dataStreamName));
        return getDataStreamResponse.getDataStreams().get(0).getDataStream().getIndices().stream().map(Index::getName).toList();
    }

    static void indexDocs(String dataStream, int numDocs) {
        BulkRequest bulkRequest = new BulkRequest();
        for (int i = 0; i < numDocs; i++) {
            String value = DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER.formatMillis(System.currentTimeMillis());
            bulkRequest.add(
                new IndexRequest(dataStream).opType(DocWriteRequest.OpType.CREATE)
                    .source(String.format(Locale.ROOT, "{\"%s\":\"%s\"}", DEFAULT_TIMESTAMP_FIELD, value), XContentType.JSON)
            );
        }
        BulkResponse bulkResponse = client().bulk(bulkRequest).actionGet();
        assertThat(bulkResponse.getItems().length, equalTo(numDocs));
        String backingIndexPrefix = DataStream.BACKING_INDEX_PREFIX + dataStream;
        for (BulkItemResponse itemResponse : bulkResponse) {
            assertThat(itemResponse.getFailureMessage(), nullValue());
            assertThat(itemResponse.status(), equalTo(RestStatus.CREATED));
            assertThat(itemResponse.getIndex(), startsWith(backingIndexPrefix));
        }
        indicesAdmin().refresh(new RefreshRequest(dataStream)).actionGet();
    }

    public void testReenableDataStreamLifecycle() throws Exception {
        // start with a lifecycle that's not enabled
        DataStreamLifecycle lifecycle = new DataStreamLifecycle(null, null, false);

        putComposableIndexTemplate("id1", null, List.of("metrics-foo*"), null, null, lifecycle);
        String dataStreamName = "metrics-foo";
        CreateDataStreamAction.Request createDataStreamRequest = new CreateDataStreamAction.Request(dataStreamName);
        client().execute(CreateDataStreamAction.INSTANCE, createDataStreamRequest).get();

        indexDocs(dataStreamName, 10);
        List<String> backingIndices = getBackingIndices(dataStreamName);
        {
            // backing index should not be managed
            String writeIndex = backingIndices.get(0);

            ExplainDataStreamLifecycleAction.Response dataStreamLifecycleExplainResponse = client().execute(
                ExplainDataStreamLifecycleAction.INSTANCE,
                new ExplainDataStreamLifecycleAction.Request(new String[] { writeIndex })
            ).actionGet();
            assertThat(dataStreamLifecycleExplainResponse.getIndices().size(), is(1));
            for (ExplainIndexDataStreamLifecycle index : dataStreamLifecycleExplainResponse.getIndices()) {
                assertThat(index.isManagedByLifecycle(), is(false));
            }
        }

        {
            // data stream has only one backing index
            assertThat(backingIndices.size(), equalTo(1));
            String writeIndex = backingIndices.get(0);
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 1));
        }

        // let's enable the data stream lifecycle and witness the rollover as we ingested 10 documents already

        client().execute(
            PutDataStreamLifecycleAction.INSTANCE,
            new PutDataStreamLifecycleAction.Request(new String[] { dataStreamName }, null, true)
        );

        assertBusy(() -> {
            List<String> currentBackingIndices = getBackingIndices(dataStreamName);
            assertThat(currentBackingIndices.size(), equalTo(2));
            String backingIndex = currentBackingIndices.get(0);
            assertThat(backingIndex, backingIndexEqualTo(dataStreamName, 1));
            String writeIndex = currentBackingIndices.get(1);
            assertThat(writeIndex, backingIndexEqualTo(dataStreamName, 2));
        });
    }

    static void putComposableIndexTemplate(
        String id,
        @Nullable String mappings,
        List<String> patterns,
        @Nullable Settings settings,
        @Nullable Map<String, Object> metadata,
        @Nullable DataStreamLifecycle lifecycle
    ) throws IOException {
        PutComposableIndexTemplateAction.Request request = new PutComposableIndexTemplateAction.Request(id);
        request.indexTemplate(
            new ComposableIndexTemplate(
                patterns,
                new Template(settings, mappings == null ? null : CompressedXContent.fromJSON(mappings), null, lifecycle),
                null,
                null,
                null,
                metadata,
                new ComposableIndexTemplate.DataStreamTemplate(),
                null
            )
        );
        client().execute(PutComposableIndexTemplateAction.INSTANCE, request).actionGet();
    }

    static void updateLifecycle(String dataStreamName, TimeValue dataRetention) {
        PutDataStreamLifecycleAction.Request putDataLifecycleRequest = new PutDataStreamLifecycleAction.Request(
            new String[] { dataStreamName },
            dataRetention
        );
        assertAcked(client().execute(PutDataStreamLifecycleAction.INSTANCE, putDataLifecycleRequest));
    }
}
