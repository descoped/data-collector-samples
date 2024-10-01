package io.descoped.dc.samples.toll.tvinn;

import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.core.security.CertificateContext;
import io.descoped.dc.core.security.CertificateFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimpleTvinnFeedTest {

    final static Logger LOG = LoggerFactory.getLogger(SimpleTvinnFeedTest.class);
    final static String TEST_BASE_URL = "https://api-test.toll.no";

    static SSLContext getBusinessSSLContext() {
//        CertificateFactory factory = CertificateFactory.scanAndCreate(CommonUtils.currentPath().getParent());
        CertificateFactory factory = CertificateFactory.scanAndCreate(Paths.get("/Volumes/SSB BusinessSSL/certs"));
        CertificateContext context = factory.getCertificateContext("ssb-test-certs");
        return context.sslContext();
    }

    /*
curl -H "Authorization: Bearer ACCESS_TOKEN" \
    -i "https://api-test.toll.no/api/declaration/declaration-clearance-feed/atom?marker=last&limit=5&direction=forward"

curl -proxy "https://proxy.ssb.no:3128" -H "Authorization: Bearer ACCESS_TOKEN" \
    -i "https://api-test.toll.no/api/declaration/declaration-clearance-feed/atom?marker=last&limit=5&direction=forward"

curl -proxy "https://proxy.ssb.no:3128" -H "Authorization: Bearer ACCESS_TOKEN" -i "https://api-test.toll.no/api/declaration/declaration-clearance-feed/atom?marker=last&limit=5&direction=forward"

     */

    Response getPage(Client client, String fromMarker, int numberOfEvents) {
        String url = String.format("%s/api/declaration/declaration-clearance-feed/atom?marker=%s&limit=%s&direction=forward", TEST_BASE_URL, fromMarker, numberOfEvents);
        LOG.info("getPage: {}", url);
        String ACCESS_TOKEN = "EXECUTE  fetch-tvinn-feed-with-maskinporten.sh and COPY accessToken=CHARS_BETWEEN_EQUAL_AND_END_CURLY BRACKET";
        Request request = Request.newRequestBuilder()
                .GET()
                .header("Authorization", String.format("Bearer %s", ACCESS_TOKEN))
                .header("Content-Type", "application/xml")
                .url(url)
                .build();
        return client.send(request);
    }

    @Disabled
    @Test
    void testFeed() {
        Client client = Client.newClientBuilder().sslContext(getBusinessSSLContext()).build();
//        Response response = getPage("last", 1);
        Response response = getPage(client, "last", 5);
        LOG.info("body: {}", new String(response.body()));
        assertEquals(200, response.statusCode());
//        LOG.trace("{}", new String(response.body()));
    }
}
