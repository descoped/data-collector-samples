package io.descoped.dc.samples.ske.freg;

import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.dc.api.Specification;
import io.descoped.dc.api.node.builder.SpecificationBuilder;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.core.executor.Worker;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.descoped.dc.api.Builders.addContent;
import static io.descoped.dc.api.Builders.bodyPublisher;
import static io.descoped.dc.api.Builders.claims;
import static io.descoped.dc.api.Builders.context;
import static io.descoped.dc.api.Builders.execute;
import static io.descoped.dc.api.Builders.get;
import static io.descoped.dc.api.Builders.headerClaims;
import static io.descoped.dc.api.Builders.jqpath;
import static io.descoped.dc.api.Builders.jwt;
import static io.descoped.dc.api.Builders.jwtToken;
import static io.descoped.dc.api.Builders.nextPage;
import static io.descoped.dc.api.Builders.paginate;
import static io.descoped.dc.api.Builders.parallel;
import static io.descoped.dc.api.Builders.post;
import static io.descoped.dc.api.Builders.publish;
import static io.descoped.dc.api.Builders.regex;
import static io.descoped.dc.api.Builders.security;
import static io.descoped.dc.api.Builders.sequence;
import static io.descoped.dc.api.Builders.status;
import static io.descoped.dc.api.Builders.whenVariableIsNull;
import static io.descoped.dc.api.Builders.xpath;
import static org.junit.jupiter.api.Assertions.assertTrue;

// https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/oppslag/
public class FregSvalbardWithMaskinportenWorkerTest {

    static final SpecificationBuilder specificationBuilder = Specification.start("SKE-FREG-BRSV-KONSUMENT", "Collect FREG Svalbard", "maskinporten-jwt-grant")
            .configure(context()
                            .topic("freg-brsv-konsument-test")
//                    .variable("clientId", "${ENV.'ssb.ske.freg.test.clientId'}")
                            .variable("clientId", "${ENV.'ssb.ske.freg.prod.clientId'}")
                            .variable("jwtGrantTimeToLiveInSeconds", "${ENV.'ssb.jwtGrant.expiration'}")
                            .variable("PlaygroundURL", "https://folkeregisteret-api-konsument-playground.sits.no")
                            .variable("KonsumentTestURL", "https://folkeregisteret-api-konsument.sits.no")
                            .variable("ProdusentTestURL", "https://folkeregisteret-api-ekstern.sits.no")
                            .variable("ProduksjonURL", "https://folkeregisteret.api.skatteetaten.no")
                            .variable("nextSequence", "${cast.toLong(contentStream.lastOrInitialPosition(0)) + 1}")
            )
            .configure(security()
                            .identity(jwt("maskinporten",
                                            headerClaims()
                                                    .alg("RS256")
//                                    .x509CertChain("ssb-p12-test-certs"),
                                                    .x509CertChain("ssb-p12-certs"),
                                            claims()
//                                    .audience("https://ver2.maskinporten.no/")
                                                    .audience("https://maskinporten.no/")
//                                    .issuer("${testClientId}")
                                                    .issuer("${clientId}")
                                                    .claim("scope", "folkeregister:deling/svalbardregister folkeregister:deling/offentligmedhjemmel")
                                                    .timeToLiveInSeconds("${jwtGrantTimeToLiveInSeconds}")
                                    )
                            )
            )
            .function(post("maskinporten-jwt-grant")
//                    .url("https://ver2.maskinporten.no/token/v1/token")
                            .url("https://maskinporten.no/token/v1/token")
                            .data(bodyPublisher()
                                    .urlEncoded(jwtToken()
                                            .identityId("maskinporten")
                                            .bindTo("JWT_GRANT")
                                            .token("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${JWT_GRANT}")
                                    )
                            )
                            .validate(status().success(200))
                            .pipe(execute("loop")
                                    .inputVariable("accessToken", jqpath(".access_token"))
                            )
            )
            .function(paginate("loop")
                    .variable("fromSequence", "${nextSequence}")
                    .addPageContent("fromSequence")
                    .iterate(execute("event-list")
                            .requiredInput("accessToken")
                    )
                    .prefetchThreshold(1500)
                    .until(whenVariableIsNull("nextSequence"))
            )
            .function(get("event-list")
//                    .url("${KonsumentTestURL}/folkeregisteret/api/brsv/v1/hendelser/feed/?seq=${fromSequence}")
                            .url("${ProduksjonURL}/folkeregisteret/api/brsv/v1/hendelser/feed/?seq=${fromSequence}")
                            .header("Authorization", "Bearer ${accessToken}")
                            .header("accept", "application/xml")
                            .validate(status().success(200))
                            .pipe(sequence(xpath("/feed/entry"))
                                    .expected(xpath("/entry/content/lagretHendelse/sekvensnummer"))
                            )
                            .pipe(nextPage()
                                    .output("nextSequence",
                                            regex(xpath("/feed/link[@rel=\"next\"]/@href"), "(?<=[?&]seq=)[^&]*")
                                    )
                            )
                            .pipe(parallel(xpath("/feed/entry"))
                                    .variable("position", xpath("/entry/content/lagretHendelse/sekvensnummer"))
                                    .pipe(addContent("${position}", "entry"))
                                    .pipe(execute("person-document")
                                            .requiredInput("accessToken")
                                            .inputVariable("personId", xpath("/entry/content/lagretHendelse/hendelse/folkeregisteridentifikator"))
                                    )
                                    .pipe(publish("${position}"))
                            )
                            .returnVariables("nextSequence")
            )
            .function(get("person-document")
//                    .url("${KonsumentTestURL}/folkeregisteret/api/brsv/v1/personer/${personId}?part=historikk")
                            .url("${ProduksjonURL}/folkeregisteret/api/brsv/v1/personer/${personId}?part=historikk")
                            .header("Authorization", "Bearer ${accessToken}")
                            .header("accept", "application/xml")
                            .validate(status().success(200))
                            .pipe(addContent("${position}", "person"))
            );

