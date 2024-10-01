package io.descoped.dc.samples.altinn3.test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Builders;
import io.descoped.dc.api.Specification;
import io.descoped.dc.api.context.ExecutionContext;
import io.descoped.dc.api.node.Configuration;
import io.descoped.dc.api.node.Configurations;
import io.descoped.dc.api.node.Security;
import io.descoped.dc.api.node.builder.BuildContext;
import io.descoped.dc.api.node.builder.JwtIdentityBuilder;
import io.descoped.dc.api.node.builder.JwtIdentityTokenBodyPublisherProducerBuilder;
import io.descoped.dc.api.node.builder.SecurityBuilder;
import io.descoped.dc.api.node.builder.SpecificationBuilder;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.api.util.JsonParser;
import io.descoped.dc.application.ssl.SecretManagerSSLResource;
import io.descoped.dc.core.executor.Worker;
import io.descoped.dc.core.handler.JwtTokenBodyPublisherProducerHandler;
import io.descoped.dc.core.security.CertificateFactory;
import io.descoped.dc.test.client.ResponseHelper;
import io.descoped.dc.test.client.TestClient;
import io.descoped.dc.test.server.TestServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.descoped.dc.api.Builders.*;
import static io.descoped.dc.api.node.builder.SpecificationBuilder.GLOBAL_CONFIGURATION;

public class AltinnProdWorkerTest {

    static final Logger LOG = LoggerFactory.getLogger(AltinnProdWorkerTest.class);

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-override.properties") // gitignored
            .values("content.stream.connector", "rawdata")
            .values("rawdata.client.provider", "filesystem")

            .values("data.collector.worker.threads", "20")
            .values("data.collector.rawdata.dump.enabled", "true")
            .values("data.collector.rawdata.dump.location", "")
            .values("data.collector.rawdata.dump.topic", "altinn-test")

            .values("local-temp-folder", "target/avro/temp")
            .values("filesystem.storage-folder", "target/avro/rawdata-store")
            .values("avro-file.max.seconds", "60")
            .values("avro-file.max.bytes", Long.toString(64 * 1024 * 1024)) // 512 MiB
            .values("avro-file.sync.interval", Long.toString(200))
            .values("listing.min-interval-seconds", "0")

            .values("data.collector.sslBundle.provider", "google-secret-manager")
            .values("data.collector.sslBundle.gcp.projectId", "descoped-team")
            .values("data.collector.sslBundle.gcp.serviceAccountKeyPath", "/Users/oranheim/bin/descoped-team-rawdata-altinn3-8bc551b20687.json")
            .values("data.collector.sslBundle.type", "p12")
            .values("data.collector.sslBundle.name", "ssb-test-certs")
            .values("data.collector.sslBundle.archiveCertificate", "ssb-prod-p12-certificate")
            .values("data.collector.sslBundle.passphrase", "ssb-prod-p12-passphrase")

            .values("rawdata.encryption.provider", "google-secret-manager")
            .values("rawdata.encryption.gcp.serviceAccountKeyPath", "/Users/oranheim/bin/descoped-team-rawdata-altinn3-8bc551b20687.json")
            .values("rawdata.encryption.gcp.projectId", "descoped-team")
            .values("rawdata.encryption.key", "rawdata-staging-altinn3-encryption-key")
            .values("rawdata.encryption.salt", "rawdata-staging-altinn3-encryption-salt")
            .build();

