package com.engabd.sendpin.spotify

import com.google.protobuf.ByteString
import com.spotify.Authentication
import com.spotify.Keyexchange
import com.spotify.metadata.Metadata
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * librespot's generated protobuf classes still work on the protobuf runtime this app
 * ships.
 *
 * librespot-lib 1.6.5 was generated against protobuf-java 3.25, and the app forces a
 * newer runtime over it (first for CVE-2024-7254, then a Dependabot bump to 4.x).
 * Protobuf only promises that a runtime reads gencode from a limited window of older
 * versions, and a mismatch does not fail the build — it fails with a
 * `VerifyError`/`NoSuchMethodError` the first time the Spotify client signs in, on
 * the one path nobody tests without a Premium account. Building, serialising and
 * re-parsing a message from each part of librespot the sign-in touches is enough to
 * load the gencode against the runtime and exercise its reflection tables.
 */
class LibrespotProtobufRuntimeTest {

    @Test
    fun `login credentials round-trip`() {
        val creds = Authentication.LoginCredentials.newBuilder()
            .setUsername("someone")
            .setTyp(Authentication.AuthenticationType.AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS)
            .setAuthData(ByteString.copyFromUtf8("blob"))
            .build()
        val back = Authentication.LoginCredentials.parseFrom(creds.toByteArray())
        assertEquals("someone", back.username)
        assertEquals("blob", back.authData.toStringUtf8())
    }

    @Test
    fun `the key-exchange hello builds and parses`() {
        val hello = Keyexchange.ClientHello.newBuilder()
            .setBuildInfo(
                Keyexchange.BuildInfo.newBuilder()
                    .setProduct(Keyexchange.Product.PRODUCT_CLIENT)
                    .setPlatform(Keyexchange.Platform.PLATFORM_LINUX_X86_64)
                    .setVersion(117300517L),
            )
            .setLoginCryptoHello(
                Keyexchange.LoginCryptoHelloUnion.newBuilder().setDiffieHellman(
                    Keyexchange.LoginCryptoDiffieHellmanHello.newBuilder()
                        .setGc(ByteString.copyFrom(ByteArray(96) { it.toByte() }))
                        .setServerKeysKnown(1),
                ),
            )
            .setClientNonce(ByteString.copyFrom(ByteArray(16)))
            .build()
        val back = Keyexchange.ClientHello.parseFrom(hello.toByteArray())
        assertEquals(117300517L, back.buildInfo.version)
        assertEquals(96, back.loginCryptoHello.diffieHellman.gc.size())
    }

    @Test
    fun `track metadata parses and reads through reflection`() {
        val track = Metadata.Track.newBuilder().setName("A song").setNumber(3).build()
        val back = Metadata.Track.parseFrom(track.toByteArray())
        assertEquals("A song", back.name)
        // The descriptor path is where a gencode/runtime mismatch shows first.
        assertEquals("A song", back.getField(Metadata.Track.getDescriptor().findFieldByName("name")))
    }
}
