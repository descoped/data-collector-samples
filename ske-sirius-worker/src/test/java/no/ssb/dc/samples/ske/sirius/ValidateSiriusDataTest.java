package no.ssb.dc.samples.ske.sirius;

import de.huxhorn.sulky.ulid.ULID;
import no.ssb.dc.api.Builders;
import no.ssb.dc.api.ulid.ULIDGenerator;
import no.ssb.dc.api.util.CommonUtils;
import no.ssb.dc.core.handler.Queries;
import no.ssb.rawdata.api.RawdataClient;
import no.ssb.rawdata.api.RawdataClientInitializer;
import no.ssb.rawdata.api.RawdataConsumer;
import no.ssb.rawdata.api.RawdataMessage;
import no.ssb.rawdata.api.RawdataProducer;
import no.ssb.rawdata.provider.postgres.FileAndClasspathReaderUtils;
import no.ssb.service.provider.api.ProviderConfigurator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pre-requisite before running this test case is SimpleSiriusFeedTest.getHendelseListe, which will export and pack
 * a snapshot of Skattemelding API to disk.
 * <p>
 * The `target/sirius` data will produced and published to RawdataClient and consumed for building a database.
 * <p>
 * Execute the test method ValidateSiriusDataTest.validate() in order to verify SKE Sirius Data Stream.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ValidateSiriusDataTest {

    static final Logger LOG = LoggerFactory.getLogger(ValidateSiriusDataTest.class);

    // static final String TARGET = "utkast";
    static final String TARGET = "fastsatt";
    static final String TOPIC = "sirius-person-" + TARGET;
    static final String JDBC_URL = "jdbc:h2:file:/tmp/siriusDb";

    @Disabled
    @Order(2)
    @Test
    void validate() throws Exception {
        Path targetPath = CommonUtils.currentPath().resolve("target").resolve("sirius").resolve(TARGET);
        if (!targetPath.toFile().exists()) {
            LOG.trace("Error resolving targetPath: {}", targetPath);
            return;
        }

        /*
         * Create rawdata client
         */
        RawdataClient rawdataClient = ProviderConfigurator.configure(Map.of(), "memory", RawdataClientInitializer.class);

        /*
         * Cache Hendelseliste
         */
        Map<String, Hendelse> hendelseListe = loadHendelseListe(targetPath);

        /*
         * Publish rawdata stream
         */
        publishToRawdataContentStream(targetPath, rawdataClient, hendelseListe);

        TaxReturnRepository repository = new TaxReturnRepository();

        /*
         * Consume rawdata stream, apply rules and persist to database
         */
        persistRawdataStream(rawdataClient, hendelseListe, repository);

        /*
         * Read unique TaxReturnStream
         */
        readTaxReturnStream(repository);

        repository.close();
    }

    private Map<String, Hendelse> loadHendelseListe(Path targetPath) throws IOException {
        Map<String, Hendelse> hendelseListeMap = new LinkedHashMap<>();
        Files.walkFileTree(targetPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String filename = file.getFileName().toString();
                if (filename.contains("hendelse-liste")) {
                    byte[] hendelseListeContent = Files.readAllBytes(file);
                    Optional<Element> documentElement = DOM.documentElement(hendelseListeContent);
                    List<Element> elements = documentElement.map(element -> element.getElementsByTagName("hendelse")).flatMap(DOM::allElements).get();
                    for (Element element : elements) {
                        Hendelse hendelse = new Hendelse(element, SimpleSiriusFeedTest.XML.serialize(element));
                        hendelseListeMap.put(hendelse.sekvensnummer, hendelse);
                    }
                }
                return super.visitFile(file, attrs);
            }
        });
        return hendelseListeMap;
    }

    @Deprecated
    private void publishToRawdataContentStream(Path targetPath, RawdataClient rawdataClient, Map<String, Hendelse> hendelseListe) throws IOException {
        RawdataProducer producer = rawdataClient.producer(TOPIC);
        List<String> positions = new ArrayList<>();
        Files.walkFileTree(targetPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String filename = file.getFileName().toString();
                if (filename.contains("skatte-melding") && filename.endsWith(".xml")) {
                    String position = String.valueOf(Integer.parseInt(file.getFileName().toString().split("-")[0]));

                    String manifestFilenamePath = file.toString().replace("skatte-melding", "skatte-melding-manifest").replace(".xml", ".json");
                    byte[] manifestContent = Files.readAllBytes(Paths.get(manifestFilenamePath));
                    byte[] skattemeldingContent = Files.readAllBytes(file);

                    RawdataMessage.Builder builder = RawdataMessage.builder();
                    builder.position(position);
                    builder.put("manifest.json", manifestContent);
                    builder.put("hendelse", hendelseListe.get(position).content);
                    builder.put("skattemelding", skattemeldingContent);
                    producer.publish(builder.build());

                    positions.add(position);
                }
                return super.visitFile(file, attrs);
            }
        });
        String[] array = positions.stream().map(Integer::parseInt).sorted().map(String::valueOf).toArray(String[]::new);
