package io.descoped.dc.samples.toll.tvinn;

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
import java.nio.file.Paths;

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

public class TvinnTestWorkerTest {

    static final SpecificationBuilder specificationBuilder = Specification.start("TOLL-TVINN-TEST", "Collect Tvinn Test", "maskinporten-jwt-grant")
            .configure(context()
                            .topic("tvinn-test")
                            .variable("TestURL", "https://api-test.toll.no")
//                    .variable("ProduksjonURL", "https://api.toll.no")
                            .variable("clientId", "30ccfd37-84dd-4448-9c48-23d58432a6b1")
                            .variable("jwtGrantTimeToLiveInSeconds", "30")
                            .variable("nextMarker", "${contentStream.lastOrInitialPosition(\"last\")}")
            )
            .configure(security()
                            .identity(jwt("maskinporten",
                                            headerClaims()
                                                    .alg("RS256")
                                                    .x509CertChain("ssb-test-certs"),
                                            claims()
                                                    .audience("https://ver2.maskinporten.no/")
                                                    .issuer("30ccfd37-84dd-4448-9c48-23d58432a6b1")
//                                            .claim("resource", "https://api-test.toll.no/api/declaration/declaration-clearance-feed/atom")
                                                    .claim("scope", "toll:declaration/clearance/feed.read")
                                                    .timeToLiveInSeconds("30")
                                    )
                            )
            )
            .function(post("maskinporten-jwt-grant")
                            .url("https://ver2.maskinporten.no/token/v1/token")
//                            .url("https://maskinporten.no/token/v1/token")
                            .data(bodyPublisher()
                                    .urlEncoded(jwtToken()
                                            .identityId("maskinporten")
                                            .bindTo("JWT_GRANT")
                                            .token("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${JWT_GRANT}")
                                    )
                            )
                            .validate(status().success(200))
                            .pipe(execute("loop")
//                            .pipe(execute("loop")
                                            .inputVariable("accessToken", jqpath(".access_token"))
                            )
            )
            .function(paginate("loop")
                    .variable("fromMarker", "${nextMarker}")
                    .addPageContent("fromMarker")
                    .iterate(execute("event-list").requiredInput("accessToken"))
                    .prefetchThreshold(150)
                    .until(whenVariableIsNull("nextMarker"))
            )
            .function(get("event-list")
//                    .url("${TestURL}/atomfeed/toll/deklarasjon-ekstern-feed/?marker=${fromMarker}&limit=25&direction=forward")
                            .url("${TestURL}/api/declaration/declaration-clearance-feed/atom?marker=${fromMarker}&limit=25&direction=forward")
                            .header("Content-Type", "application/xml")
                            .header("Authorization", "Bearer ${accessToken}")
                            .validate(status().success(200))
                            .pipe(sequence(xpath("/feed/entry"))
                                    .expected(xpath("/entry/id"))
                            )
                            .pipe(nextPage()
                                    .output("nextMarker",
                                            regex(xpath("/feed/link[@rel=\"previous\"]/@href"), "(?<=[?&]marker=)[^&]*")
                                    )
                            )
                            .pipe(parallel(xpath("/feed/entry"))
                                    .variable("position", xpath("/entry/id"))
                                    .pipe(addContent("${position}", "entry"))
                                    .pipe(publish("${position}"))
                            )
                            .returnVariables("nextMarker")
            );

    @Disabled
    @Test
    public void testCollectTvinn() {
        String serialized = specificationBuilder.serialize();
        SpecificationBuilder spec = Specification.deserialize(serialized);
        Worker.newBuilder()
                .configuration(new StoreBasedDynamicConfiguration.Builder()
                        .values("content.stream.connector", "rawdata")
                        .values("rawdata.client.provider", "memory")
                        .values("data.collector.worker.threads", "25")
                        .values("postgres.driver.host", "localhost")
                        .values("postgres.driver.port", "5432")
                        .values("postgres.driver.user", "rdc")
                        .values("postgres.driver.password", "rdc")
                        .values("postgres.driver.database", "rdc")
                        .values("rawdata.postgres.consumer.prefetch-size", "100")
                        .values("rawdata.postgres.consumer.prefetch-poll-interval-when-empty", "1000")
                        .build()
                        .asMap())
//                .buildCertificateFactory(CommonUtils.currentPath())
                .buildCertificateFactory(Paths.get("/Volumes/SSB BusinessSSL/certs"))
                //.stopAtNumberOfIterations(5)
                .printConfiguration()
                .specification(spec)
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

        Files.writeString(targetPath.resolve("specs").resolve("toll-tvinn-test-spec.json"), specificationBuilder.serialize());
    }
}
