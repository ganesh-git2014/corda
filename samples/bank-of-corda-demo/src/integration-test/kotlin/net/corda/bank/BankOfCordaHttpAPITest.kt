package net.corda.bank

import net.corda.bank.api.BankOfCordaClientApi
import net.corda.bank.api.BankOfCordaWebApi.IssueRequestParams
import net.corda.core.utilities.getOrThrow
import net.corda.testing.BOC
import net.corda.testing.driver.driver
import org.junit.Test
import kotlin.test.assertTrue

class BankOfCordaHttpAPITest {
    @Test
    fun `issuer flow via Http`() {
        driver(extraCordappPackagesToScan = listOf("net.corda.finance"), isDebug = true) {
            val (nodeBankOfCorda) = listOf(
                    startNode(providedName = BOC.name),
                    startNode(providedName = BIGCORP_LEGAL_NAME)
            ).map { it.getOrThrow() }
            val nodeBankOfCordaApiAddr = startWebserver(nodeBankOfCorda).getOrThrow().listenAddress
            val requestParams = IssueRequestParams(1000, "USD", BIGCORP_LEGAL_NAME, "1", BOC.name, defaultNotaryHandle.identity.name)
            assertTrue(BankOfCordaClientApi(nodeBankOfCordaApiAddr).requestWebIssue(requestParams))
        }
    }
}