    static final SpecificationBuilder specificationBuilder = Specification.start("ALTINN-TEST", "Altinn 3", "maskinporten-jwt-grant")
            .configure(context()
                    .topic("altinn-test")
                    .variable("nextPage", null) // TODO ask Altinn about self and next url parameters
                    .variable("appId", "${ENV.'ssb.altinn.app-id'}")
                    .variable("clientId", "${ENV.'ssb.altinn.test.clientId'}")
                    .variable("jwtGrantTimeToLiveInSeconds", "${ENV.'ssb.jwtGrant.expiration'}")
            )
            .configure(security()
                            .identity(jwt("maskinporten",
                                            headerClaims()
                                                    .alg("RS256")
                                                    .x509CertChain("ssb-test-certs"),
                                            claims()
                                                    .audience("https://ver2.maskinporten.no/")
                                                    .issuer("${clientId}")
                                                    .claim("resource", "https://tt02.altinn.no/maskinporten-api/")
//                                    .claim("scope", "altinn:instances.read altinn:instances.write")
                                                    .claim("scope", "altinn:serviceowner/instances.read altinn:serviceowner/instances.write")
                                                    .timeToLiveInSeconds("${jwtGrantTimeToLiveInSeconds}")
                                    )
                            )
            ).function(post("maskinporten-jwt-grant")
                    .url("https://ver2.maskinporten.no/token/api/v1/token")
                    .data(bodyPublisher()
                            .urlEncoded(jwtToken()
                                    .identityId("maskinporten")
                                    .bindTo("JWT_GRANT")
                                    .token("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${JWT_GRANT}")
                            )
                    )
                    .validate(status().success(200))
                    .pipe(execute("altinn-jwt-replacement-token")
                            .inputVariable("accessToken", jqpath(".access_token"))
                    )
            ).function(get("altinn-jwt-replacement-token")
                    .url("https://platform.tt02.altinn.no/authentication/api/v1/exchange/maskinporten")
                    .header("Content-Type", "plain/text")
                    .header("Authorization", "Bearer ${accessToken}")
                    .validate(status().success(200))
                    .pipe(execute("loop")
                            .inputVariable("accessToken", body())
                    )
            ).function(paginate("loop")
                    .variable("fromPage", "${nextPage}")
                    //.addPageContent("fromPage")
                    .iterate(execute("page")
                            .requiredInput("accessToken")
                    )
                    .until(whenVariableIsNull("nextPage"))
            ).function(get("page")
                    .url("https://platform.tt02.altinn.no/storage/api/v1/instances?org=ssb&appId=${appId}")
                    .header("Authorization", "Bearer ${accessToken}")
                    .pipe(sequence(jqpath(".instances[]"))
                            .expected(regex(jqpath(".id"), "([^\\/]+$)"))
                    )
                    .pipe(parallel(jqpath(".instances[]"))
                            .variable("position", regex(jqpath(".id"), "([^\\/]+$)")) // instanceGuid is position
                            .variable("ownerPartyId", jqpath(".instanceOwner.partyId"))
                            .pipe(addContent("${position}", "entry")
                                    .storeState("ownerPartyId", "${ownerPartyId}")
                                    .storeState("instanceGuid", "${position}")
                                    .storeState("ackURL", "https://platform.tt02.altinn.no/storage/api/v1/sbl/instances/${ownerPartyId}/${position}")
                            )
                            .pipe(forEach(jqpath(".data[]"))
                                    .pipe(execute("download-file")
                                            .requiredInput("accessToken")
                                            .inputVariable("dataId", jqpath(".id"))
                                            .inputVariable("instanceGuid", jqpath(".instanceGuid"))
                                    )
                            )
                            .pipe(publish("${position}"))
                    )
                    .validate(status().success(200))
            ).function(get("download-file")
                    .url("https://platform.tt02.altinn.no/storage/api/v1/instances/${ownerPartyId}/${instanceGuid}/data/${dataId}")
                    .header("Authorization", "Bearer ${accessToken}")
                    .header("Accept", "application/xml")
                    .pipe(addContent("${position}", "file-${dataId}"))
                    .validate(status()
                            .success(200)
                            .success(500) // inconsistent test data causes 500 error
                            .success(404) // 404 is due to success on 500 error
                    )
            );

    @Disabled
    @Test
    void collect() {
        String serialized = specificationBuilder.serialize();
        //LOG.trace("{}", serialized);

        Worker.newBuilder()
                .configuration(configuration.asMap())
                .specification(specificationBuilder)
                .useBusinessSSLResourceSupplier(() -> new SecretManagerSSLResource(configuration))
                .build()
                .run();
    }

    @Disabled
    @Test
    void jwtGrant() {
        JwtIdentityBuilder jwtIdentityBuilder = Builders.jwt("test",
                headerClaims()
                        .alg("RS256")
                        .x509CertChain("ssb-test-certs"),
                claims()
                        .audience("aud")
                        .issuer("abcdef")
                        .timeToLiveInSeconds("30")
        );

        SecurityBuilder securityBuilder = security();
        securityBuilder.identity(jwtIdentityBuilder);
        Security security = securityBuilder.build();

        LinkedHashMap<String, Object> nodeInstanceById = new LinkedHashMap<>();
        Map<Class<? extends Configuration>, Configuration> configurationMap = new LinkedHashMap<>();
        configurationMap.put(Security.class, security);
        nodeInstanceById.put(GLOBAL_CONFIGURATION, new Configurations(configurationMap));
        BuildContext buildContext = BuildContext.of(new LinkedHashMap<>(), nodeInstanceById);

        JwtIdentityTokenBodyPublisherProducerBuilder jwtIdentityTokenBuilder = new JwtIdentityTokenBodyPublisherProducerBuilder();
        jwtIdentityTokenBuilder.identityId("test").bindTo("JWT_GRANT").token("grantType=${JWT_GRANT}");

        JwtTokenBodyPublisherProducerHandler handler = new JwtTokenBodyPublisherProducerHandler(jwtIdentityTokenBuilder.build(buildContext));
        ExecutionContext context = ExecutionContext.empty();
        CertificateFactory certificateFactory = CertificateFactory.scanAndCreate(CommonUtils.currentPath());
        context.services().register(CertificateFactory.class, certificateFactory);
        handler.execute(context);
    }

    @Disabled
    @Test
    void collectAndWriteToOutput() throws InterruptedException {
        TestServer testServer = TestServer.create(configuration);
        TestClient testClient = TestClient.create(testServer);
        testServer.start();
        testClient.put("/tasks", specificationBuilder.serialize());
        while (true) {
            ResponseHelper<String> response = testClient.get("/tasks");
            ArrayNode list = JsonParser.createJsonParser().fromJson(response.body(), ArrayNode.class);
            if (list.size() > 0) {
                Thread.sleep(250);
                continue;
            }
            break;
        }
        Thread.sleep(1000);
        testServer.stop();
    }

    @Disabled
    @Test
    public void writeTargetConsumerSpec() throws IOException {
        Path currentPath = CommonUtils.currentPath().getParent().getParent();
        Path targetPath = currentPath.resolve("data-collection-consumer-specifications");

        boolean targetProjectExists = targetPath.toFile().exists();
        if (!targetProjectExists) {
            throw new RuntimeException(String.format("Couldn't locate '%s' under currentPath: %s%n", targetPath.toFile().getName(), currentPath.toAbsolutePath().toString()));
        }

        Files.writeString(targetPath.resolve("specs").resolve("altinn3-test-spec.json"), specificationBuilder.serialize());
    }

}
