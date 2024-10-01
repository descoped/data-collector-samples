package io.descoped.dc.samples.ske.freg;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.HttpStatus;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.samples.ske.maskinporten.MaskinportenAuth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimpleFregMaskinportenTest {

    static final Logger LOG = LoggerFactory.getLogger(SimpleFregMaskinportenTest.class);

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-override.properties") // gitignored
            .build();

    static final Client client = Client.newClient();
    private static Path contentStorePath;

    @BeforeAll
    public static void beforeAll() throws IOException {
        contentStorePath = CommonUtils.currentPath().resolve("target").resolve("store");
        if (!contentStorePath.toFile().exists()) {
            Files.createDirectories(contentStorePath);
        }
    }

    @Disabled
    @Test
    public void collectFregHendelseliste() throws IOException {
        MaskinportenAuth auth = new MaskinportenAuth(MaskinportenAuth.TEST_MASKINPORTEN_NO, configuration.evaluateToString("ssb.ske.freg.test.clientId"), CommonUtils.currentPath().resolve("pkcs12-certs"), "ssb-test-certs");
        String jwtGrant = auth.createMaskinportenJwtGrant();
        LOG.trace("jwtGrant: {}", jwtGrant);
        String jwtAccessToken = auth.getMaskinportenJwtAccessToken(jwtGrant);
        LOG.trace("jwtAccessToken: {}", jwtGrant);

        {
            String url = "https://folkeregisteret-api-konsument.sits.no/folkeregisteret/api/brsv/v1/hendelser/feed?seq=1";
            LOG.trace("GetHendelseliste-URL: {}", url);
            Response response = doGetRequest(url, jwtAccessToken);
            assertEquals(200, response.statusCode(), getHttpError(response));
            LOG.trace("GetResponse: {}", new String(response.body()));
        }
    }

    Response doGetRequest(String url, String token) {
        return doGetRequest(url, token, null);
    }

    Response doGetRequest(String url, String token, String contentType) {
        Request request = Request.newRequestBuilder()
                .url(url)
                .header("Accept", contentType == null ? "application/json" : contentType)
                .header("Authorization", String.format("Bearer %s", token))
                .GET()
                .build();
        return client.send(request);
    }

    String getHttpError(Response response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return String.format("%s [%s] %s%s", response.url(), response.statusCode(), HttpStatus.valueOf(response.statusCode()).reason(), body.isEmpty() ? "" : String.format("%n%s", body));
    }


}
