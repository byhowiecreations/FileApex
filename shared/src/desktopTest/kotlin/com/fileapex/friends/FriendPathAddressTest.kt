package com.fileapex.friends

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendPathAddressTest {
    private fun local(vararg addresses: String) = addresses.forEach { assertEquals(it, true, FriendPathAddress.isLocal(it)) }
    private fun remote(vararg addresses: String) = addresses.forEach { assertEquals(it, false, FriendPathAddress.isLocal(it)) }

    @Test fun homeAndOfficeNetworkAddressesAreSameNetwork() {
        local("192.168.1.5:5555", "172.16.16.60:41000", "172.31.255.1:9", "10.0.0.2:1", "169.254.3.4:80", "127.0.0.1:1")
    }

    @Test fun localIpv6AddressesAreSameNetwork() {
        local("[fe80::1%wlan0]:5", "[fe80::abcd]:5", "[fd12:3456::1]:9", "[fc00::1]:9", "[::1]:3")
    }

    @Test fun anythingElseIsTheInternet() {
        remote("8.8.8.8:443", "172.32.0.1:5", "172.15.0.1:5", "100.64.1.2:5", "[2001:db8::1]:5", "203.0.113.9:1", "")
    }
}
