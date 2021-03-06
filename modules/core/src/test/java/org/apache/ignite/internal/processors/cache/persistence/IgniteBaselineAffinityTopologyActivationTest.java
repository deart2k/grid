/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.internal.processors.cache.persistence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cluster.BaselineNode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.WALMode;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.cluster.DetachedClusterNode;
import org.apache.ignite.internal.processors.cluster.BaselineTopology;
import org.apache.ignite.internal.processors.cluster.BaselineTopologyHistory;
import org.apache.ignite.internal.processors.cluster.BaselineTopologyHistoryItem;
import org.apache.ignite.internal.processors.cluster.DiscoveryDataClusterState;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Assert;

/**
 *
 */
public class IgniteBaselineAffinityTopologyActivationTest extends GridCommonAbstractTest {
    /** */
    private String consId;

    /** Entries count to add to cache. */
    private static final int ENTRIES_COUNT = 100;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        if (consId != null)
            cfg.setConsistentId(consId);

        cfg.setDataStorageConfiguration(
            new DataStorageConfiguration().setDefaultDataRegionConfiguration(
                new DataRegionConfiguration()
                    .setPersistenceEnabled(true).setMaxSize(10 * 1024 * 1024)

            ).setWalMode(WALMode.LOG_ONLY)
        );

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        GridTestUtils.deleteDbFiles();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids(false);

