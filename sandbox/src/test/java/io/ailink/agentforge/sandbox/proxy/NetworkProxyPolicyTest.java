package io.ailink.agentforge.sandbox.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NetworkProxyPolicyTest {
    @ParameterizedTest
    @CsvSource({"example.com,example.com,ALLOW", "api.example.com,*.example.com,ALLOW", "example.com,*.example.com,DENY",
            "example.com,**.example.com,ALLOW", "a.b.example.com,**.example.com,ALLOW"})
    void hostPatternsUseCodexApexRules(String host, String pattern, HostPolicy.Decision expected) {
        assertEquals(expected, new HostPolicy(List.of(pattern), List.of(), HostPolicy.Mode.FULL, HostPolicy.Fallback.DENY).decide(host, "GET"));
    }

    @Test
    void denyWinsOverAllow() {
        assertEquals(HostPolicy.Decision.DENY,
                new HostPolicy(List.of("*.example.com"), List.of("api.example.com"), null, null).decide("API.Example.COM.:443", "GET"));
    }

    @Test
    void limitedAllowsOnlyReadMethods() {
        HostPolicy pol = new HostPolicy(List.of("*"), List.of(), HostPolicy.Mode.LIMITED, null);
        assertEquals(HostPolicy.Decision.ALLOW, pol.decide("example.com", "OPTIONS"));
        assertEquals(HostPolicy.Decision.DENY, pol.decide("example.com", "POST"));
        assertEquals(HostPolicy.Decision.DENY, pol.decide("example.com"));
    }

    @Test
    void literalIpsAreNormalizedWithoutDns() {
        assertEquals("127.0.0.1", HostPolicy.normalizeHost("127.0.0.1:8080"));
        assertEquals("::1", HostPolicy.normalizeHost("[0:0:0:0:0:0:0:1]:443"));
        assertEquals("2001:db8::1", HostPolicy.normalizeHost("[2001:DB8:0:0:0:0:0:1]"));
        assertEquals("example.com", HostPolicy.normalizeHost(" Example.COM.. "));
        assertNull(HostLiterals.parseLiteral("999.1.1.1"));
        assertNull(HostLiterals.parseLiteral("example.com"));
    }
}
