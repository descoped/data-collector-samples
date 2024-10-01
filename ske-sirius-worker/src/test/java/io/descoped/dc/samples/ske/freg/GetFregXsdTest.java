package io.descoped.dc.samples.ske.freg;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.security.BusinessSSLResource;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.core.handler.FileBodyHandler;
import io.descoped.dc.core.security.CertificateContext;
import io.descoped.dc.core.security.CertificateFactory;
import io.descoped.dc.samples.ske.maskinporten.MaskinportenAuth;
import io.descoped.secrets.api.SecretManagerClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GetFregXsdTest {

    static final Logger LOG = LoggerFactory.getLogger(GetFregXsdTest.class);

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-override.properties") // gitignored
            .build();

    static final Client client = Client.newClient();
    static final AtomicReference<CertificateContext> sslContext = new AtomicReference<>();
    static final AtomicReference<String> authToken = new AtomicReference<>();

    @Disabled
    @Test
    void getBrsvXsd() throws IOException {
        Map<String, String> providerConfig = Map.of(
                "secrets.provider", "google-secret-manager",
                "secrets.projectId", "descoped-team",
                "secrets.serviceAccountKeyPath", configuration.evaluateToString("gcp.service-account.file")
        );
        sslContext.set(getBusinessSSLContext(providerConfig));
        authToken.set(getAccessToken());

        Request request = Request.newRequestBuilder()
//                .url("https://folkeregisteret.api.skatteetaten.no/folkeregisteret/api/brsv/v1/personer/xsd")
                .url("https://folkeregisteret.api.skatteetaten.no/folkeregisteret/api/brsv/v1/hendelser/feed/xsd")
                .header("Authorization", String.format("Bearer %s", authToken.get()))
                .GET()
                .build();

//        Path targetFile = CommonUtils.currentPath().resolve("target").resolve("personer-brsv.xsd");
        Path targetFile = CommonUtils.currentPath().resolve("target").resolve("hendleser-feed.xsd");
        Files.createFile(targetFile);
        Response response = client.send(request, new FileBodyHandler(targetFile));
        assertEquals(200, response.statusCode(), new String(response.body()));
    }

    private CertificateContext getBusinessSSLContext(Map<String, String> providerConfig) {
        try (SecretManagerClient client = SecretManagerClient.create(providerConfig)) {
            CertificateFactory certificateFactory = CertificateFactory.create(new GoogleBusinessSSLResource(client));
            return certificateFactory.getCertificateContext("ssb-prod-certs");
        }
    }

    static String getAccessToken() {
        MaskinportenAuth auth = new MaskinportenAuth(MaskinportenAuth.MASKINPORTEN_NO, configuration.evaluateToString("ssb.ske.freg.prod.clientId"), sslContext.get());
        String jwtGrant = auth.createMaskinportenJwtGrant();
        return auth.getMaskinportenJwtAccessToken(jwtGrant);
    }

    static Response doRequest(String url) {
        return doRequest(url, null);
    }

    static Response doRequest(String url, String contentType) {
        Request.Builder requestBuilder = Request.newRequestBuilder().url(url);
        if (contentType != null) {
            requestBuilder.header("Accept", contentType);
        }
        requestBuilder.header("Authorization", String.format("Bearer %s", authToken.get()));
        requestBuilder.GET();

        return client.send(requestBuilder.build());
    }

    static class GoogleBusinessSSLResource implements BusinessSSLResource {
        private final SecretManagerClient client;

        public GoogleBusinessSSLResource(SecretManagerClient client) {
            this.client = client;
        }

        @Override
        public String bundleName() {
            return "ssb-prod-certs";
        }

        @Override
        public String getType() {
            return "p12";
        }

        @Override
        public char[] publicCertificate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public char[] privateCertificate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] archiveCertificate() {
            return client.readBytes("ssb-prod-p12-certificate");
        }

        @Override
        public char[] passphrase() {
            return SecretManagerClient.safeCharArrayAsUTF8(client.readBytes("ssb-prod-p12-passphrase"));
        }

        @Override
        public void close() {
        }
    }

    ;
}