        GridTestUtils.deleteDbFiles();
    }

    /**
     * Verifies that when old but compatible node
     * (it is the node that once wasn't presented in branchingHistory but hasn't participated in any branching point)
     * joins the cluster after restart, cluster gets activated.
     */
    public void testAutoActivationWithCompatibleOldNode() throws Exception {
        startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C").active(true);

        stopAllGrids(false);

        startGridWithConsistentId("A");
        startGridWithConsistentId("B").active(true);

        {
            IgniteEx nodeA = grid("A");

            assertNotNull(nodeA.cluster().currentBaselineTopology());
            assertEquals(3, nodeA.cluster().currentBaselineTopology().size());

            assertTrue(nodeA.cluster().active());
        }

        stopAllGrids(false);

        startGridWithConsistentId("A");
        startGridWithConsistentId("B");

        {
            IgniteEx nodeA = grid("A");

            assertNotNull(nodeA.cluster().currentBaselineTopology());
            assertEquals(3, nodeA.cluster().currentBaselineTopology().size());

            assertFalse(nodeA.cluster().active());
        }

        final Ignite nodeC = startGridWithConsistentId("C");

        boolean active = GridTestUtils.waitForCondition(
            new GridAbsPredicate() {
                @Override public boolean apply() {
                    return nodeC.active();
                }
            },
            10_000
        );

        assertTrue(active);
    }

    /**
     * IgniteCluster::setBaselineTopology(long topVer) should throw an exception
     * when online node from current BaselineTopology is not presented in topology version.
     */
    public void testBltChangeTopVerRemoveOnlineNodeFails() throws Exception {
        Ignite ignite = startGridWithConsistentId("A");

        ignite.active(true);

        long singleNodeTopVer = ignite.cluster().topologyVersion();

        startGridWithConsistentId("OnlineConsID");

        ignite.cluster().setBaselineTopology(baselineNodes(ignite.cluster().forServers().nodes()));

        boolean expectedExceptionThrown = false;

        try {
            ignite.cluster().setBaselineTopology(singleNodeTopVer);
        }
        catch (IgniteException e) {
            String errMsg = e.getMessage();
            assertTrue(errMsg.startsWith("Removing online nodes"));
            assertTrue(errMsg.contains("[OnlineConsID]"));

            expectedExceptionThrown = true;
        }

        assertTrue(expectedExceptionThrown);
    }

    /**
     * Verifies that online nodes cannot be removed from BaselineTopology (this may change in future).
     */
    public void testOnlineNodesCannotBeRemovedFromBaselineTopology() throws Exception {
        Ignite nodeA = startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("OnlineConsID");

        nodeC.active(true);

        boolean expectedExceptionIsThrown = false;

        try {
            nodeC.cluster().setBaselineTopology(Arrays.asList((BaselineNode) nodeA.cluster().localNode(),
                nodeB.cluster().localNode()));
        } catch (IgniteException e) {
            String errMsg = e.getMessage();
            assertTrue(errMsg.startsWith("Removing online nodes"));
            assertTrue(errMsg.contains("[OnlineConsID]"));

            expectedExceptionIsThrown = true;
        }

        assertTrue(expectedExceptionIsThrown);
    }

    /**
     *
     */
    public void testNodeFailsToJoinWithIncompatiblePreviousBaselineTopology() throws Exception {
        startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("C");

        nodeC.active(true);

        stopAllGrids(false);

        Ignite nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B").active(true);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        stopAllGrids(false);

        startGridWithConsistentId("C").active(true);

        stopGrid("C", false);

        startGridWithConsistentId("A");
        startGridWithConsistentId("B");

        boolean expectedExceptionThrown = false;

        try {
            startGridWithConsistentId("C");
        }
        catch (IgniteCheckedException e) {
            expectedExceptionThrown = true;

            if (e.getCause() != null && e.getCause().getCause() != null) {
                Throwable rootCause = e.getCause().getCause();

                if (!(rootCause instanceof IgniteSpiException) || !rootCause.getMessage().contains("not compatible"))
                    Assert.fail("Unexpected ignite exception was thrown: " + e);
            }
            else
                throw e;
        }

        assertTrue("Expected exception wasn't thrown.", expectedExceptionThrown);
    }

    /**
     * Verifies scenario when parts of grid were activated independently they are not allowed to join
     * into the same grid again (due to risks of incompatible data modifications).
     */
    public void testIncompatibleBltNodeIsProhibitedToJoinCluster() throws Exception {
        startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C").active(true);

        stopAllGrids(false);

        startGridWithConsistentId("A");
        startGridWithConsistentId("B").active(true);

        stopAllGrids(false);

        startGridWithConsistentId("C").active(true);

        stopAllGrids(false);

        startGridWithConsistentId("A");
        startGridWithConsistentId("B");

        boolean expectedExceptionThrown = false;

        try {
            startGridWithConsistentId("C");
        }
        catch (IgniteCheckedException e) {
            expectedExceptionThrown = true;

            if (e.getCause() != null && e.getCause().getCause() != null) {
                Throwable rootCause = e.getCause().getCause();

                if (!(rootCause instanceof IgniteSpiException) || !rootCause.getMessage().contains("not compatible"))
                    Assert.fail("Unexpected ignite exception was thrown: " + e);
            }
            else
                throw e;
        }

        assertTrue("Expected exception wasn't thrown.", expectedExceptionThrown);
    }

    /**
     * Test verifies that node with out-of-data but still compatible Baseline Topology is allowed to join the cluster.
     */
    public void testNodeWithOldBltIsAllowedToJoinCluster() throws Exception {
        final long expectedHash1 = (long)"A".hashCode() + "B".hashCode() + "C".hashCode();

        BaselineTopologyVerifier verifier1 = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNotNull(blt);

                assertEquals(3, blt.consistentIds().size());

                long activationHash = U.field(blt, "branchingPntHash");

                assertEquals(expectedHash1, activationHash);
            }
        };

        final long expectedHash2 = (long)"A".hashCode() + "B".hashCode();

        BaselineTopologyVerifier verifier2 = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNotNull(blt);

                assertEquals(3, blt.consistentIds().size());

                long activationHash = U.field(blt, "branchingPntHash");

                assertEquals(expectedHash2, activationHash);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("C");

        nodeC.active(true);
        verifyBaselineTopologyOnNodes(verifier1, new Ignite[] {nodeA, nodeB, nodeC});

        stopAllGrids(false);

        nodeA = startGridWithConsistentId("A");
        nodeB = startGridWithConsistentId("B");

        nodeB.active(true);

        verifyBaselineTopologyOnNodes(verifier2, new Ignite[] {nodeA, nodeB});

        stopAllGrids(false);

        nodeA = startGridWithConsistentId("A");
        nodeB = startGridWithConsistentId("B");
        nodeC = startGridWithConsistentId("C");

        verifyBaselineTopologyOnNodes(verifier2, new Ignite[] {nodeA, nodeB, nodeC});
    }

    /**
     * Verifies that when new node outside of baseline topology joins active cluster with BLT already set
     * it receives BLT from the cluster and stores it locally.
     */
    public void testNewNodeJoinsToActiveCluster() throws Exception {
        Ignite nodeA = startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("C");

        nodeC.active(true);

        BaselineTopologyVerifier verifier1 = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNotNull(blt);

                assertEquals(3, blt.consistentIds().size());
            }
        };

        verifyBaselineTopologyOnNodes(verifier1, new Ignite[] {nodeA, nodeB, nodeC});

        Ignite nodeD = startGridWithConsistentId("D");

        verifyBaselineTopologyOnNodes(verifier1, new Ignite[] {nodeD});

        stopAllGrids(false);

        nodeD = startGridWithConsistentId("D");

        assertFalse(nodeD.active());

        verifyBaselineTopologyOnNodes(verifier1, new Ignite[] {nodeD});
    }

    /**
     *
     */
    public void testRemoveNodeFromBaselineTopology() throws Exception {
        final long expectedActivationHash = (long)"A".hashCode() + "C".hashCode();

        BaselineTopologyVerifier verifier = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNotNull(blt);

                assertEquals(2, blt.consistentIds().size());

                long activationHash = U.field(blt, "branchingPntHash");

                assertEquals(expectedActivationHash, activationHash);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("C");

        nodeC.active(true);

        stopGrid("B", false);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        boolean activated = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return grid("A").active();
            }
        }, 10_000);

        assertEquals(true, activated);

        verifyBaselineTopologyOnNodes(verifier, new Ignite[] {nodeA, nodeC});

        stopAllGrids(false);

        nodeA = startGridWithConsistentId("A");
        nodeC = startGridWithConsistentId("C");

        activated = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return grid("A").active();
            }
        }, 10_000);

        assertTrue(activated);

        verifyBaselineTopologyOnNodes(verifier, new Ignite[] {nodeA, nodeC});
    }

    /**
     *
     */
    public void testAddNodeToBaselineTopology() throws Exception {
        final long expectedActivationHash = (long)"A".hashCode() + "B".hashCode() + "C".hashCode() + "D".hashCode();

        BaselineTopologyVerifier verifier = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNotNull(blt);

                assertEquals(4, blt.consistentIds().size());

                long activationHash = U.field(blt, "branchingPntHash");

                assertEquals(expectedActivationHash, activationHash);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("C");

        nodeC.active(true);

        IgniteEx nodeD = (IgniteEx) startGridWithConsistentId("D");

        nodeD.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        verifyBaselineTopologyOnNodes(verifier, new Ignite[]{nodeA, nodeB, nodeC, nodeD});
    }

    /**
     * Verifies that baseline topology is removed successfully through baseline changing API.
     */
    public void testRemoveBaselineTopology() throws Exception {
        BaselineTopologyVerifier verifier = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNull(blt);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");
        Ignite nodeC = startGridWithConsistentId("C");

        nodeA.active(true);

        nodeA.cluster().setBaselineTopology(null);

        verifyBaselineTopologyOnNodes(verifier, new Ignite[] {nodeA, nodeB, nodeC});
    }

    /** */
    private interface BaselineTopologyVerifier {
        /** */
        public void verify(BaselineTopology blt);
    }

    /** */
    private interface BaselineTopologyHistoryVerifier {
        /** */
        public void verify(BaselineTopologyHistory bltHist);
    }

    /** */
    private void verifyBaselineTopologyOnNodes(BaselineTopologyVerifier bltVerifier, Ignite[] igs) {
        for (Ignite ig : igs) {
            BaselineTopology blt = getBaselineTopology(ig);

            bltVerifier.verify(blt);
        }
    }

    /** */
    private void verifyBaselineTopologyHistoryOnNodes(BaselineTopologyHistoryVerifier bltHistVerifier, Ignite[] igs) {
        for (Ignite ig : igs) {
            BaselineTopologyHistory blt = getBaselineTopologyHistory(ig);

            bltHistVerifier.verify(blt);
        }
    }

    /** */
    private Ignite startGridWithConsistentId(String consId) throws Exception {
        this.consId = consId;

        return startGrid(consId);
    }

    /**
     * Verifies that when new node joins already active cluster and new activation request is issued,
     * no changes to BaselineTopology branching history happen.
     */
    public void testActivationHashIsNotUpdatedOnMultipleActivationRequests() throws Exception {
        final long expectedActivationHash = (long)"A".hashCode();

        BaselineTopologyVerifier verifier = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                long activationHash = U.field(blt, "branchingPntHash");

                assertEquals(expectedActivationHash, activationHash);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");

        nodeA.active(true);

        Ignite nodeB = startGridWithConsistentId("B");

        nodeA.active(true);

        verifyBaselineTopologyOnNodes(verifier, new Ignite[] {nodeA, nodeB});
    }

    /**
     * Verifies that grid is autoactivated when full BaselineTopology is preset even on one node
     * and then all other nodes from BaselineTopology are started.
     */
    public void testAutoActivationWithBaselineTopologyPreset() throws Exception {
        Ignite ig = startGridWithConsistentId("A");

        ig.active(true);

        ig.cluster().setBaselineTopology(Arrays.asList(new BaselineNode[] {
            createBaselineNodeWithConsId("A"), createBaselineNodeWithConsId("B"), createBaselineNodeWithConsId("C")}));

        stopAllGrids();

        final Ignite ig1 = startGridWithConsistentId("A");

        startGridWithConsistentId("B");

        startGridWithConsistentId("C");

        boolean activated = GridTestUtils.waitForCondition(
            new GridAbsPredicate() {
               @Override public boolean apply() {
                   return ig1.active();
               }
            },
            10_000
        );

        assertTrue(activated);
    }

    /**
     * Creates BaselineNode with specific attribute indicating that this node is not client.
     */
    private BaselineNode createBaselineNodeWithConsId(String consId) {
        Map<String, Object> attrs = new HashMap<>();

        attrs.put("org.apache.ignite.cache.client", false);

        return new DetachedClusterNode(consId, attrs);
    }

    /** */
    public void testAutoActivationSimple() throws Exception {
        startGrids(3);

        IgniteEx srv = grid(0);

        srv.active(true);

        createAndFillCache(srv);

        // TODO: final implementation should work with cancel == true.
        stopAllGrids();

        //note: no call for activation after grid restart
        startGrids(3);

        final Ignite ig = grid(0);

        boolean clusterActive = GridTestUtils.waitForCondition(
            new GridAbsPredicate() {
                @Override public boolean apply() {
                    return ig.active();
                }
            },
            10_000);

        assertTrue(clusterActive);

        checkDataInCache((IgniteEx) ig);
    }

    /**
     *
     */
    public void testNoAutoActivationOnJoinNewNodeToInactiveCluster() throws Exception {
        startGrids(2);

        IgniteEx srv = grid(0);

        srv.active(true);

        awaitPartitionMapExchange();

        assertTrue(srv.active());

        srv.active(false);

        assertFalse(srv.active());

        startGrid(2);

        Thread.sleep(3_000);

        assertFalse(srv.active());
    }

    /**
     * Verifies that neither BaselineTopology nor BaselineTopologyHistory are changed when cluster is deactivated.
     */
    public void testBaselineTopologyRemainsTheSameOnClusterDeactivation() throws Exception {
        startGrids(2);

        IgniteEx srv = grid(0);

        srv.active(true);

        awaitPartitionMapExchange();

        assertTrue(srv.active());

        srv.active(false);

        BaselineTopology blt = getBaselineTopology(srv);

        BaselineTopologyHistory bltHist = getBaselineTopologyHistory(srv);

        assertEquals(0, blt.id());

        assertEquals(2, blt.consistentIds().size());

        assertEquals(1, blt.branchingHistory().size());

        assertEquals(0, bltHist.history().size());
    }

    /**
     *
     */
    public void testBaselineHistorySyncWithNewNode() throws Exception {
        final long expectedBranchingHash = "A".hashCode() + "B".hashCode() + "C".hashCode();

        BaselineTopologyHistoryVerifier verifier = new BaselineTopologyHistoryVerifier() {
            @Override public void verify(BaselineTopologyHistory bltHist) {
                assertNotNull(bltHist);

                assertEquals(1, bltHist.history().size());

                BaselineTopologyHistoryItem histItem = bltHist.history().get(0);

                assertEquals(1, histItem.branchingHistory().size());

                long actualBranchingHash = histItem.branchingHistory().get(0);

                assertEquals(expectedBranchingHash, actualBranchingHash);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C");

        nodeA.active(true);

        stopGrid("C", false);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        startGridWithConsistentId("D");

        stopAllGrids(false);

        Ignite nodeD = startGridWithConsistentId("D");

        verifyBaselineTopologyHistoryOnNodes(verifier, new Ignite[] {nodeD});
    }

    /**
     *
     */
    public void testBaselineHistorySyncWithOldNodeWithCompatibleHistory() throws Exception {
        final long expectedBranchingHash0 = "A".hashCode() + "B".hashCode() + "C".hashCode();

        final long expectedBranchingHash1 = "A".hashCode() + "B".hashCode();

        BaselineTopologyHistoryVerifier verifier = new BaselineTopologyHistoryVerifier() {
            @Override public void verify(BaselineTopologyHistory bltHist) {
                assertNotNull(bltHist);

                assertEquals(2, bltHist.history().size());

                BaselineTopologyHistoryItem histItem = bltHist.history().get(0);

                assertEquals(1, histItem.branchingHistory().size());

                long actualBranchingHash0 = histItem.branchingHistory().get(0);

                assertEquals(expectedBranchingHash0, actualBranchingHash0);

                histItem = bltHist.history().get(1);

                assertEquals(1, histItem.branchingHistory().size());

                long actualBranchingHash1 = histItem.branchingHistory().get(0);

                assertEquals(expectedBranchingHash1, actualBranchingHash1);
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C");

        nodeA.active(true);

        stopGrid("C", false);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        stopGrid("B", false);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        startGridWithConsistentId("B");

        stopAllGrids(false);

        startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");

        verifyBaselineTopologyHistoryOnNodes(verifier, new Ignite[] {nodeB});
    }

    /**
     * @throws Exception if failed.
     */
    public void testBaselineNotDeletedOnDeactivation() throws Exception {
        Ignite nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C");

        nodeA.active(true);

        assertNotNull(nodeA.cluster().currentBaselineTopology());

        nodeA.active(false);

        stopAllGrids();

        nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C");

        final Ignite ig = nodeA;

        boolean clusterActive = GridTestUtils.waitForCondition(
            new GridAbsPredicate() {
                @Override public boolean apply() {
                    return ig.active();
                }
            },
            10_000);

        assertNotNull(nodeA.cluster().currentBaselineTopology());

        assertTrue(clusterActive);
    }

    /**
     *
     */
    public void testNodeWithBltIsProhibitedToJoinNewCluster() throws Exception {
        BaselineTopologyVerifier nullVerifier = new BaselineTopologyVerifier() {
            @Override public void verify(BaselineTopology blt) {
                assertNull(blt);
            }
        };

        Ignite nodeC = startGridWithConsistentId("C");

        nodeC.active(true);

        stopGrid("C", false);

        Ignite nodeA = startGridWithConsistentId("A");
        Ignite nodeB = startGridWithConsistentId("B");

        verifyBaselineTopologyOnNodes(nullVerifier, new Ignite[] {nodeA, nodeB});

        boolean expectedExceptionThrown = false;

        try {
            startGridWithConsistentId("C");
        }
        catch (IgniteCheckedException e) {
            expectedExceptionThrown = true;

            if (e.getCause() != null && e.getCause().getCause() != null) {
                Throwable rootCause = e.getCause().getCause();

                if (!(rootCause instanceof IgniteSpiException) || !rootCause.getMessage().contains("Node with set up BaselineTopology"))
                    Assert.fail("Unexpected ignite exception was thrown: " + e);
            }
            else
                throw e;
        }

        assertTrue("Expected exception wasn't thrown.", expectedExceptionThrown);
    }

    /**
     * Restore this test when requirements for BaselineTopology deletion are clarified and this feature
     * is covered with more tests.
     */
    public void _testBaselineTopologyHistoryIsDeletedOnBaselineDelete() throws Exception {
        BaselineTopologyHistoryVerifier verifier = new BaselineTopologyHistoryVerifier() {
            @Override public void verify(BaselineTopologyHistory bltHist) {
                assertNotNull(bltHist);

                assertEquals(0, bltHist.history().size());
            }
        };

        Ignite nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");
        startGridWithConsistentId("C");

        nodeA.active(true);

        stopAllGrids(false);

        nodeA = startGridWithConsistentId("A");
        startGridWithConsistentId("B");

        nodeA.active(true);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        stopAllGrids(false);

        nodeA = startGridWithConsistentId("A");

        nodeA.active(true);

        nodeA.cluster().setBaselineTopology(baselineNodes(nodeA.cluster().forServers().nodes()));

        stopAllGrids(false);

        final Ignite node = startGridWithConsistentId("A");

        boolean activated = GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return node.active();
            }
        }, 10_000);

        assertTrue(activated);

        node.cluster().setBaselineTopology(null);

        verifyBaselineTopologyHistoryOnNodes(verifier, new Ignite[] {node});

        stopAllGrids(false);

        nodeA = startGridWithConsistentId("A");

        verifyBaselineTopologyHistoryOnNodes(verifier, new Ignite[] {nodeA});
    }

    /**
     * Retrieves baseline topology from ignite node instance.
     *
     * @param ig Ig.
     */
    private BaselineTopology getBaselineTopology(Ignite ig) {
        return ((DiscoveryDataClusterState) U.field(
            (Object) U.field(
                (Object) U.field(ig, "ctx"),
                "stateProc"),
            "globalState"))
            .baselineTopology();
    }

    /** */
    private BaselineTopologyHistory getBaselineTopologyHistory(Ignite ig) {
        return U.field(
            (Object) U.field(
                (Object) U.field(ig, "ctx"),
                "stateProc"),
            "bltHist");
    }

    /** */
    private void checkDataInCache(IgniteEx srv) {
        IgniteCache<Object, Object> cache = srv.cache(DEFAULT_CACHE_NAME);

        for (int i = 0; i < ENTRIES_COUNT; i++) {
            TestValue testVal = (TestValue) cache.get(i);

            assertNotNull(testVal);

            assertEquals(i, testVal.id);
        }
    }

    /** */
    private void createAndFillCache(Ignite srv) {
        IgniteCache cache = srv.getOrCreateCache(cacheConfiguration());

        for (int i = 0; i < ENTRIES_COUNT; i++)
            cache.put(i, new TestValue(i, "str" + i));
    }

    /** */
    private CacheConfiguration cacheConfiguration() {
        return new CacheConfiguration()
            .setName(DEFAULT_CACHE_NAME)
            .setCacheMode(CacheMode.PARTITIONED)
            .setAtomicityMode(CacheAtomicityMode.ATOMIC)
            .setBackups(2)
            .setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
    }

    /** */
    private static final class TestValue {
        /** */
        private final int id;

        /** */
        private final String strId;

        /** */
        private TestValue(int id, String strId) {
            this.id = id;
            this.strId = strId;
        }
    }

    /** */
    private Collection<BaselineNode> baselineNodes(Collection<ClusterNode> clNodes) {
        Collection<BaselineNode> res = new ArrayList<>(clNodes.size());

        for (ClusterNode clN : clNodes)
            res.add(clN);

        return res;
    }

}