//        producer.publish(array);
    }

    private void persistRawdataStream(RawdataClient rawdataClient, Map<String, Hendelse> hendelseListe, TaxReturnRepository repository) throws Exception {
        try (RawdataConsumer consumer = rawdataClient.consumer(TOPIC)) {
            RawdataMessage rawdataMessage;
            while ((rawdataMessage = consumer.receive(1, TimeUnit.SECONDS)) != null) {
                byte[] manifestContent = rawdataMessage.get("manifest.json");
                byte[] content = rawdataMessage.get("skattemelding");

                String manifestJson = new String(manifestContent);

                String position = Queries.from(Builders.jqpath(".metadata.position").build()).evaluateStringLiteral(manifestJson);
                String url = Queries.from(Builders.jqpath(".metadata.url").build()).evaluateStringLiteral(manifestJson);
                String statusCode = Queries.from(Builders.jqpath(".\"http-info\".statusCode").build()).evaluateStringLiteral(manifestJson);

                Hendelse hendelse = hendelseListe.get(position);
                Manifest manifest = new Manifest(position, url, Integer.parseInt(statusCode));
                Skattemelding skattemelding = new Skattemelding(content);

                TaxReturn taxReturn = new TaxReturn.Builder()
                        .position(hendelse.sekvensnummer)
                        .incomeYear(Integer.parseInt(hendelse.gjelderPeriode))
                        .registrationDate(hendelse.registreringstidspunkt)
                        .eventType(hendelse.hendelsetype)
                        .feedIdentifier(hendelse.identifikator)
                        .identifier(skattemelding.identifikator)
                        .shielded(skattemelding.skjermet)
                        .statusCode(manifest.statusCode)
                        .errorCode(skattemelding.feilKode)
                        .build();
                repository.createTaxReturn(taxReturn);

                // Track Identitetsendring
                IdentityHistory identityHistory = repository.getIdentity(hendelse.identifikator, Integer.parseInt(hendelse.gjelderPeriode));
                //LOG.trace("[{}] Current last identity: {}", hendelse.sekvensnummer, ofNullable(identityHistory).map(ih -> ih.identity).orElse(null));

                switch (EventType.parse(hendelse.hendelsetype)) {
                    case NEW:
                        if (identityHistory != null) {
                            break;
                        }
                        if (!hendelse.identifikator.equals(skattemelding.identifikator)) {
                            LOG.warn("[{}] The Hendelse.identifikator {} with hendelsetype {} DOES NOT match Skattemelding.identifikator: {}",
                                    hendelse.sekvensnummer, hendelse.identifikator, hendelse.hendelsetype, skattemelding.identifikator);
                        }
                        IdentityHistory newIdentity = new IdentityHistory.Builder()
                                .fid(UUID.randomUUID().toString())
                                .identity(hendelse.identifikator)
                                .incomeYear(Integer.parseInt(hendelse.gjelderPeriode))
                                .registrationDate(hendelse.registreringstidspunkt)
                                .eventType(hendelse.hendelsetype)
                                .build();
                        repository.createIdentity(newIdentity);
                        break;

                    case IDENTITY_CHANGE:
                        if (identityHistory == null) {
                            throw new RuntimeException(String.format("[%s] Identity change for %s failed because NO PARENT identity exists!",
                                    hendelse.sekvensnummer, hendelse.identifikator));
                        }
                        IdentityHistory identityChange = new IdentityHistory.Builder()
                                .fid(identityHistory.fid) // map to previous identity.fid
                                .identity(skattemelding.identifikator) // change Identity to Skattemelding.identifikator
                                .incomeYear(Integer.parseInt(hendelse.gjelderPeriode))
                                .registrationDate(hendelse.registreringstidspunkt)
                                .eventType(hendelse.hendelsetype)
                                .build();
                        repository.createIdentity(identityChange);
                        LOG.error("[{}] IdentityChange: {} -> {}", hendelse.sekvensnummer, hendelse.identifikator, skattemelding.identifikator);
                        assertEquals(skattemelding.identifikator, ofNullable(repository.getLastIdentity(hendelse.identifikator, Integer.parseInt(hendelse.gjelderPeriode))).map(tracked -> tracked.identity).orElse(null));
                        assertEquals(skattemelding.identifikator, ofNullable(repository.getLastIdentity(skattemelding.identifikator, Integer.parseInt(hendelse.gjelderPeriode))).map(tracked -> tracked.identity).orElse(null));
                        assertEquals(skattemelding.identifikator, ofNullable(repository.getNextIdentity(hendelse.identifikator, Integer.parseInt(hendelse.gjelderPeriode))).map(tracked -> tracked.identity).orElse(null));
                        break;

                    case DELETED:
                        break;
                }

                if (false) {
                    logRawdataMessageEvent(position, hendelse, manifest, skattemelding);
                }

                if (taxReturn.shielded || taxReturn.isIdentityChanged() || taxReturn.isPersonDeleted()) {
                    System.err.println(String.format("%s", taxReturn));
                } else {
                    System.out.printf("%s%n", taxReturn);
                }
            }
        }
    }

    private void readTaxReturnStream(TaxReturnRepository repository) {
        // TODO
    }

    private void logRawdataMessageEvent(String position, Hendelse hendelse, Manifest manifest, Skattemelding skattemelding) {
        /*
         * any deviance between: hendelse.registreringstidspunkt AND skattemelding.registreringstidspunkt
         */
        if (!skattemelding.skjermet && !hendelse.registreringstidspunkt.equals(skattemelding.registreringstidspunkt)) {
            LOG.error("position: {} hendelse-timestamp: {} NOT EQUAL to skattemelding-timstamp: {}", position, hendelse.registreringstidspunkt, skattemelding.registreringstidspunkt);
            LOG.error("Exiting!");
            System.exit(-1);
        }

        /*
         * is skattemelding skjermet
         */
        if (skattemelding.skjermet) {
            LOG.error("isSkjermet: {}\n| {}\n\\-| {}\n  \\--> {}", skattemelding.skjermet, hendelse, manifest, skattemelding);
        } else {
            LOG.info("isSkjermet: {}\n| {}\n\\-| {}\n  \\--> {}", skattemelding.skjermet, hendelse, manifest, skattemelding);
        }

        /*
         * any non 200 status codes
         */
        if (manifest.statusCode != 200) {
            throw new RuntimeException(String.format("Position: %s returned http error: %s", manifest.position, manifest.statusCode));
        }
    }

    @Order(1)
    @Test
    void testDbRepository() {
        TaxReturnRepository repository = new TaxReturnRepository();

        TaxReturn taxReturn = new TaxReturn.Builder()
                .position("1")
                .incomeYear(2018)
                .registrationDate(new Date())
                .eventType("NY")
                .feedIdentifier("123")
                .identifier("123")
                .shielded(false)
                .statusCode(200)
                .errorCode(null)
                .build();
        assertTrue(repository.createTaxReturn(taxReturn), "Transaction failed");

        {
            IdentityHistory identityHistory = new IdentityHistory.Builder()
                    .fid("001")
                    .identity("A")
                    .incomeYear(2018)
                    .registrationDate(new Date())
                    .eventType("NY")
                    .build();
            assertTrue(repository.createIdentity(identityHistory), "Transaction failed");

            IdentityHistory identityHistory2 = new IdentityHistory.Builder()
                    .fid("001")
                    .identity("B")
                    .incomeYear(2018)
                    .registrationDate(new Date())
                    .eventType("INDENTITETSENDRING")
                    .build();
            assertTrue(repository.createIdentity(identityHistory2), "Transaction failed");

            /*
             * Get last valid identity for a previous identity. if an Identity has been delete it should return null
             */

            assertEquals("A", ofNullable(repository.getIdentity("A", 2018)).map(identity -> identity.identity).orElse(null));
            assertEquals("B", ofNullable(repository.getIdentity("B", 2018)).map(identity -> identity.identity).orElse(null));
            assertNull(repository.getIdentity("C", 2018));

            assertEquals("A", ofNullable(repository.getFirstIdentity("A", 2018)).map(identity -> identity.identity).orElse(null));
            assertEquals("A", ofNullable(repository.getFirstIdentity("B", 2018)).map(identity -> identity.identity).orElse(null));

            assertEquals("B", ofNullable(repository.getLastIdentity("A", 2018)).map(identity -> identity.identity).orElse(null));
            assertEquals("B", ofNullable(repository.getLastIdentity("B", 2018)).map(identity -> identity.identity).orElse(null));

            assertEquals("B", ofNullable(repository.getNextIdentity("A", 2018)).map(identity -> identity.identity).orElse(null));
            assertEquals("B", ofNullable(repository.getNextIdentity("B", 2018)).map(identity -> identity.identity).orElse(null));
        }

        TaxReturnStream taxReturnStream = new TaxReturnStream.Builder()
                .position("1")
                .incomeYear(2018)
                .registrationDate(new Date())
                .identifier("123")
                .shielded(false)
                .build();
        assertTrue(repository.createTaxReturnStream(taxReturnStream), "Transaction failed");

    }

    // https://skatteetaten.github.io/datasamarbeid-api-dokumentasjon/download/kodelister/deling/hendelsetyper.xml
    enum EventType {
        NEW("NY"),
        IDENTITY_CHANGE("INDENTITETSENDRING"),
        DELETED("SLETTET");

        final String value;

        EventType(String value) {
            this.value = value;
        }

        static EventType parse(String eventType) {
            return List.of(EventType.values()).stream().filter(type -> type.value.equalsIgnoreCase(eventType)).findFirst().orElseThrow();
        }
    }

    static class DOM {

        static Optional<Element> documentElement(byte[] content) {
            return of(SimpleSiriusFeedTest.XML.deserialize(content).getDocumentElement());
        }

        static Optional<Element> firstElement(NodeList nodeList) {
            return nodeList.getLength() > 0 ? of((Element) nodeList.item(0)) : Optional.empty();
        }

        static Optional<String> firstElementAsValue(NodeList nodeList) {
            return nodeList.getLength() > 0 ? of(nodeList.item(0).getTextContent()) : Optional.empty();
        }

        static Optional<Date> firstElementFromUTCAsDate(NodeList nodeList) {
            return firstElement(nodeList)
                    .flatMap(element -> of(element.getTextContent()))
                    .flatMap(value -> of(DateTimeFormatter.ISO_DATE_TIME.parse(value))
                            .map(accessor -> Date.from(Instant.from(accessor))));
        }

        public static Optional<List<Element>> allElements(NodeList nodeList) {
            List<Element> elements = new ArrayList<>();
            for (int i = 0; i < nodeList.getLength(); i++) {
                elements.add((Element) nodeList.item(i));
            }
            return of(elements);
        }
    }

    static class Hendelse {
        final String sekvensnummer;
        final String identifikator;
        final String gjelderPeriode;
        final Date registreringstidspunkt;
        final String hendelsetype;
        final byte[] content;

        public Hendelse(Element element, byte[] content) {
            this.sekvensnummer = of(element).map(child -> child.getElementsByTagName("sekvensnummer")).flatMap(DOM::firstElementAsValue).orElseThrow();
            this.identifikator = of(element).map(child -> child.getElementsByTagName("identifikator")).flatMap(DOM::firstElementAsValue).orElseThrow();
            this.gjelderPeriode = of(element).map(child -> child.getElementsByTagName("gjelderPeriode")).flatMap(DOM::firstElementAsValue).orElseThrow();
            this.registreringstidspunkt = of(element).map(child -> child.getElementsByTagName("registreringstidspunkt")).flatMap(DOM::firstElementFromUTCAsDate).orElseThrow();
            this.hendelsetype = of(element).map(child -> child.getElementsByTagName("hendelsetype")).flatMap(DOM::firstElementAsValue).orElseThrow();
            this.content = content;
        }

        static String formatDateTime(Date registreringstidspunkt) {
            if (registreringstidspunkt == null) {
                return null;
            }
            return registreringstidspunkt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().toString();
        }

        @Override
        public String toString() {
            return "Hendelse{" +
                    "sekvensnummer='" + sekvensnummer + '\'' +
                    ", identifikator='" + identifikator + '\'' +
                    ", gjelderPeriode='" + gjelderPeriode + '\'' +
                    ", registreringstidspunkt=" + formatDateTime(registreringstidspunkt) +
                    ", hendelsetype='" + hendelsetype + '\'' +
                    '}';
        }
    }

    static class Manifest {
        private final String position;
        private final String url;
        private final int statusCode;

        public Manifest(String position, String url, int statusCode) {
            this.position = position;
            this.url = url;
            this.statusCode = statusCode;
        }

        @Override
        public String toString() {
            return "Manifest{" +
                    "position='" + position + '\'' +
                    ", url='" + url + '\'' +
                    ", statusCode=" + statusCode +
                    '}';
        }
    }

    static class Skattemelding {
        final String identifikator;
        final boolean skjermet;
        final Date registreringstidspunkt;
        final String feilKode;

        public Skattemelding(byte[] content) {
            Optional<Element> root = DOM.documentElement(content);
            this.identifikator = root.map(element -> element.getElementsByTagName("personidentifikator")).flatMap(DOM::firstElementAsValue).orElseThrow();
            this.skjermet = root.map(element -> element.getElementsByTagName("skjermet")).flatMap(DOM::firstElementAsValue).map(Boolean::parseBoolean).orElse(false);
            this.registreringstidspunkt = root.map(element -> element.getElementsByTagName("registreringstidspunkt")).flatMap(DOM::firstElementFromUTCAsDate).orElse(null);
            this.feilKode = root.map(element -> element.getElementsByTagName("feil")).flatMap(DOM::firstElementAsValue).orElse(null);
        }

        @Override
        public String toString() {
            return "Skattemelding{" +
                    "identifikator='" + identifikator + '\'' +
                    ", skjermet=" + skjermet +
                    ", registreringstidspunkt=" + Hendelse.formatDateTime(registreringstidspunkt) +
                    ", feilMelding='" + feilKode + '\'' +
                    '}';
        }
    }

    static class TaxReturn {
        final UUID ulid;                // unique sortable id
        final String position;          // hendelseliste sekvensnummer
        final int incomeYear;           // hendelseliste income year
        final Date registrationDate;    // hendelseliste juridical registration date
        final EventType eventType;         // hendelseliste hendelsetype
        final String feedIdentifier;    // hendelseliste  identifier
        final String identifier;        // skattemelding identifier
        final boolean shielded;         // skattemelding skjermet
        final int statusCode;           // manifest status code
        final String errorCode;         // skattemedling response containing feil kode

        public TaxReturn(UUID ulid, String position, int incomeYear, Date registrationDate, EventType eventType, String feedIdentifier, String identifier, boolean shielded, int statusCode, String errorCode) {
            Objects.requireNonNull(ulid);
            Objects.requireNonNull(position);
            if (incomeYear == 0) throw new RuntimeException("Missing IncomeYear!");
            Objects.requireNonNull(registrationDate);
            Objects.requireNonNull(feedIdentifier);
            this.ulid = ulid;
            this.position = position;
            this.incomeYear = incomeYear;
            this.registrationDate = registrationDate;
            this.eventType = eventType;
            this.feedIdentifier = feedIdentifier;
            this.identifier = identifier;
            this.shielded = shielded;
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TaxReturn taxReturn = (TaxReturn) o;
            return incomeYear == taxReturn.incomeYear &&
                    shielded == taxReturn.shielded &&
                    statusCode == taxReturn.statusCode &&
                    Objects.equals(ulid, taxReturn.ulid) &&
                    Objects.equals(position, taxReturn.position) &&
                    Objects.equals(registrationDate, taxReturn.registrationDate) &&
                    Objects.equals(eventType, taxReturn.eventType) &&
                    Objects.equals(feedIdentifier, taxReturn.feedIdentifier) &&
                    Objects.equals(identifier, taxReturn.identifier) &&
                    Objects.equals(errorCode, taxReturn.errorCode);
        }

        boolean isIdentityChanged() {
            return !feedIdentifier.equals(identifier);
        }

        boolean isPersonDeleted() {
            return statusCode == 404 && List.of("SM-001", "SM-002").stream().anyMatch(code -> code.equals(errorCode));
        }

        @Override
        public int hashCode() {
            return Objects.hash(ulid, position, incomeYear, registrationDate, eventType, feedIdentifier, identifier, shielded, statusCode, errorCode);
        }

        @Override
        public String toString() {
            return "TaxReturn{" +
                    "ulid=" + ulid +
                    ", position='" + position + '\'' +
                    ", incomeYear=" + incomeYear +
                    ", registrationDate=" + Hendelse.formatDateTime(registrationDate) +
                    ", eventType='" + eventType + '\'' +
                    ", feedIdentifier='" + feedIdentifier + '\'' +
                    ", identifier='" + identifier + '\'' +
                    ", identityChanged='" + isIdentityChanged() + '\'' +
                    ", personDeleted='" + isPersonDeleted() + '\'' +
                    ", shielded=" + shielded +
                    ", statusCode=" + statusCode +
                    ", errorCode='" + errorCode + '\'' +
                    '}';
        }

        static class Builder {
            private String position;
            private int incomeYear;
            private Date registrationDate;
            private EventType eventType;
            private String feedIdentifier;
            private String identifier;
            private boolean shielded;
            private int statusCode;
            private String errorCode;

            static void tick() {
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            public Builder position(String position) {
                this.position = position;
                return this;
            }

            public Builder incomeYear(int incomeYear) {
                this.incomeYear = incomeYear;
                return this;
            }

            public Builder registrationDate(Date registrationDate) {
                this.registrationDate = registrationDate;
                return this;
            }

            public Builder eventType(String eventType) {
                this.eventType = EventType.parse(eventType);
                return this;
            }

            public Builder feedIdentifier(String feedIdentifier) {
                this.feedIdentifier = feedIdentifier;
                return this;
            }

            public Builder identifier(String identifier) {
                this.identifier = identifier;
                return this;
            }

            public Builder shielded(boolean shielded) {
                this.shielded = shielded;
                return this;
            }

            public Builder statusCode(int statusCode) {
                this.statusCode = statusCode;
                return this;
            }

            public Builder errorCode(String errorCode) {
                this.errorCode = errorCode;
                return this;
            }

            public TaxReturn build() {
                ULID.Value ulid = ULIDGenerator.generate();
                UUID uuid = ULIDGenerator.toUUID(ulid);
                tick();
                return new TaxReturn(uuid, position, incomeYear, registrationDate, eventType, feedIdentifier, identifier, shielded, statusCode, errorCode);
            }
        }
    }

    static class IdentityHistory {
        final String fid;
        final String identity;
        final int incomeYear;
        final Date registrationDate;
        final EventType eventType;

        public IdentityHistory(String fid, String identity, int incomeYear, Date registrationDate, EventType eventType) {
            Objects.requireNonNull(fid);
            Objects.requireNonNull(identity);
            if (incomeYear == 0) throw new RuntimeException("Missing IncomeYear!");
            Objects.requireNonNull(incomeYear);
            Objects.requireNonNull(registrationDate);
            Objects.requireNonNull(eventType);
            this.fid = fid;
            this.identity = identity;
            this.incomeYear = incomeYear;
            this.registrationDate = registrationDate;
            this.eventType = eventType;
        }

        @Override
        public String toString() {
            return "IdentityHistory{" +
                    "fid='" + fid + '\'' +
                    ", identity='" + identity + '\'' +
                    ", incomeYear='" + incomeYear + '\'' +
                    ", registrationDate=" + Hendelse.formatDateTime(registrationDate) +
                    ", eventType=" + eventType +
                    '}';
        }

        static class Builder {
            private String fid;
            private String identity;
            private int incomeYear;
            private Date registrationDate;
            private EventType eventType;

            public Builder fid(String fid) {
                this.fid = fid;
                return this;
            }

            public Builder identity(String identity) {
                this.identity = identity;
                return this;
            }

            public Builder incomeYear(int incomeYear) {
                this.incomeYear = incomeYear;
                return this;
            }

            public Builder registrationDate(Date registrationDate) {
                this.registrationDate = registrationDate;
                return this;
            }

            public Builder eventType(String eventType) {
                this.eventType = EventType.parse(eventType);
                return this;
            }

            public Builder eventType(EventType eventType) {
                this.eventType = eventType;
                return this;
            }

            public IdentityHistory build() {
                return new IdentityHistory(fid, identity, incomeYear, registrationDate, eventType);
            }
        }
    }

    static class TaxReturnStream {
        final UUID ulid;                // unique sortable id
        final String position;
        final String identifier;
        final int incomeYear;
        final Date registrationDate;
        final boolean shielded;

        public TaxReturnStream(UUID ulid, String position, String identifier, int incomeYear, Date registrationDate, boolean shielded) {
            Objects.requireNonNull(ulid);
            Objects.requireNonNull(position);
            if (incomeYear == 0) throw new RuntimeException("Missing IncomeYear!");
            Objects.requireNonNull(registrationDate);
            this.ulid = ulid;
            this.position = position;
            this.identifier = identifier;
            this.incomeYear = incomeYear;
            this.registrationDate = registrationDate;
            this.shielded = shielded;
        }

        static class Builder {
            private String position;
            private int incomeYear;
            private Date registrationDate;
            private String identifier;
            private boolean shielded;

            public Builder position(String position) {
                this.position = position;
                return this;
            }

            public Builder identifier(String identifier) {
                this.identifier = identifier;
                return this;
            }

            public Builder incomeYear(int incomeYear) {
                this.incomeYear = incomeYear;
                return this;
            }

            public Builder registrationDate(Date registrationDate) {
                this.registrationDate = registrationDate;
                return this;
            }

            public Builder shielded(boolean shielded) {
                this.shielded = shielded;
                return this;
            }

            public TaxReturnStream build() {
                ULID.Value ulid = ULIDGenerator.generate();
                UUID uuid = ULIDGenerator.toUUID(ulid);
                TaxReturn.Builder.tick();
                return new TaxReturnStream(uuid, position, identifier, incomeYear, registrationDate, shielded);
            }
        }
    }

    static class TaxReturnRepository implements AutoCloseable {

        // TODO add guard so only one tx can occurr (lock)
        private final Connection conn;

        public TaxReturnRepository() {
            conn = init();
        }

        boolean createTaxReturn(TaxReturn taxReturn) {
            return doTransaction(() -> {
                String sql = FileAndClasspathReaderUtils.readFileOrClasspathResource("no/ssb/dc/samples/ske/sirius/create-tax-return.sql");
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, taxReturn.ulid);
                    ps.setString(2, taxReturn.position);
                    ps.setInt(3, taxReturn.incomeYear);
                    ps.setTimestamp(4, Timestamp.from(taxReturn.registrationDate.toInstant()));
                    ps.setString(5, taxReturn.eventType.value);
                    ps.setString(6, taxReturn.feedIdentifier);
                    ps.setString(7, taxReturn.identifier);
                    ps.setBoolean(8, taxReturn.shielded);
                    ps.setInt(9, taxReturn.statusCode);
                    ps.setString(10, taxReturn.errorCode);
                    return ps.executeUpdate() == 1;

                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        boolean createIdentity(IdentityHistory identityHistory) {
            return doTransaction(() -> {
                String sql = FileAndClasspathReaderUtils.readFileOrClasspathResource("no/ssb/dc/samples/ske/sirius/create-identity.sql");
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, identityHistory.fid);
                    ps.setString(2, identityHistory.identity);
                    ps.setInt(3, identityHistory.incomeYear);
                    ps.setTimestamp(4, Timestamp.from(identityHistory.registrationDate.toInstant()));
                    ps.setString(5, identityHistory.eventType.value);
                    return ps.executeUpdate() == 1;

                } catch (SQLException e) {
                    throw new RuntimeException(String.format("%s\n%s", identityHistory, CommonUtils.captureStackTrace(e)));
                }
            });
        }

        private IdentityHistory.Builder mapResultSetToIdentity(ResultSet rs) throws SQLException {
            IdentityHistory.Builder builder = new IdentityHistory.Builder();
            builder.fid(rs.getString(1));
            builder.identity(rs.getString(2));
            builder.incomeYear(rs.getInt(3));
            builder.registrationDate(new Date(rs.getTimestamp(4).getTime()));
            builder.eventType(rs.getString(5));
            return builder;
        }

        private IdentityHistory findIdentity(String identity, int incomeYear, String sqlResource) {
            AtomicReference<IdentityHistory> identityMap = new AtomicReference<>();
            doTransaction(() -> {
                String sql = FileAndClasspathReaderUtils.readFileOrClasspathResource(sqlResource);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, identity);
                    ps.setInt(2, incomeYear);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        IdentityHistory.Builder builder = mapResultSetToIdentity(rs);
                        identityMap.set(builder.build());
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return true;
            });
            return identityMap.get();
        }

        IdentityHistory getIdentity(String identity, int incomeYear) {
            return findIdentity(identity, incomeYear, "no/ssb/dc/samples/ske/sirius/find-identity.sql");
        }

        IdentityHistory getFirstIdentity(String identity, int incomeYear) {
            return findIdentity(identity, incomeYear, "no/ssb/dc/samples/ske/sirius/find-first-identity.sql");
        }

        IdentityHistory getLastIdentity(String identity, int incomeYear) {
            return findIdentity(identity, incomeYear, "no/ssb/dc/samples/ske/sirius/find-last-identity.sql");
        }

        IdentityHistory getNextIdentity(String identity, int incomeYear) {
            AtomicReference<IdentityHistory> identityMap = new AtomicReference<>();
            doTransaction(() -> {
                String sql = FileAndClasspathReaderUtils.readFileOrClasspathResource("no/ssb/dc/samples/ske/sirius/find-next-identity.sql");
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, identity);
                    ps.setInt(2, incomeYear);
                    ResultSet rs = ps.executeQuery();
                    List<IdentityHistory> identityList = new ArrayList<>();
                    while (rs.next()) {
                        IdentityHistory identityResult = mapResultSetToIdentity(rs).build();
                        identityList.add(identityResult);
                    }
                    int index = identityList.size() - 1;
                    for (IdentityHistory item : identityList) {
                        if (item.identity.equals(identity)) {
                            break;
                        }
                        index--;
                    }
                    IdentityHistory result = identityList.get(index + (index < identityList.size() - 1 ? 1 : 0));
                    identityMap.set(result);

                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return true;
            });
            return identityMap.get();
        }

        boolean createTaxReturnStream(TaxReturnStream taxReturnStream) {
            return doTransaction(() -> {
                String sql = FileAndClasspathReaderUtils.readFileOrClasspathResource("no/ssb/dc/samples/ske/sirius/create-tax-return-stream.sql");
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, taxReturnStream.ulid);
                    ps.setString(2, taxReturnStream.position);
                    ps.setString(3, taxReturnStream.identifier);
                    ps.setInt(4, taxReturnStream.incomeYear);
                    ps.setTimestamp(5, Timestamp.from(taxReturnStream.registrationDate.toInstant()));
                    ps.setBoolean(6, taxReturnStream.shielded);
                    return ps.executeUpdate() == 1;

                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        private Connection init() {
            try {
                Class.forName("org.h2.Driver"); // register
                Connection conn = DriverManager.getConnection(JDBC_URL, "sa", "");
                dropOrCreateDatabase(conn);
                return conn;
            } catch (SQLException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        private Boolean doTransaction(Supplier<Boolean> callback) {
            try {
                conn.beginRequest();
                boolean result = callback.get();
                conn.commit();
                return result;

            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                try {
                    conn.endRequest();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void dropOrCreateDatabase(Connection conn) {
            try {
                String initSQL = FileAndClasspathReaderUtils.readFileOrClasspathResource("no/ssb/dc/samples/ske/sirius/init-db.sql");
                conn.beginRequest();

                try (Scanner s = new Scanner(initSQL)) {
                    s.useDelimiter("(;(\r)?\n)|(--\n)");
                    try (Statement st = conn.createStatement()) {
                        while (s.hasNext()) {
                            String line = s.next();
                            if (line.startsWith("/*!") && line.endsWith("*/")) {
                                int i = line.indexOf(' ');
                                line = line.substring(i + 1, line.length() - " */".length());
                            }

                            if (line.trim().length() > 0) {
                                st.execute(line);
                            }
                        }
                        conn.commit();
                        LOG.trace("Database create!");
                    }
                }

                conn.endRequest();

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() throws Exception {
            conn.close();
        }
    }

}
