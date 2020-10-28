package no.ssb.dc.samples.ske.freg;

import no.ssb.dc.api.http.Client;
import no.ssb.dc.api.http.Request;
import no.ssb.dc.api.http.Response;
import no.ssb.dc.core.security.CertificateContext;
import no.ssb.dc.core.security.CertificateFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimpleFregKomplettUttrekkTest {

    static final Logger LOG = LoggerFactory.getLogger(SimpleFregKomplettUttrekkTest.class);

    static SSLContext getBusinessSSLContext() {
        CertificateFactory factory = CertificateFactory.scanAndCreate(Paths.get("/Volumes/SSB BusinessSSL/certs"));
        CertificateContext context = factory.getCertificateContext("ske-p12-certs");
        return context.sslContext();
    }

    @Disabled
    @Test
    void endpoint() {
        Request request = Request.newRequestBuilder()
                .url("https://folkeregisteret.api.skatteetaten.no/folkeregisteret/offentlig-med-hjemmel/api/v1/uttrekk/komplett")
                .GET()
                .build();

        Response response = Client.newClientBuilder()
                .sslContext(getBusinessSSLContext())
                .build()
                .send(request);

        assertEquals(200, response.statusCode());

        LOG.trace("{}", new String(response.body()));
    }
}
