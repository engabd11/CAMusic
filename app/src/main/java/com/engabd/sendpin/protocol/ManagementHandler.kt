package com.engabd.sendpin.protocol

import com.engabd.sendpin.protocol.noise.B64Url
import com.engabd.sendpin.protocol.noise.PairingStore
import com.engabd.sendpin.protocol.noise.SendspinPsk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The management requests (`management/list-records` and friends) a paired server may issue (spec §Management), answered
 * with one `management/result` each.
 *
 * Pure over the [PairingStore] so it can be tested without a socket. The session decides
 * whether management is active — `'management'` in the current activities, which the
 * activation rules only admit on a long-term-paired connection — and passes that in;
 * everything else is here. Storage is unbounded on a phone, so the optional `storage`
 * accounting is omitted, as the spec allows.
 *
 * Only the Pairing PSK method is implemented, so the PIN-method objects are absent from
 * the config, a request touching them is `invalid`, and there is no pairing window to
 * open.
 */
class ManagementHandler(private val store: PairingStore) {

    class Outcome(val result: SendspinManagementResult, val closeUnauthorizedAfter: Boolean = false)

    fun handle(request: String, payload: JsonObject?, managementActive: Boolean, sessionPskId: String): Outcome {
        if (!managementActive) return Outcome(result("permission_denied"))
        return when (request) {
            "list-records" -> Outcome(
                result(
                    "ok",
                    buildJsonObject {
                        put(
                            "records",
                            buildJsonArray {
                                for (r in store.records()) {
                                    add(
                                        buildJsonObject {
                                            put("psk_id", r.pskId)
                                            r.serverId?.let { put("server_id", it) }
                                            put("used", r.used)
                                        },
                                    )
                                }
                            },
                        )
                    },
                ),
            )
            "add-record" -> {
                val psk = payload?.string("psk")?.let { decodePsk(it) }
                val serverId = payload?.string("server_id")
                if (psk == null) return Outcome(result("invalid"))
                Outcome(
                    result(
                        when (store.addRecord(psk, serverId)) {
                            PairingStore.AddResult.OK -> "ok"
                            PairingStore.AddResult.ALREADY_EXISTS -> "already_exists"
                            PairingStore.AddResult.INVALID -> "invalid"
                        },
                    ),
                )
            }
            "remove-record" -> {
                val pskId = payload?.string("psk_id") ?: return Outcome(result("invalid"))
                val outcome = when (store.removeRecord(pskId)) {
                    PairingStore.RemoveResult.OK -> "ok"
                    PairingStore.RemoveResult.NOT_FOUND -> "not_found"
                    PairingStore.RemoveResult.INVALID -> "invalid"
                }
                // Removing the requester's own record ends its authority: reply, then close.
                Outcome(result(outcome), closeUnauthorizedAfter = outcome == "ok" && pskId == sessionPskId)
            }
            "get-pairing-config" -> Outcome(
                result(
                    "ok",
                    buildJsonObject {
                        put("pairing_psk", buildJsonObject { put("enabled", store.pairingPskEnabled) })
                        put("record_mode", buildJsonObject { put("psk_id", store.recordModePskId) })
                        put("unpaired_access", buildJsonObject { put("enabled", store.unpairedAccessEnabled) })
                    },
                ),
            )
            "set-pairing-config" -> Outcome(result(setPairingConfig(payload)))
            "open-pairing-window" -> Outcome(result("invalid"))
            else -> Outcome(result("invalid"))
        }
    }

    /**
     * A patch: only present fields are written. Validated in full before anything is
     * committed, so a request that is rejected leaves the config untouched.
     */
    private fun setPairingConfig(payload: JsonObject?): String {
        payload ?: return "invalid"
        if (payload.containsKey("static_pin") || payload.containsKey("dynamic_pin")) return "invalid"
        val pairingPsk = payload["pairing_psk"] as? JsonObject
        val recordMode = payload["record_mode"] as? JsonObject
        val unpaired = payload["unpaired_access"] as? JsonObject
        if (pairingPsk == null && payload.containsKey("pairing_psk")) return "invalid"
        if (recordMode == null && payload.containsKey("record_mode")) return "invalid"
        if (unpaired == null && payload.containsKey("unpaired_access")) return "invalid"

        val newPsk = pairingPsk?.string("psk")?.let { decodePsk(it) ?: return "invalid" }
        val pskEnabled = pairingPsk?.boolean("enabled")
        val recordModeId = recordMode?.string("psk_id")
        if (recordMode != null && recordModeId == null) return "invalid"
        val unpairedEnabled = unpaired?.boolean("enabled")
        if (unpaired != null && unpairedEnabled == null) return "invalid"
        if (recordModeId != null) {
            val target = store.record(recordModeId) ?: return "invalid"
            if (target.serverId != null) return "invalid"
        }

        if (newPsk != null) {
            when (store.rotatePairingPsk(newPsk)) {
                PairingStore.AddResult.OK -> {}
                PairingStore.AddResult.ALREADY_EXISTS -> return "already_exists"
                PairingStore.AddResult.INVALID -> return "invalid"
            }
        }
        pskEnabled?.let { store.pairingPskEnabled = it }
        recordModeId?.let { store.setRecordModePskId(it) }
        unpairedEnabled?.let { store.unpairedAccessEnabled = it }
        return "ok"
    }

    private fun decodePsk(text: String): ByteArray? {
        if (text.length != 43) return null
        val bytes = try { B64Url.decode(text) } catch (_: IllegalArgumentException) { return null }
        return bytes.takeIf { it.size == SendspinPsk.PSK_SIZE }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun result(code: String, data: JsonElement? = null) =
        SendspinManagementResult(payload = ManagementResultPayload(result = code, data = data))
}
