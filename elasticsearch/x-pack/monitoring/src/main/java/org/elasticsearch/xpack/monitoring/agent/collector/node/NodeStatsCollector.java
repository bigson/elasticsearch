/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.agent.collector.node;

import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsRequest;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.CommonStatsFlags;
import org.elasticsearch.bootstrap.BootstrapInfo;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.license.plugin.core.XPackLicenseState;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.agent.collector.AbstractCollector;
import org.elasticsearch.xpack.monitoring.agent.exporter.MonitoringDoc;
import org.elasticsearch.xpack.security.InternalClient;

import java.util.Collection;
import java.util.Collections;

/**
 * Collector for nodes statistics.
 * <p>
 * This collector runs on every non-client node and collect
 * a {@link NodeStatsMonitoringDoc} document for each node of the cluster.
 */
public class NodeStatsCollector extends AbstractCollector {

    public static final String NAME = "node-stats-collector";

    private final Client client;
    private final NodeEnvironment nodeEnvironment;

    private final DiskThresholdDecider diskThresholdDecider;

    @Inject
    public NodeStatsCollector(Settings settings, ClusterService clusterService, MonitoringSettings monitoringSettings,
                              XPackLicenseState licenseState, InternalClient client,
                              NodeEnvironment nodeEnvironment, DiskThresholdDecider diskThresholdDecider) {
        super(settings, NAME, clusterService, monitoringSettings, licenseState);
        this.client = client;
        this.nodeEnvironment = nodeEnvironment;
        this.diskThresholdDecider = diskThresholdDecider;
    }

    @Override
    protected boolean shouldCollect() {
        // In some cases, the collector starts to collect nodes stats but the
        // NodeEnvironment is not fully initialized (NodePath is null) and can fail.
        // This why we need to check for nodeEnvironment.hasNodeFile() here, but only
        // for nodes that can hold data. Client nodes can collect nodes stats because
        // elasticsearch correctly handles the nodes stats for client nodes.
        return super.shouldCollect()
                && (DiscoveryNode.nodeRequiresLocalStorage(settings) == false || nodeEnvironment.hasNodeFile());
    }

    @Override
    protected Collection<MonitoringDoc> doCollect() throws Exception {
        NodesStatsRequest request = new NodesStatsRequest("_local");
        request.indices(CommonStatsFlags.ALL);
        request.os(true);
        request.jvm(true);
        request.process(true);
        request.threadPool(true);
        request.fs(true);

        NodesStatsResponse response = client.admin().cluster().nodesStats(request).actionGet();

        // if there's a failure, then we failed to work with the _local node (guaranteed a single exception)
        if (response.hasFailures()) {
            throw response.failures().get(0);
        }

        NodeStats nodeStats = response.getNodes().get(0);

        // Here we are calling directly the DiskThresholdDecider to retrieve the high watermark value
        // It would be nicer to use a settings API like documented in #6732
        Double diskThresholdWatermarkHigh = (diskThresholdDecider != null) ? 100.0 - diskThresholdDecider.getFreeDiskThresholdHigh() : -1;
        boolean diskThresholdDeciderEnabled = (diskThresholdDecider != null) && diskThresholdDecider.isEnabled();

        DiscoveryNode sourceNode = localNode();

        NodeStatsMonitoringDoc nodeStatsDoc = new NodeStatsMonitoringDoc(monitoringId(), monitoringVersion());
        nodeStatsDoc.setClusterUUID(clusterUUID());
        nodeStatsDoc.setTimestamp(System.currentTimeMillis());
        nodeStatsDoc.setSourceNode(sourceNode);
        nodeStatsDoc.setNodeId(sourceNode.getId());
        nodeStatsDoc.setNodeMaster(isLocalNodeMaster());
        nodeStatsDoc.setNodeStats(nodeStats);
        nodeStatsDoc.setMlockall(BootstrapInfo.isMemoryLocked());
        nodeStatsDoc.setDiskThresholdWaterMarkHigh(diskThresholdWatermarkHigh);
        nodeStatsDoc.setDiskThresholdDeciderEnabled(diskThresholdDeciderEnabled);

        return Collections.singletonList(nodeStatsDoc);
    }
}
