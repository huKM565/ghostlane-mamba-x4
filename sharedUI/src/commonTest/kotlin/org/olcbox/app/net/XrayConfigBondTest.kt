package org.olcbox.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class XrayConfigBondTest {
    private val spec = assertIs<OutboundSpec.Vless>(
        LinkParser.parse(
            "vless://11111111-1111-1111-1111-111111111111@127.0.0.1:10000" +
                "?security=none&encryption=none#Mamba-x4"
        )
    )

    @Test fun vlessBondBalancesAcrossFourIndependentRooms() {
        val root = Json.parseToJsonElement(
            XrayConfig.buildVlessBond(
                spec,
                roomPorts = listOf(21081, 21082, 21083, 21084),
                roomUsername = "room-user",
                roomPassword = "room-pass",
                socksPort = 22080,
                login = SocksLogin("in-user", "in-pass"),
            )
        ).jsonObject

        val routing = root["routing"]!!.jsonObject
        val balancer = routing["balancers"]!!.jsonArray.single().jsonObject
        assertEquals("bond", balancer["tag"]!!.jsonPrimitive.content)
        assertEquals("roundRobin", balancer["strategy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("vless-room-1", "vless-room-2", "vless-room-3", "vless-room-4"),
            balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val rule = routing["rules"]!!.jsonArray.single().jsonObject
        assertEquals("bond", rule["balancerTag"]!!.jsonPrimitive.content)
        assertEquals(listOf("in"), rule["inboundTag"]!!.jsonArray.map { it.jsonPrimitive.content })

        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val vlessOutbounds = outbounds.filter { it["protocol"]?.jsonPrimitive?.content == "vless" }
        assertEquals(4, vlessOutbounds.size)
        vlessOutbounds.forEachIndexed { index, outbound ->
            val number = index + 1
            assertEquals("vless-room-$number", outbound["tag"]!!.jsonPrimitive.content)
            val server = outbound["settings"]!!.jsonObject["vnext"]!!.jsonArray.single().jsonObject
            assertEquals("127.0.0.1", server["address"]!!.jsonPrimitive.content)
            assertEquals(10000, server["port"]!!.jsonPrimitive.content.toInt())
            val user = server["users"]!!.jsonArray.single().jsonObject
            assertEquals("11111111-1111-1111-1111-111111111111", user["id"]!!.jsonPrimitive.content)
            assertEquals("none", user["encryption"]!!.jsonPrimitive.content)
            val stream = outbound["streamSettings"]!!.jsonObject
            assertEquals("none", stream["security"]!!.jsonPrimitive.content)
            assertEquals("socks-room-$number", stream["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content)
            val mux = outbound["mux"]!!.jsonObject
            assertEquals(true, mux["enabled"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(-1, mux["concurrency"]!!.jsonPrimitive.content.toInt())
            assertEquals(16, mux["xudpConcurrency"]!!.jsonPrimitive.content.toInt())
            assertEquals("allow", mux["xudpProxyUDP443"]!!.jsonPrimitive.content)
        }

        val socksOutbounds = outbounds.filter { it["protocol"]?.jsonPrimitive?.content == "socks" }
        assertEquals(4, socksOutbounds.size)
        socksOutbounds.forEachIndexed { index, outbound ->
            assertEquals("socks-room-${index + 1}", outbound["tag"]!!.jsonPrimitive.content)
            val server = outbound["settings"]!!.jsonObject["servers"]!!.jsonArray.single().jsonObject
            assertEquals("127.0.0.1", server["address"]!!.jsonPrimitive.content)
            assertEquals(21081 + index, server["port"]!!.jsonPrimitive.content.toInt())
            val user = server["users"]!!.jsonArray.single().jsonObject
            assertEquals("room-user", user["user"]!!.jsonPrimitive.content)
            assertEquals("room-pass", user["pass"]!!.jsonPrimitive.content)
        }

        val inbound = root["inbounds"]!!.jsonArray.single().jsonObject
        assertEquals(22080, inbound["port"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, inbound["settings"]!!.jsonObject["udp"]!!.jsonPrimitive.content.toBoolean())
        val user = inbound["settings"]!!.jsonObject["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("in-user", user["user"]!!.jsonPrimitive.content)
        assertEquals("in-pass", user["pass"]!!.jsonPrimitive.content)
    }

    @Test fun vlessBondBalancesAcrossThreeRemainingRooms() {
        val root = Json.parseToJsonElement(
            XrayConfig.buildVlessBond(spec, listOf(21081, 21082, 21084), "u", "p")
        ).jsonObject

        val balancer = root["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        assertEquals(
            listOf("vless-room-1", "vless-room-2", "vless-room-3"),
            balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val ports = root["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["protocol"]?.jsonPrimitive?.content == "socks" }
            .map {
                it["settings"]!!.jsonObject["servers"]!!.jsonArray.single().jsonObject["port"]!!
                    .jsonPrimitive.content.toInt()
            }
        assertEquals(listOf(21081, 21082, 21084), ports)
    }

    @Test fun vlessBondStartsWithOneReadyRoom() {
        val root = Json.parseToJsonElement(
            XrayConfig.buildVlessBond(spec, listOf(21081), "u", "p")
        ).jsonObject

        val balancer = root["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("vless-room-1"), balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun vlessBondBalancesAcrossTwelveRooms() {
        val ports = (21081..21092).toList()
        val root = Json.parseToJsonElement(
            XrayConfig.buildVlessBond(spec, ports, "u", "p")
        ).jsonObject

        val balancer = root["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        assertEquals(
            (1..12).map { "vless-room-$it" },
            balancer["selector"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(12, outbounds.count { it["protocol"]?.jsonPrimitive?.content == "vless" })
        val socks = outbounds.filter { it["protocol"]?.jsonPrimitive?.content == "socks" }
        assertEquals(12, socks.size)
        assertEquals(
            ports,
            socks.map {
                it["settings"]!!.jsonObject["servers"]!!.jsonArray.single().jsonObject["port"]!!
                    .jsonPrimitive.content.toInt()
            }
        )
    }

    @Test fun vlessBondRejectsEmptyDuplicateOrTooManyPorts() {
        val invalid = listOf(
            emptyList(),
            (21081..21093).toList(),
            listOf(21081, 21082, 21082, 21084),
        )
        for (ports in invalid) {
            assertFailsWith<IllegalArgumentException> {
                XrayConfig.buildVlessBond(spec, ports, "u", "p")
            }
        }
    }
}
