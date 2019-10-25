package no.ssb.dc.samples.ske.sirius;

import no.ssb.config.StoreBasedDynamicConfiguration;
import no.ssb.dc.api.Specification;
import no.ssb.dc.api.node.builder.SpecificationBuilder;
import no.ssb.dc.api.util.CommonUtils;
import no.ssb.dc.core.executor.Worker;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static no.ssb.dc.api.Builders.addContent;
import static no.ssb.dc.api.Builders.context;
import static no.ssb.dc.api.Builders.eval;
import static no.ssb.dc.api.Builders.execute;
import static no.ssb.dc.api.Builders.get;
import static no.ssb.dc.api.Builders.nextPage;
import static no.ssb.dc.api.Builders.paginate;
import static no.ssb.dc.api.Builders.parallel;
import static no.ssb.dc.api.Builders.publish;
import static no.ssb.dc.api.Builders.security;
import static no.ssb.dc.api.Builders.sequence;
import static no.ssb.dc.api.Builders.status;
import static no.ssb.dc.api.Builders.whenVariableIsNull;
import static no.ssb.dc.api.Builders.xpath;

// https://skatteetaten.github.io/datasamarbeid-api-dokumentasjon/reference_skattemelding
public class SiriusWorkerTest {

    static final SpecificationBuilder specificationBuilder = Specification.start("SKE-SIRIUS", "Collect Sirius", "loop")
            .configure(context()
                    .topic("sirius-person-utkast")
                    .header("accept", "application/xml")
                    .variable("baseURL", "https://api-at.sits.no")
                    .variable("rettighetspakke", "ssb")
                    .variable("hentAntallMeldingerOmGangen", "100")
                    .variable("hendelse", "utkast")
                    .variable("nextSequence", "${cast.toLong(contentStream.lastOrInitialPosition(0)) + 1}")
            )
            .configure(security()
                    .sslBundleName("ske-test-certs")
            )
            .function(paginate("loop")
                    .variable("fromSequence", "${nextSequence}")
                    .addPageContent()
                    .iterate(execute("parts"))
                    .prefetchThreshold(150)
                    .until(whenVariableIsNull("nextSequence"))
            )
            .function(get("parts")
                    .url("${baseURL}/api/formueinntekt/skattemelding/${hendelse}/hendelser/?fraSekvensnummer=${fromSequence}&antall=${hentAntallMeldingerOmGangen}")
                    .validate(status().success(200).fail(400).fail(404).fail(500))
                    .pipe(sequence(xpath("/hendelser/hendelse"))
                            .expected(xpath("/hendelse/sekvensnummer"))
                    )
                    .pipe(nextPage()
                            .output("nextSequence",
                                    eval(xpath("/hendelser/hendelse[last()]/sekvensnummer"), "lastSequence", "${cast.toLong(lastSequence) + 1}")
                            )
                    )
                    .pipe(parallel(xpath("/hendelser/hendelse"))
                            .variable("position", xpath("/hendelse/sekvensnummer"))
                            .pipe(addContent("${position}", "entry"))
                            .pipe(execute("utkast-melding")
                                    .inputVariable("utkastIdentifikator", xpath("/hendelse/identifikator"))
                                    .inputVariable("year", xpath("/hendelse/gjelderPeriode"))
                            )
                            .pipe(publish("${position}"))
                    )
                    .returnVariables("nextSequence")
            )
            .function(get("utkast-melding")
                    .url("${baseURL}/api/formueinntekt/skattemelding/${hendelse}/${rettighetspakke}/${year}/${utkastIdentifikator}")
                    .validate(status().success(200).fail(400).fail(404).fail(500))
                    .pipe(addContent("${position}", "skattemelding"))
            );

    @Ignore
    @Test
    public void thatWorkerCollectSiriusData() throws InterruptedException {
        Worker.newBuilder()
                .configuration(new StoreBasedDynamicConfiguration.Builder()
                        .values("content.stream.connector", "rawdata")
                        .values("rawdata.client.provider", "memory")
                        .values("data.collector.worker.threads", "40")
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
                .buildCertificateFactory(CommonUtils.currentPath())
                //.stopAtNumberOfIterations(5)
                .printConfiguration()
                //.printExecutionPlan()
                .specification(specificationBuilder)
                .build()
                .run();
    }

    @Ignore
    @Test
    public void writeTargetConsumerSpec() throws IOException {
        Path currentPath = CommonUtils.currentPath();
        Path targetPath = currentPath.resolve("data-collection-consumer-specifications");

        boolean targetProjectExists = targetPath.toFile().exists();
        if (!targetProjectExists) {
            throw new RuntimeException(String.format("Couldn't locate '%s' under currentPath: %s%n", targetPath.toFile().getName(), currentPath.toAbsolutePath().toString()));
        }

        Files.writeString(targetPath.resolve("specs").resolve("ske-sirius-person-utkast-spec.json"), specificationBuilder.serialize());
        Files.writeString(targetPath.resolve("specs").resolve("ske-sirius-person-fastsatt-spec.json"), specificationBuilder.serialize().replace("utkast", "fastsatt"));
    }
}