    @Disabled
    @Test
    public void thatWorkerCollectFregData() {
        Path scanDirectory = CommonUtils.currentPath().resolve("certs");
        assertTrue(scanDirectory.toFile().exists());
        Worker.newBuilder()
                .configuration(new StoreBasedDynamicConfiguration.Builder()
                        .propertiesResource("application-override.properties") // gitignored
                        .values("content.stream.connector", "rawdata")
                        .values("rawdata.client.provider", "memory")
                        .values("data.collector.worker.threads", "20")
                        .values("local-temp-folder", "target/_tmp_avro_")
                        .values("avro-file.max.seconds", "86400")
                        .values("avro-file.max.bytes", "67108864")
                        .values("avro-file.sync.interval", "524288")
                        .values("gcs.bucket-name", "")
                        .values("gcs.listing.min-interval-seconds", "30")
                        .values("gcs.service-account.key-file", "")
                        .values("postgres.driver.host", "localhost")
                        .values("postgres.driver.port", "5432")
                        .values("postgres.driver.user", "rdc")
                        .values("postgres.driver.password", "rdc")
                        .values("postgres.driver.database", "rdc")
                        .values("rawdata.postgres.consumer.prefetch-size", "100")
                        .values("rawdata.postgres.consumer.prefetch-poll-interval-when-empty", "1000")
                        .environment("DC_")
                        .build()
                        .asMap())
//                .buildCertificateFactory(Paths.get("/Volumes/SSB BusinessSSL/certs"))
                .buildCertificateFactory(scanDirectory)
                //.stopAtNumberOfIterations(5)
                .printConfiguration()
                .specification(specificationBuilder)
                .build()
                .run();
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

//        Files.writeString(targetPath.resolve("specs").resolve("ske-freg-brsv-konsument-spec.json"), specificationBuilder.serialize());
        Files.writeString(targetPath.resolve("specs").resolve("ske-freg-brsv-prod-spec.json"), specificationBuilder.serialize());
    }
}
