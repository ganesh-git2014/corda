package net.corda.node.services.network

import net.corda.core.identity.PartyAndCertificate
import net.corda.core.internal.ThreadBox
import net.corda.core.messaging.SingleMessageRecipient
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.NetworkMapCache
import net.corda.core.serialization.CordaSerializable
import net.corda.node.utilities.AddOrRemove
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe

/**
 * A network map contains lists of nodes on the network along with information about their identity keys, services
 * they provide and host names or IP addresses where they can be connected to. This information is cached locally within
 * nodes, by the [NetworkMapCache]. Currently very basic consensus controls are applied, using signed changes which
 * replace each other based on a serial number present in the change.
 */
// TODO: A better architecture for the network map service might be one like the Tor directory authorities, where
// several nodes linked by RAFT or Paxos elect a leader and that leader distributes signed documents describing the
// network layout. Those documents can then be cached by every node and thus a network map can/ be retrieved given only
// a single successful peer connection.
//
// It may also be that this is replaced or merged with the identity management service; for example if the network has
// a concept of identity changes over time, should that include the node for an identity? If so, that is likely to
// replace this service.
@ThreadSafe
interface NetworkMapService {

    val nodeRegistrations: Map<PartyAndCertificate, NodeRegistrationInfo>

    // Map from subscriber address, to most recently acknowledged update map version.
    val subscribers: ThreadBox<MutableMap<SingleMessageRecipient, LastAcknowledgeInfo>>
}


@ThreadSafe
class InMemoryNetworkMapService: NetworkMapService {

    override val nodeRegistrations: MutableMap<PartyAndCertificate, NodeRegistrationInfo> = ConcurrentHashMap()
    override val subscribers = ThreadBox(mutableMapOf<SingleMessageRecipient, LastAcknowledgeInfo>())
}


/**
 * A node registration state in the network map.
 *
 * @param node the node being added/removed.
 * @param serial an increasing value which represents the version of this registration. Not expected to be sequential,
 * but later versions of the registration must have higher values (or they will be ignored by the map service).
 * Similar to the serial number on DNS records.
 * @param type add if the node is being added to the map, or remove if a previous node is being removed (indicated as
 * going offline).
 * @param expires when the registration expires. Only used when adding a node to a map.
 */
// TODO: This might alternatively want to have a node and party, with the node being optional, so registering a node
// involves providing both node and paerty, and deregistering a node involves a request with party but no node.
@CordaSerializable
data class NodeRegistration(val node: NodeInfo, val serial: Long, val type: AddOrRemove, var expires: Instant) {
    override fun toString(): String = "$node #$serial ($type)"
}

@CordaSerializable
data class LastAcknowledgeInfo(val mapVersion: Int)

@CordaSerializable
data class NodeRegistrationInfo(val reg: NodeRegistration, val mapVersion: Int)
