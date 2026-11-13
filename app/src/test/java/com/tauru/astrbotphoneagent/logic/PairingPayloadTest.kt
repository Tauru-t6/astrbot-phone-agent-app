package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {
    private fun payload(endpoint:String="http://100.108.160.92:8766",kind:String="bot",expires:Double=1100.0) =
        """{"type":"phone_control_pairing","version":1,"endpoint":"$endpoint","code":"pcp_${"a".repeat(43)}","expires_at":$expires,"device_kind":"$kind"}"""
    @Test fun acceptsTailscaleAndHttpsButRejectsPublicCleartextOrCredentials() {
        assertTrue(PairingPayloadParser.validEndpoint("http://100.108.160.92:8766"))
        assertTrue(PairingPayloadParser.validEndpoint("https://phone.example.com"))
        for(url in listOf("http://8.8.8.8:8766","http://example.com","https://user:secret@example.com","https://example.com/path","https://example.com?token=x","http://192.168.999.1"))
            assertFalse(url,PairingPayloadParser.validEndpoint(url))
    }
    @Test fun expiredWrongRoleAndUnknownPayloadCannotRedeem() {
        assertEquals("bot",PairingPayloadParser.parse(payload(),"bot",1000.0).deviceKind)
        assertThrows(IllegalArgumentException::class.java){PairingPayloadParser.parse(payload(expires=900.0),"bot",1000.0)}
        assertThrows(IllegalArgumentException::class.java){PairingPayloadParser.parse(payload(kind="personal"),"bot",1000.0)}
        assertThrows(IllegalArgumentException::class.java){PairingPayloadParser.parse(payload().replace("phone_control_pairing","other"),"bot",1000.0)}
    }
}
