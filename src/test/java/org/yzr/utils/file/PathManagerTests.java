package org.yzr.utils.file;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.Assert.assertEquals;

public class PathManagerTests {
    @Test
    public void usesRequestSchemeWithoutProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.addHeader("Host", "apps.example.test:8444");

        assertEquals("https://apps.example.test:8444", PathManager.request(request).getBaseURL());
    }

    @Test
    public void usesOriginalSchemeFromReverseProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.addHeader("Host", "apps.example.test:8444");
        request.addHeader("X-Forwarded-Proto", "https");

        assertEquals("https://apps.example.test:8444", PathManager.request(request).getBaseURL());
    }

    @Test
    public void usesFirstForwardedSchemeFromProxyChain() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.addHeader("Host", "apps.example.test");
        request.addHeader("X-Forwarded-Proto", "https, http");

        assertEquals("https://apps.example.test", PathManager.request(request).getBaseURL());
    }
}
