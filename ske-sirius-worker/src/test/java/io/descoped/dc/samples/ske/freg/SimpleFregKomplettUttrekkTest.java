package io.descoped.dc.samples.ske.freg;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.core.security.CertificateFactory;
import io.descoped.dc.samples.ske.maskinporten.MaskinportenAuth;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/uttrekk/
public class SimpleFregKomplettUttrekkTest {

    static final Logger LOG = LoggerFactory.getLogger(SimpleFregKomplettUttrekkTest.class);
    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-override.properties") // gitignored
            .build();
    static final Boolean USE_MASKINPORTEN_AUTH = false;
    static final AtomicReference<SSLContext> certificateContext = new AtomicReference<>();
    static final AtomicReference<String> authToken = new AtomicReference<>(null);

    @Disabled
    @Test
    void getUttrekkKomplett() throws InterruptedException {
//        String jobId = createJobIdForUttrekkKomplett();
//        String jobId = "c5d3af2f-4ac7-4506-af67-61e71c5f0b8f";
        String jobId = "599a8baf-b7ff-469a-9eca-538a15bf6e97";

        int batchNr = 0;
        List<JsonNode> prevPersonDocumentIdList = null;
        List<JsonNode> personDocumentIdList;
        while (!(personDocumentIdList = getUttrekkBatch(jobId, batchNr)).isEmpty()) {
            LOG.debug("jobId: {} -- batch: {} -- size: {}", jobId, batchNr, personDocumentIdList.size());
            prevPersonDocumentIdList = personDocumentIdList;
            batchNr++;
        }

        assertNotNull(prevPersonDocumentIdList);
        assertFalse(prevPersonDocumentIdList.isEmpty());

        String lastPersonDocumentId = JqHelper.queryStringLiteral(prevPersonDocumentIdList.get(prevPersonDocumentIdList.size() - 1), ".");
//        String lastPersonDocumentId = prevPersonDocumentIdList.get(prevPersonDocumentIdList.size()-1).asText();
        LOG.trace("{}", lastPersonDocumentId);

        Response personDocument = getPersonDocument(lastPersonDocumentId);
        LOG.trace("{}", new String(personDocument.body()));
    }

    @Test
    void match404ErrorMessage() {
        Pattern pattern = Pattern.compile("^(Batch med id=\\d+ er enda ikke klar)$");
        IntStream.range(0, 150).forEach(i -> assertTrue(pattern.matcher(String.format("Batch med id=%s er enda ikke klar", i)).matches()));
    }

    String createJobIdForUttrekkKomplett() {
        Response response = doRequest("https://folkeregisteret.api.skatteetaten.no/folkeregisteret/offentlig-med-hjemmel/api/v1/uttrekk/komplett?feedsekvensnr=0");
        assertEquals(200, response.statusCode(), new String(response.body()));
        String jobId = JqHelper.queryStringLiteral(response.body(), ".jobbId");
        LOG.debug("jobId: {}", jobId);
        return jobId;
    }

    List<JsonNode> getUttrekkBatch(String jobId, int batchNr) throws InterruptedException {
        Response response;
        while ((response = doRequest(String.format("https://folkeregisteret.api.skatteetaten.no/folkeregisteret/offentlig-med-hjemmel/api/v1/uttrekk/%s/batch/%s", jobId, batchNr))).statusCode() == 404) {
            LOG.warn("retry in 15 sec - {} -- {}", response.statusCode(), new String(response.body()));
            Thread.sleep(15 * 1000);
        }
        assertEquals(200, response.statusCode(), new String(response.body()));
        List<JsonNode> personDocumentIdList = JqHelper.queryList(response.body(), ".dokumentidentifikator[]");
        return personDocumentIdList;
    }

    Response getPersonDocument(String personDocumentId) {
        Response response = doRequest(String.format("https://folkeregisteret.api.skatteetaten.no/folkeregisteret/offentlig-med-hjemmel/api/v1/personer/arkiv/%s?part=person-basis&part=identitetsgrunnlag-utvidet&part=relasjon-utvidet&part=utlendingsmyndighetenesIdentifikasjonsnummer&part=innflytting&part=utflytting&part=foedselINorge&part=opphold&part=forholdTilSametingetsValgmanntall",
                        personDocumentId),
                "application/xml"
        );
        assertEquals(200, response.statusCode(), new String(response.body()));
        return response;
    }

    //
    // ---------------------------------------------------------------------------------------------------------------
    //

    static SSLContext getBusinessSSLContext() {
        CertificateFactory factory = CertificateFactory.scanAndCreate(Paths.get("/Volumes/SSB BusinessSSL/certs"));
        return factory.getCertificateContext("ske-prod-certs").sslContext();
    }

    static String getAccessToken() {
        MaskinportenAuth auth = new MaskinportenAuth(MaskinportenAuth.TEST_MASKINPORTEN_NO, configuration.evaluateToString("ssb.ske.freg.test.clientId"), Paths.get("/Volumes/SSB BusinessSSL/certs"), "ske-prod-certs");
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
        if (USE_MASKINPORTEN_AUTH) {
            if (authToken.get() == null) {
                authToken.set(getAccessToken());
            }
            requestBuilder.header("Authorization", String.format("Bearer %s", authToken.get()));
        } else {
            if (certificateContext.get() == null) {
                certificateContext.set(getBusinessSSLContext());
            }
        }
        requestBuilder.GET();

        Client.Builder clientBuilder = Client.newClientBuilder();
        if (!USE_MASKINPORTEN_AUTH) {
            clientBuilder.sslContext(certificateContext.get());
        }
        return clientBuilder.build().send(requestBuilder.build());
    }

}
