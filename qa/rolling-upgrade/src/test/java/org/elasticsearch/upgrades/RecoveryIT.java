/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.upgrades;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.elasticsearch.client.Response;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.yaml.ObjectPath;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

public class RecoveryIT extends ESRestTestCase {

    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveReposUponCompletion() {
        return true;
    }

    private enum CLUSTER_TYPE {
        OLD,
        MIXED,
        UPGRADED;

        public static CLUSTER_TYPE parse(String value) {
            switch (value) {
                case "old_cluster":
                    return OLD;
                case "mixed_cluster":
                    return MIXED;
                case "upgraded_cluster":
                    return UPGRADED;
                    default:
                        throw new AssertionError("unknown cluster type: " + value);
            }
        }
    }

    private final CLUSTER_TYPE clusterType = CLUSTER_TYPE.parse(System.getProperty("tests.rest.suite"));

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(super.restClientSettings())
            // increase the timeout here to 90 seconds to handle long waits for a green
            // cluster health. the waits for green need to be longer than a minute to
            // account for delayed shards
            .put(ESRestTestCase.CLIENT_RETRY_TIMEOUT, "90s")
            .put(ESRestTestCase.CLIENT_SOCKET_TIMEOUT, "90s")
            .build();
    }

    private void assertOK(Response response) {
        assertThat(response.getStatusLine().getStatusCode(), anyOf(equalTo(200), equalTo(201)));
    }

    private void ensureGreen() throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("wait_for_status", "green");
        params.put("wait_for_no_relocating_shards", "true");
        params.put("timeout", "70s");
        params.put("level", "shards");
        assertOK(client().performRequest("GET", "_cluster/health", params));
    }

    private void createIndex(String name, Settings settings) throws IOException {
        assertOK(client().performRequest("PUT", name, Collections.emptyMap(),
            new StringEntity("{ \"settings\": " + Strings.toString(settings) + " }", ContentType.APPLICATION_JSON)));
    }


    public void testHistoryUUIDIsGenerated() throws Exception {
        final String index = "index_history_uuid";
        if (clusterType == CLUSTER_TYPE.OLD) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1);
            createIndex(index, settings.build());
            ensureGreen();
        } else if (clusterType == CLUSTER_TYPE.UPGRADED) {
            ensureGreen();
            Response response = client().performRequest("GET", index + "/_stats", Collections.singletonMap("level", "shards"));
            assertOK(response);
            ObjectPath objectPath = ObjectPath.createFromResponse(response);
            List<Object> shardStats = objectPath.evaluate("indices." + index + ".shards.0");
            assertThat(shardStats, hasSize(2));
            String expectHistoryUUID = null;
            for (int shard = 0; shard < 2; shard++) {
                String nodeID = objectPath.evaluate("indices." + index + ".shards.0." + shard + ".routing.node");
                String historyUUID = objectPath.evaluate("indices." + index + ".shards.0." + shard + ".commit.user_data.history_uuid");
                assertThat("no history uuid found for shard on " + nodeID, historyUUID, notNullValue());
                if (expectHistoryUUID == null) {
                    expectHistoryUUID = historyUUID;
                } else {
                    assertThat("different history uuid found for shard on " + nodeID, historyUUID, equalTo(expectHistoryUUID));
                }
            }
        } else {
            // we are now in mixed cluster mode. we want to make sure the shard is fully allocated on the new node that was just
            // started in order not to run into delayed unassigned shards when we bring down the old node (there must be a fully valid
            // copy)
            ensureGreen();
        }
    }

}
