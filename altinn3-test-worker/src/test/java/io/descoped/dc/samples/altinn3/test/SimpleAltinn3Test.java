package io.descoped.dc.samples.altinn3.test;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Builders;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.HttpStatus;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.api.util.JsonParser;
import io.descoped.dc.core.handler.Queries;
import io.descoped.dc.core.security.CertificateContext;
import io.descoped.dc.core.security.CertificateFactory;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * https://difi.github.io/felleslosninger/maskinporten_protocol_jwtgrant.html
 * <p>
 * https://docs.altinn.studio/teknologi/altinnstudio/altinn-api/swagger/storage/#/Instances
 * <p>
 * https://github.com/auth0/java-jwt
 * <p>
 * https://github.com/ks-no/fiks-maskinporten/blob/master/maskinporten-client/src/main/java/no/ks/fiks/maskinporten/Maskinportenklient.java
 * <p>
 * https://github.com/Altinn/altinn-cli/blob/master/AltinnCLI/Commands/Login/SubCommandHandlers/MaskinportenLoginHandler.cs
 * <p>
 * https://github.com/Altinn/altinn-cli/blob/master/AltinnCLI/Commands/Storage/StorageClientWrapper.cs#L45
 */
public class SimpleAltinn3Test {

    static final Logger LOG = LoggerFactory.getLogger(SimpleAltinn3Test.class);
    static final boolean LOG_ACCESS_TOKENS = false;

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-override.properties") // gitignored
            .build();

    static final Client client = Client.newClient();
    private static Path contentStorePath;

    @BeforeAll
    static void beforeAll() throws IOException {
        contentStorePath = CommonUtils.currentPath().resolve("target").resolve("store");
        if (!contentStorePath.toFile().exists()) {
            Files.createDirectories(contentStorePath);
        }
    }

    @Disabled
    @Test
    void collectAltinnData() throws IOException {
        String jwtGrant = createMaskinportenJwtGrant(configuration.evaluateToInt("ssb.jwtGrant.expiration"));

        // Request JWT access token from Maskinporten
        String jwtAccessToken = getMaskinportenJwtAccessToken(jwtGrant);

        // Replace Maskinporten JWT med Altinn JWT
        String jwtReplacementToken = getAltinnJwtGrant(jwtAccessToken);

        // Get Instances
        List<JsonNode> instanceList;
        {
//            String url = String.format("https://platform.tt02.altinn.no/storage/api/v1/instances?org=ssb&appId=%s", configuration.evaluateToString("ssb.altinn.app-id"));
            String url = String.format("https://platform.altinn.no/storage/api/v1/instances?org=ssb&appId=%s", configuration.evaluateToString("ssb.altinn.app-id"));
            LOG.trace("GetInstanceData-URL: {}", url);
            Response response = doGetRequest(url, jwtReplacementToken);
            assertEquals(200, response.statusCode(), getHttpError(response));
            //LOG.trace("GetInstanceData: {}", new String(response.body()));

            instanceList = jqQueryList(response.body(), ".instances[]");
            LOG.trace("instance-list-size: {}", instanceList.size());
        }

        // Get Instance Data
        {
            /*
             * 1. iterer over hver instans
             * 2. iterer over data element
             * 3. hent ned dokument
             */
            TikaConfig config = TikaConfig.getDefaultConfig();
            Detector detector = config.getDetector();
            List<MediaType> textMediaTypes = List.of("plain/text", "application/json", "application/xml").stream().map(MediaType::parse).collect(Collectors.toList());

            for (JsonNode instance : instanceList) {
                String ownerPartyId = jqQueryStringLiteral(instance, ".instanceOwner.partyId");
                String instanceId = jqQueryStringLiteral(instance, ".id");
                List<JsonNode> dataElements = jqQueryList(instance, ".data[]");

                String entryInstanceGuid = instanceId.substring(ownerPartyId.concat("/").length());
                writeMetadataContent(entryInstanceGuid, JsonParser.createJsonParser().toPrettyJSON(instance).getBytes());

                for (JsonNode dataElement : dataElements) {
                    String dataId = jqQueryStringLiteral(dataElement, ".id");
                    String instanceGuid = jqQueryStringLiteral(dataElement, ".instanceGuid");
//                    String url = String.format("https://platform.tt02.altinn.no/storage/api/v1/instances/%s/data/%s", instanceId, dataId);
                    String url = String.format("https://platform.altinn.no/storage/api/v1/instances/%s/data/%s", instanceId, dataId);
                    LOG.trace("Instance-Data-URL: {}", url);
                    Response response = doGetRequest(url, jwtReplacementToken, "application/xml");
                    if (response.statusCode() == 500) {
                        LOG.error("Error: {}", getHttpError(response));
                        continue;
                    }
                    assertEquals(200, response.statusCode(), getHttpError(response));

                    String content = new String(response.body(), StandardCharsets.UTF_8);
                    MediaType mediaType = detector.detect(new ByteArrayInputStream(content.getBytes()), new Metadata());
                    /*
                    boolean isTextContent = textMediaTypes.stream().anyMatch(mediaType::equals);
                    if (!isTextContent) {
                        content = new String(Base64.encode(content.getBytes()), StandardCharsets.UTF_8);
                    }

                    LOG.trace("Instance-Data: {} -- {} -> {}", instanceId, mediaType, content);
                    */

                    writeContent(instanceGuid, dataId, response.body(), mediaType);
                }
            }
        }
    }

