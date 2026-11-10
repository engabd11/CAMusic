package com.engabd.sendpin.ma

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A named grouping of Music Assistant speakers, each at its own volume, recalled in one
 * tap from the Speakers screen.
 *
 * Kept on this phone (see [com.engabd.sendpin.data.AppSettings.speakerScenes]), not on
 * the server: Music Assistant has no such thing, and a scene is only the grouping and
 * volume commands the screen already sends, replayed. It never says what to play, so
 * recalling one cannot start, stop or restart the music.
 */
@Serializable
data class SpeakerScene(
    /** Stable across renames. */
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** The player the group forms around: the Speakers screen's play-to target. */
    val leader: String = "",
    /** Every speaker in the scene, the leader included. */
    val members: List<Member> = emptyList(),
    /**
     * The Music Assistant server it was saved on (its `ServerConfig.id`), so another
     * server does not list it with every speaker skipped. Blank on scenes saved before
     * this was kept: those show on every server rather than vanishing.
     */
    val server: String = "",
) {
    /** One speaker and its volume. The name is only for saying which one is missing. */
    @Serializable
    data class Member(val id: String = "", val name: String = "", val volume: Int = 0)

    /** What recalling a scene has to send, worked out against the players as they are now. */
    data class Plan(
        /** The play-to target after the recall. */
        val leader: String,
        /** Players to join to [leader]. */
        val add: List<String>,
        /** Players to take out of their group first. */
        val remove: List<String>,
        /** Volumes that differ from the scene, by player id, 0..100. */
        val volumes: Map<String, Int>,
        /** Names of the scene's speakers that are not available right now. */
        val skipped: List<String>,
        /** Whether [leader] is a different player from the one in use. */
        val leaderChanges: Boolean,
    ) {
        val isNoop: Boolean get() = !leaderChanges && add.isEmpty() && remove.isEmpty() && volumes.isEmpty()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(list: List<SpeakerScene>): String = json.encodeToString(ListSerializer(serializer()), list)

        fun decode(raw: String): List<SpeakerScene>? = runCatching {
            json.decodeFromString(ListSerializer(serializer()), raw)
        }.getOrNull()

        /**
         * The group around [leader] as [players] show it, at the volumes they show.
         *
         * [listenerId] is the phone sitting silently in the group for Light Sync: it is
         * not a speaker anyone chose, and recalling it would put the phone into a group
         * Light Sync is not running for. [inGroup] is who the screen shows as grouped,
         * which can be ahead of MA's answer by a join it has not confirmed yet.
         */
        fun capture(
            name: String,
            leader: String,
            players: List<MaPlayer>,
            listenerId: String?,
            server: String = "",
            inGroup: Set<String> = groupOf(leader, players),
        ): SpeakerScene {
            return SpeakerScene(
                name = name,
                leader = leader,
                server = server,
                members = players
                    .filter { it.playerId in inGroup && it.playerId != listenerId }
                    .sortedByDescending { it.playerId == leader }
                    .map { Member(it.playerId, it.name, it.volumeLevel) },
            )
        }

        /**
         * What it takes to bring [players] to [scene], or a no-op [Plan] when they are there.
         *
         * A speaker that is unavailable is skipped and named rather than failing the whole
         * recall: the rest of the room is still worth setting. If the scene's leader is the
         * one missing, its first available member leads instead. The Light listener
         * ([listenerId]) is neither added nor taken out, so Light Sync keeps listening.
         */
        fun plan(scene: SpeakerScene, players: List<MaPlayer>, currentLeader: String, listenerId: String?): Plan {
            val byId = players.associateBy { it.playerId }
            val wanted = scene.members.filter { it.id != listenerId }
            val usable = wanted.filter { byId[it.id]?.available == true }
            val skipped = wanted.filter { byId[it.id]?.available != true }.map { it.name.ifBlank { it.id } }
            val leader = scene.leader.takeIf { l -> usable.any { it.id == l } } ?: usable.firstOrNull()?.id
                ?: return Plan(currentLeader, emptyList(), emptyList(), emptyMap(), skipped, leaderChanges = false)

            val usableIds = usable.map { it.id }.toSet()
            val groupNow = groupOf(leader, players)
            val add = usable.map { it.id }.filter { it != leader && it !in groupNow }
            val leaving = groupNow.filter { it != leader && it != listenerId && it !in usableIds }
            // A leader that is itself following another group has to leave it first.
            val detach = listOfNotNull(leader.takeIf { byId[it]?.syncedTo != null })
            val volumes = usable.filter { byId[it.id]?.volumeLevel != it.volume }.associate { it.id to it.volume }
            return Plan(leader, add, detach + leaving, volumes, skipped, leaderChanges = leader != currentLeader)
        }

        /** The scenes that belong on [server]: its own and any saved before scenes were tagged. */
        fun forServer(list: List<SpeakerScene>, server: String): List<SpeakerScene> =
            list.filter { it.server.isBlank() || it.server == server }

        /** [leader] and every player synced to it. */
        private fun groupOf(leader: String, players: List<MaPlayer>): Set<String> {
            val childs = players.firstOrNull { it.playerId == leader }?.groupChilds.orEmpty()
            return players
                .filter { it.playerId == leader || it.syncedTo == leader || it.playerId in childs }
                .mapTo(mutableSetOf()) { it.playerId }
        }
    }
}
