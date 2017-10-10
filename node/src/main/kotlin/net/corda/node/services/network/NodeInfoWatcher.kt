package net.corda.node.services.network

import net.corda.cordform.CordformNode
import net.corda.core.crypto.SignedData
import net.corda.core.internal.*
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.KeyManagementService
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.seconds
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.streams.toList

/**
 * Class containing the logic to
 * - Serialize and de-serialize a [NodeInfo] to disk and reading it back.
 * - Poll a directory for new serialized [NodeInfo]
 *
 * @param path the base path of a node.
 * @param pollFrequencyMsec how often to poll the filesystem in milliseconds. Any value smaller than 5 seconds will
 *        be treated as 5 seconds.
 * @param scheduler a [Scheduler] for the rx [Observable] returned by [nodeInfoUpdates], this is mainly useful for
 *        testing. It defaults to the io scheduler which is the appropriate value for production uses.
 */
class NodeInfoWatcher(private val nodePath: Path,
                      pollFrequencyMsec: Long = 5.seconds.toMillis(),
                      private val scheduler: Scheduler = Schedulers.io()) {

    private val nodeInfoDirectory = nodePath / CordformNode.NODE_INFO_DIRECTORY
    private val pollFrequencyMsec: Long

    companion object {
        private val logger = loggerFor<NodeInfoWatcher>()

        /**
         * Saves the given [NodeInfo] to a path.
         * The node is 'encoded' as a SignedData<NodeInfo>, signed with the owning key of its first identity.
         * The name of the written file will be "nodeInfo-" followed by the hash of the content. The hash in the filename
         * is used so that one can freely copy these files without fearing to overwrite another one.
         *
         * @param path the path where to write the file, if non-existent it will be created.
         * @param nodeInfo the NodeInfo to serialize.
         * @param keyManager a KeyManagementService used to sign the NodeInfo data.
         */
        fun saveToFile(path: Path, nodeInfo: NodeInfo, keyManager: KeyManagementService) {
            try {
                path.createDirectories()
                val serializedBytes = nodeInfo.serialize()
                val regSig = keyManager.sign(serializedBytes.bytes, nodeInfo.legalIdentities.first().owningKey)
                val signedData = SignedData(serializedBytes, regSig)
                signedData.serialize().open().copyTo(path / "nodeInfo-${serializedBytes.hash}")
            } catch (e: Exception) {
                logger.warn("Couldn't write node info to file", e)
            }
        }
    }

    init {
        this.pollFrequencyMsec = maxOf(pollFrequencyMsec, 5.seconds.toMillis())
    }

    /**
     * Read all the files contained in [nodePath] / [CordformNode.NODE_INFO_DIRECTORY] and keep watching
     * the folder for further updates.
     *
     * We simply list the directory content every 5 seconds, the Java implementation of WatchService has been proven to
     * be unreliable on MacOs and given the fairly simple use case we have, this simple implementation should do.
     *
     * @return an [Observable] returning [NodeInfo]s, there is no guarantee that the same value isn't returned more
     *      than once.
     */
    fun nodeInfoUpdates(): Observable<NodeInfo> {
        return Observable.interval(pollFrequencyMsec, TimeUnit.MILLISECONDS, scheduler)
                .flatMapIterable { loadFromDirectory() }
    }

    /**
     * Loads all the files contained in a given path and returns the deserialized [NodeInfo]s.
     * Signatures are checked before returning a value.
     *
     * @return a list of [NodeInfo]s
     */
    private fun loadFromDirectory(): List<NodeInfo> {
        if (!nodeInfoDirectory.isDirectory()) {
            logger.info("$nodeInfoDirectory isn't a Directory, not loading NodeInfo from files")
            return emptyList()
        }
        val result = nodeInfoDirectory.list { paths ->
            paths.filter { it.isRegularFile() }
                    .map { processFile(it) }
                    .toList()
                    .filterNotNull()
        }
        logger.info("Successfully read ${result.size} NodeInfo files.")
        return result
    }

    private fun processFile(file: Path): NodeInfo? {
        try {
            logger.info("Reading NodeInfo from file: $file")
            val signedData = file.readAll().deserialize<SignedData<NodeInfo>>()
            return signedData.verified()
        } catch (e: Exception) {
            logger.warn("Exception parsing NodeInfo from file. $file", e)
            return null
        }
    }
}