    String createMaskinportenJwtGrant(int expirationInSeconds) {
        // Load BusinessSSL
        final Path scanDirectory = CommonUtils.currentPath().resolve("certs");
        LOG.trace("Certs dir: {}", scanDirectory);
        CertificateFactory certificateFactory = CertificateFactory.scanAndCreate(scanDirectory);
//        CertificateContext certificateContext = certificateFactory.getCertificateContext("ssb-test-certs");
        CertificateContext certificateContext = certificateFactory.getCertificateContext("ssb-prod-certs");
        assertNotNull(certificateContext);

        // Create Java JWT Algorithm using RSA PublicKey and PrivateKey
        KeyPair keyPair = certificateContext.keyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        Algorithm algorithm = Algorithm.RSA256(publicKey, privateKey);

        // Print Issuer SHA1 Thumbprint
        //String thumbprint = DatatypeConverter.printHexBinary(MessageDigest.getInstance("SHA-1").digest(cert.getEncoded())).toLowerCase();
        //LOG.trace("thumbprint: {}", thumbprint);

        // Create JWT Header
        Map<String, Object> headerClaims = new HashMap<>();
        headerClaims.put("alg", "RS256");
        headerClaims.put("x5c", List.of(certificateContext.trustManager().getAcceptedIssuers()).stream().map(this::getEncodedCertificate).collect(Collectors.toList()));

        // Create JWT Grant and Sign
        return JWT.create()
                .withHeader(headerClaims)
//                .withAudience("https://ver2.maskinporten.no/")
                .withAudience("https://maskinporten.no/")
//                .withClaim("resource", "https://tt02.altinn.no/maskinporten-api/")
                .withClaim("resource", "https://altinn.no/maskinporten-api/")
                .withClaim("scope", "altinn:instances.read altinn:instances.write")
//                .withClaim("scope", "altinn:serviceowner/intances.read altinn:serviceowner/intances.write")
//                .withClaim("scope", "serviceowner/intances.read serviceowner/intances.write")
//                .withIssuer(configuration.evaluateToString("ssb.altinn.test.clientId"))
                .withIssuer(configuration.evaluateToString("ssb.altinn.prod.clientId"))
                .withExpiresAt(Date.from(Instant.now().atOffset(ZoneOffset.UTC).plusSeconds(expirationInSeconds).toInstant()))
                .withIssuedAt(Date.from(Instant.now().atOffset(ZoneOffset.UTC).toInstant()))
                .withJWTId(UUID.randomUUID().toString())
                .sign(algorithm);
    }

    String getMaskinportenJwtAccessToken(String jwtGrant) {
        String payload = String.format("grant_type=%s&assertion=%s", "urn:ietf:params:oauth:grant-type:jwt-bearer", jwtGrant);
        Request request = Request.newRequestBuilder()
//                .url("https://ver2.maskinporten.no/token")
                .url("https://maskinporten.no/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(payload.getBytes())
                .build();
        Response response = client.send(request);
        assertEquals(200, response.statusCode(), getHttpError(response));
        JsonNode jsonNode = JsonParser.createJsonParser().fromJson(new String(response.body(), StandardCharsets.UTF_8), JsonNode.class);
        String accessToken = jsonNode.get("access_token").asText();
        if (LOG_ACCESS_TOKENS) {
            LOG.debug("Acquired-Maskinporten-AccessToken: {}", accessToken);
        }
        return accessToken;
    }

    String getAltinnJwtGrant(String maskinportenJwtAccessToken) {
//        Response response = doGetRequest("https://platform.tt02.altinn.no/authentication/api/v1/exchange/maskinporten", maskinportenJwtAccessToken, "plain/text");
        Response response = doGetRequest("https://platform.altinn.no/authentication/api/v1/exchange/maskinporten", maskinportenJwtAccessToken, "plain/text");
        assertEquals(200, response.statusCode(), getHttpError(response));
        String replacementAccessToken = new String(response.body(), StandardCharsets.UTF_8);
        if (LOG_ACCESS_TOKENS) {
            LOG.debug("Replacement-Altinn-AccessToken: {}", replacementAccessToken);
        }
        return replacementAccessToken;
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

    List<JsonNode> jqQueryList(Object data, String expression) {
        return (List<JsonNode>) Queries.from(Builders.jqpath(expression).build()).evaluateList(data);
    }

    Object jqQueryObject(Object data, String expression) {
        return Queries.from(Builders.jqpath(expression).build()).evaluateObject(data);
    }

    String jqQueryStringLiteral(Object data, String expression) {
        return Queries.from(Builders.jqpath(expression).build()).evaluateStringLiteral(data);
    }

    byte[] getEncodedCertificate(X509Certificate crt) {
        try {
            return crt.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    void writeMetadataContent(String instanceId, byte[] content) throws IOException {
        Path contentPath = contentStorePath.resolve(instanceId);
        if (!contentPath.toFile().exists()) {
            Files.createDirectories(contentPath);
        }
        Path contentFile = contentPath.resolve("instance-metadata.json");
        LOG.info("Write metadata file: {}", contentFile);
        Files.write(contentFile, content);
    }

    void writeContent(String instanceGuid, String dataId, byte[] content, MediaType mediaType) throws IOException {
        Path contentPath = contentStorePath.resolve(instanceGuid);
        if (!contentPath.toFile().exists()) {
            Files.createDirectories(contentPath);
        }
        Path contentFile = contentPath.resolve(dataId + "." + mediaType.getSubtype());
        LOG.info("Write file: {}", contentFile);
        Files.write(contentFile, content);
    }

    String getHttpError(Response response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return String.format("%s [%s] %s%s", response.url(), response.statusCode(), HttpStatus.valueOf(response.statusCode()).reason(), body.isEmpty() ? "" : String.format("%n%s", body));
    }
}
