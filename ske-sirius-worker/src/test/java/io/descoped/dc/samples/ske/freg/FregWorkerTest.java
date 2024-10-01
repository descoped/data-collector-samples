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
import java.nio.file.Paths;

import static io.descoped.dc.api.Builders.addContent;
import static io.descoped.dc.api.Builders.context;
import static io.descoped.dc.api.Builders.execute;
import static io.descoped.dc.api.Builders.get;
import static io.descoped.dc.api.Builders.nextPage;
import static io.descoped.dc.api.Builders.paginate;
import static io.descoped.dc.api.Builders.parallel;
import static io.descoped.dc.api.Builders.publish;
import static io.descoped.dc.api.Builders.regex;
import static io.descoped.dc.api.Builders.security;
import static io.descoped.dc.api.Builders.sequence;
import static io.descoped.dc.api.Builders.status;
import static io.descoped.dc.api.Builders.whenVariableIsNull;
import static io.descoped.dc.api.Builders.xpath;

// https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/oppslag/
public class FregWorkerTest {

    static final SpecificationBuilder specificationBuilder = Specification.start("SKE-FREG-PLAYGROUND", "Collect FREG", "loop")
            .configure(context()
                    .topic("freg-playground")
                    .header("accept", "application/xml")
                    .variable("ProdusentTestURL", "https://folkeregisteret-api-ekstern.sits.no")
                    .variable("KonsumentTestURL", "https://folkeregisteret-api-konsument.sits.no")
                    .variable("ProduksjonURL", "https://folkeregisteret.api.skatteetaten.no")
                    .variable("PlaygroundURL", "https://folkeregisteret-api-konsument-playground.sits.no")
                    .variable("nextSequence", "${cast.toLong(contentStream.lastOrInitialPosition(0)) + 1}")
            )
            .configure(security()
                            .sslBundleName("ske-prod-certs")
//                    .sslBundleName("ske-test-certs")
            )
            .function(paginate("loop")
                    .variable("fromSequence", "${nextSequence}")
                    .addPageContent("fromSequence")
                    .iterate(execute("event-list"))
                    .prefetchThreshold(1500)
                    .until(whenVariableIsNull("nextSequence"))
            )
            .function(get("event-list")
                    .url("${ProduksjonURL}/folkeregisteret/offentlig-med-hjemmel/api/v1/hendelser/feed/?seq=${fromSequence}")
                    .validate(status().success(200).fail(400).fail(404).fail(500))
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
                            .pipe(execute("event-document")
                                    .inputVariable("eventId", xpath("/entry/content/lagretHendelse/hendelse/hendelsesdokument"))
                            )
                            .pipe(execute("person-document")
                                    .inputVariable("personId", xpath("/entry/content/lagretHendelse/hendelse/folkeregisteridentifikator"))
                            )
                            .pipe(publish("${position}"))
                    )
                    .returnVariables("nextSequence")
            )
            .function(get("event-document")
                            .url("${ProduksjonURL}/folkeregisteret/offentlig-med-hjemmel/api/v1/hendelser/${eventId}")
                            .validate(status().success(200).fail(400).fail(404).fail(500))
                            .pipe(addContent("${position}", "event"))
//                    .pipe(console())
            )
            .function(get("person-document")
                            .url("${ProduksjonURL}/folkeregisteret/offentlig-med-hjemmel/api/v1/personer/${personId}?part=person-basis&part=identitetsgrunnlag-utvidet&part=relasjon-utvidet&part=utlendingsmyndighetenesIdentifikasjonsnummer&part=innflytting&part=utflytting&part=foedselINorge&part=opphold&part=forholdTilSametingetsValgmanntall")
                            .validate(status().success(200).fail(400).fail(404).fail(500))
                            .pipe(addContent("${position}", "person"))
//                    .pipe(console())
            );

    @Disabled
    @Test
    public void thatWorkerCollectFregData() {
        Worker.newBuilder()
                .configuration(new StoreBasedDynamicConfiguration.Builder()
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
                .buildCertificateFactory(Paths.get("/Volumes/SSB BusinessSSL/certs"))
//                .buildCertificateFactory(CommonUtils.currentPath())
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

        Files.writeString(targetPath.resolve("specs").resolve("ske-freg-playground-spec.json"), specificationBuilder.serialize());
    }
}
