/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.qos;

import java.util.Optional;
import java.util.function.Supplier;

import com.google.common.base.Preconditions;
import com.google.common.collect.EvictingQueue;
import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.qos.config.QosServiceRuntimeConfig;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.cassandra.sidecar.metrics.CassandraMetricsService;
import com.palantir.remoting3.clients.ClientConfigurations;
import com.palantir.remoting3.jaxrs.JaxRsClient;

public class QosResource implements QosService {

    private final Optional<CassandraMetricsService> cassandraMetricClient;
    private Supplier<QosServiceRuntimeConfig> config;
    private static final String GAUGE_ATTRIBUTE = "Value";
    private static MetricsManager metricsManager = new MetricsManager();
    private static EvictingQueue<PendingTaskMetric> queue = EvictingQueue.create(100);

    public QosResource(Supplier<QosServiceRuntimeConfig> config) {
        this.config = config;
        this.cassandraMetricClient = config.get().cassandraServiceConfig()
                .map(cassandraServiceConfig -> Optional.of(JaxRsClient.create(
                CassandraMetricsService.class,
                "qos-service",
                ClientConfigurations.of(cassandraServiceConfig))))
        .orElse(Optional.empty());
    }

    @Override
    public int getLimit(String client) {
        //TODO (hsaraogi): return long once the ratelimiter can handle it.
        int configLimit = config.get().clientLimits().getOrDefault(client, Integer.MAX_VALUE);
        int scaledLimit = (int) (configLimit * checkCassandraHealth());

        //TODO (hsaraogi): add client names as tags
        metricsManager.registerOrGetHistogram(QosResource.class, "scaledLimit").update(scaledLimit);
        return configLimit;
    }

    private double checkCassandraHealth() {
//        int readTimeoutCounter = getTimeoutCounter("Read");
        if (cassandraMetricClient.isPresent()) {
            Object numPendingCommitLogTasks = cassandraMetricClient.get().getMetric(
                    "CommitLog",
                    "PendingTasks",
                    GAUGE_ATTRIBUTE,
                    ImmutableMap.of());

            Preconditions.checkState(numPendingCommitLogTasks instanceof Integer,
                    "Expected type Integer, found %s",
                    numPendingCommitLogTasks.getClass());

            int numPendingCommitLogTasksInt = (int) numPendingCommitLogTasks;

            queue.add(ImmutablePendingTaskMetric.builder()
                    .numPendingTasks(numPendingCommitLogTasksInt)
                    .timetamp(System.currentTimeMillis())
                    .build());

            double averagePendingCommitLogTasks = queue.stream()
                    .mapToInt(PendingTaskMetric::numPendingTasks)
                    .average()
                    .getAsDouble();

            if (Double.compare(averagePendingCommitLogTasks, (double) numPendingCommitLogTasks) < 0) {
                return 1.0 -
                        ((numPendingCommitLogTasksInt - averagePendingCommitLogTasks) / numPendingCommitLogTasksInt);
            }
        }
        return 1.0;
    }
}
