package net.corda.node.services

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.whenever
import net.corda.core.contracts.AlwaysAcceptAttachmentConstraint
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.CompositeKey
import net.corda.core.flows.NotaryError
import net.corda.core.flows.NotaryException
import net.corda.core.flows.NotaryFlow
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.deleteIfExists
import net.corda.core.internal.div
import net.corda.core.node.NotaryInfo
import net.corda.core.node.services.NotaryService
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.Try
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.StartedNode
import net.corda.node.services.config.BFTSMaRtConfiguration
import net.corda.node.services.config.NotaryConfig
import net.corda.node.services.transactions.BFTNonValidatingNotaryService
import net.corda.node.services.transactions.minClusterSize
import net.corda.node.services.transactions.minCorrectReplicas
import net.corda.node.utilities.ServiceIdentityGenerator
import net.corda.testing.chooseIdentity
import net.corda.testing.common.internal.NetworkParametersCopier
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.contracts.DummyContract
import net.corda.testing.dummyCommand
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNodeArgs
import net.corda.testing.node.MockNodeParameters
import org.junit.After
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BFTNotaryServiceTests {
    companion object {
        private val clusterName = CordaX500Name(BFTNonValidatingNotaryService.id, "BFT", "Zurich", "CH")
    }

    // MockNetwork doesn't support BFT notaries (which makes sense since MockNetwork uses in-memory messaging for the node
    // while the BFT-SMaRt library is using sockets) So instead we create the network without any notaries, but then once
    // the BFT cluster identity is created we create our own network parameters which contains this notary and use nodeFactory
    // to write this to each node.
    private val mockNet = MockNetwork(notarySpecs = emptyList())
    private lateinit var notary: Party
    private lateinit var bftNetworkParameters: NetworkParametersCopier
    private lateinit var node: StartedNode<MockNetwork.MockNode>

    @After
    fun stopNodes() {
        mockNet.stopNodes()
    }

    private fun bftNotaryCluster(clusterSize: Int, exposeRaces: Boolean = false) {
        (Paths.get("config") / "currentView").deleteIfExists() // XXX: Make config object warn if this exists?
        val replicaIds = (0 until clusterSize)
        notary = ServiceIdentityGenerator.generateToDisk(
                replicaIds.map { mockNet.baseDirectory(mockNet.nextNodeId + it) },
                clusterName,
                NotaryService.constructId(validating = false, bft = true))

        bftNetworkParameters = NetworkParametersCopier(testNetworkParameters(listOf(NotaryInfo(notary, false))))

        val clusterAddresses = replicaIds.map { NetworkHostAndPort("localhost", 11000 + it * 10) }
        for (replicaId in replicaIds) {
            mockNet.createNode(parameters = MockNodeParameters(configOverrides = {
                val notary = NotaryConfig(validating = false, bftSMaRt = BFTSMaRtConfiguration(replicaId, clusterAddresses, exposeRaces = exposeRaces))
                doReturn(notary).whenever(it).notary
            }), nodeFactory = this::nodeWithBftNetworkParameters)
        }
    }

    /** Failure mode is the redundant replica gets stuck in startup, so we can't dispose it cleanly at the end. */
    @Test
    fun `all replicas start even if there is a new consensus during startup`() {
        bftNotaryCluster(minClusterSize(1), true) // This true adds a sleep to expose the race.
        node = mockNet.createNode(nodeFactory = this::nodeWithBftNetworkParameters)
        val f = node.run {
            val trivialTx = signInitialTransaction(notary) {
                addOutputState(DummyContract.SingleOwnerState(owner = info.chooseIdentity()), DummyContract.PROGRAM_ID, AlwaysAcceptAttachmentConstraint)
            }
            // Create a new consensus while the redundant replica is sleeping:
            services.startFlow(NotaryFlow.Client(trivialTx)).resultFuture
        }
        mockNet.runNetwork()
        f.getOrThrow()
    }

    @Test
    fun `detect double spend 1 faulty`() {
        detectDoubleSpend(1)
    }

    @Test
    fun `detect double spend 2 faulty`() {
        detectDoubleSpend(2)
    }

    private fun nodeWithBftNetworkParameters(args: MockNodeArgs): MockNetwork.MockNode {
        bftNetworkParameters.install(args.config.baseDirectory)
        return MockNetwork.MockNode(args)
    }

    private fun detectDoubleSpend(faultyReplicas: Int) {
        val clusterSize = minClusterSize(faultyReplicas)
        bftNotaryCluster(clusterSize)
        node = mockNet.createNode(nodeFactory = this::nodeWithBftNetworkParameters)
        node.run {
            val issueTx = signInitialTransaction(notary) {
                addOutputState(DummyContract.SingleOwnerState(owner = info.chooseIdentity()), DummyContract.PROGRAM_ID, AlwaysAcceptAttachmentConstraint)
            }
            database.transaction {
                services.recordTransactions(issueTx)
            }
            val spendTxs = (1..10).map {
                signInitialTransaction(notary) {
                    addInputState(issueTx.tx.outRef<ContractState>(0))
                }
            }
            assertEquals(spendTxs.size, spendTxs.map { it.id }.distinct().size)
            val flows = spendTxs.map { NotaryFlow.Client(it) }
            val stateMachines = flows.map { services.startFlow(it) }
            mockNet.runNetwork()
            val results = stateMachines.map { Try.on { it.resultFuture.getOrThrow() } }
            val successfulIndex = results.mapIndexedNotNull { index, result ->
                if (result is Try.Success) {
                    val signers = result.value.map { it.by }
                    assertEquals(minCorrectReplicas(clusterSize), signers.size)
                    signers.forEach {
                        assertTrue(it in (notary.owningKey as CompositeKey).leafKeys)
                    }
                    index
                } else {
                    null
                }
            }.single()
            spendTxs.zip(results).forEach { (tx, result) ->
                if (result is Try.Failure) {
                    val error = (result.exception as NotaryException).error as NotaryError.Conflict
                    assertEquals(tx.id, error.txId)
                    val (stateRef, consumingTx) = error.conflict.verified().stateHistory.entries.single()
                    assertEquals(StateRef(issueTx.id, 0), stateRef)
                    assertEquals(spendTxs[successfulIndex].id, consumingTx.id)
                    assertEquals(0, consumingTx.inputIndex)
                    assertEquals(info.chooseIdentity(), consumingTx.requestingParty)
                }
            }
        }
    }
}

private fun StartedNode<*>.signInitialTransaction(
        notary: Party,
        block: TransactionBuilder.() -> Any?
): SignedTransaction {
    return services.signInitialTransaction(
            TransactionBuilder(notary).apply {
                addCommand(dummyCommand(services.myInfo.chooseIdentity().owningKey))
                block()
            })
}
