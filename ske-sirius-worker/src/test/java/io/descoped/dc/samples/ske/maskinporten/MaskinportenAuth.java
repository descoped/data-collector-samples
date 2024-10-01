package io.descoped.dc.samples.ske.maskinporten;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.HttpStatus;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.util.JsonParser;
import io.descoped.dc.core.security.CertificateContext;
import io.descoped.dc.core.security.CertificateFactory;
import io.descoped.dc.samples.ske.freg.SimpleFregMaskinportenTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MaskinportenAuth {

    static final Logger LOG = LoggerFactory.getLogger(SimpleFregMaskinportenTest.class);
    static final boolean LOG_ACCESS_TOKENS = false;
    public static final String TEST_MASKINPORTEN_NO = "https://ver2.maskinporten.no/";
    public static final String MASKINPORTEN_NO = "https://maskinporten.no/";

    private final String maskinportenURL;
    private final String clientId;
    private final CertificateContext certificateContext;
    private final Client client;

    public MaskinportenAuth(String maskinportenURL, String clientId, Path certsFolder, String sslBundleName) {
        this.maskinportenURL = maskinportenURL;
        this.clientId = clientId;
        // Load BusinessSSL
        assertTrue(certsFolder.toFile().exists());
        CertificateFactory certificateFactory = CertificateFactory.scanAndCreate(certsFolder);
        certificateContext = certificateFactory.getCertificateContext(sslBundleName);
        assertNotNull(certificateContext);

        this.client = Client.newClientBuilder().sslContext(certificateContext.sslContext()).build();
    }

    public MaskinportenAuth(String maskinportenURL, String clientId, CertificateContext certificateContext) {
        this.maskinportenURL = maskinportenURL;
        this.clientId = clientId;
        // Load BusinessSSL
        this.certificateContext = certificateContext;
        this.client = Client.newClientBuilder().sslContext(certificateContext.sslContext()).build();
    }

    public String createMaskinportenJwtGrant() {
        return createMaskinportenJwtGrant(30);
    }

    public String createMaskinportenJwtGrant(int expirationInSeconds) {
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
                .withAudience(MASKINPORTEN_NO)
                .withClaim("scope", "folkeregister:deling/svalbardregister folkeregister:deling/offentligmedhjemmel")
                .withIssuer(clientId)
                .withExpiresAt(Date.from(Instant.now().atOffset(ZoneOffset.UTC).plusSeconds(expirationInSeconds).toInstant()))
                .withIssuedAt(Date.from(Instant.now().atOffset(ZoneOffset.UTC).toInstant()))
                .withJWTId(UUID.randomUUID().toString())
                .sign(algorithm);
    }

    public String getMaskinportenJwtAccessToken(String jwtGrant) {
        String payload = String.format("grant_type=%s&assertion=%s", "urn:ietf:params:oauth:grant-type:jwt-bearer", jwtGrant);
        Request request = Request.newRequestBuilder()
                .url(MASKINPORTEN_NO + "/token/v1/token")
//                .header("Accept", "application/xml")
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

    byte[] getEncodedCertificate(X509Certificate crt) {
        try {
            return crt.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    String getHttpError(Response response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        return String.format("%s [%s] %s%s", response.url(), response.statusCode(), HttpStatus.valueOf(response.statusCode()).reason(), body.isEmpty() ? "" : String.format("%n%s", body));
    }

}
