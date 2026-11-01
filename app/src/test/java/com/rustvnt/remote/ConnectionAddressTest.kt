package com.rustvnt.remote

import org.junit.Assert.*
import org.junit.Test

class ConnectionAddressTest {
    @Test fun usesDesktopDefaultPortAndPreservesIpv6() {
        assertEquals(ConnectionAddress.Address("desktop.example",29875),ConnectionAddress.parse(" desktop.example "))
        assertEquals(ConnectionAddress.Address("192.168.1.4",40000),ConnectionAddress.parse("192.168.1.4:40000"))
        assertEquals(ConnectionAddress.Address("2001:db8::1",29875),ConnectionAddress.parse("2001:db8::1"))
        assertEquals(ConnectionAddress.Address("2001:db8::1",40000),ConnectionAddress.parse("[2001:db8::1]:40000"))
    }
    @Test fun formattedAddressesRoundTrip() {
        for(host in listOf("pc.local","192.168.1.4","2001:db8::1"))for(port in listOf(29875,65535)) {
            assertEquals(ConnectionAddress.Address(host,port),ConnectionAddress.parse(ConnectionAddress.format(host,port)))
        }
    }
    @Test fun rejectsBadPortsAndAccidentalUrlsBeforeConnecting() {
        for(value in listOf("","pc:0","pc:65536","pc:abc","http://pc","pc name","[::1","[::1]tail",":29875")) {
            try {ConnectionAddress.parse(value);fail("accepted $value")}catch(_:IllegalArgumentException){}
        }
    }
}
