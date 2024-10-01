package io.descoped.dc.samples.ske.sirius;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.descoped.dc.api.CorrelationIds;
import io.descoped.dc.api.content.HttpRequestInfo;
import io.descoped.dc.api.content.MetadataContent;
import io.descoped.dc.api.context.ExecutionContext;
import io.descoped.dc.api.handler.QueryException;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.util.CommonUtils;
import io.descoped.dc.api.util.JsonParser;
import io.descoped.dc.core.executor.FixedThreadPool;
import io.descoped.dc.core.security.CertificateContext;
import io.descoped.dc.core.security.CertificateFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.net.ssl.SSLContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test case fetches a Sirius Utkast and Fastsatt Hendelseliste and Skattemeldinger and exports all data to zip file.
 * <p>
 * SKE doc: https://skatteetaten.github.io/datasamarbeid-api-dokumentasjon/reference_skattemelding
 */
public class SimpleSiriusFeedTest {

    final static Logger LOG = LoggerFactory.getLogger(SimpleSiriusFeedTest.class);
    final static String TEST_BASE_URL = "https://api-at.sits.no";
    final static List<Path> writtenFiles = new ArrayList<>();
    final boolean appendMessages = true;
    final int fromSequence = 1;
    final int numberOfEvents = 3000;

    static SSLContext getBusinessSSLContext() {
        CertificateFactory factory = CertificateFactory.scanAndCreate(CommonUtils.currentPath());
        CertificateContext context = factory.getCertificateContext("ske-test-certs");
        return context.sslContext();
    }

    @AfterAll
    public static void afterAll() {
        if (!writtenFiles.isEmpty()) pack();
    }

    static void pack() {
        writtenFiles.sort(Comparator.comparing(Path::toString));
        Path outputPath = CommonUtils.currentPath().resolve("target").resolve(String.format("sirius-dump-%s.zip", FileWriter.getTimestampAsString()));
        LOG.trace("Pack file: {}", outputPath.toString());
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
            try (ZipOutputStream zos = new ZipOutputStream(fos)) {
                for (Path sourceFile : writtenFiles) {
                    File sourceFileToZip = sourceFile.toFile();
                    try (FileInputStream fis = new FileInputStream(sourceFileToZip)) {
                        String workingDir = outputPath.getParent().normalize().toString();
                        ZipEntry zipEntry = new ZipEntry(sourceFileToZip.getCanonicalFile().toString().replace(workingDir, "").substring(1));
                        zos.putNextEntry(zipEntry);
                        byte[] bytes = new byte[1024];
                        int length;
                        while ((length = fis.read(bytes)) >= 0) {
                            zos.write(bytes, 0, length);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Response getHendelseListe(Client client, Hendelse hendelse, int fromSequence, int numberOfEvents) {
        Request request = Request.newRequestBuilder()
                .GET()
                .header("accept", "application/xml")
                .url(String.format("%s/api/formueinntekt/skattemelding/%s/hendelser/?fraSekvensnummer=%s&antall=%s", TEST_BASE_URL, hendelse.value, fromSequence, numberOfEvents))
                .build();
        return client.send(request);
    }

    CompletableFuture<RequestAndResponse> getSkattemelding(Client client, Hendelse hendelse, String identifier, String incomeYear, String snapshot) {
        Request request = Request.newRequestBuilder()
                .GET()
                .header("accept", "application/xml")
                .url(String.format("%s/api/formueinntekt/skattemelding/%s/ssb/%s/%s?gjelderPaaTidspunkt=%s", TEST_BASE_URL, hendelse.value, incomeYear, identifier, snapshot))
                .build();
        return CompletableFuture.supplyAsync(() -> new RequestAndResponse(request, client.sendAsync(request)));
    }

    @Disabled
    @ParameterizedTest
    @EnumSource(Hendelse.class)
    public void getHendelseListe(Hendelse hendelse) {
        Client client = Client.newClientBuilder().sslContext(getBusinessSSLContext()).build();

        FixedThreadPool threadPool = FixedThreadPool.newInstance(20);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        LOG.trace("BEGIN");

        final long past = Instant.now().toEpochMilli();
        Response hendelseListeResponse = getHendelseListe(client, hendelse, fromSequence, numberOfEvents);
        assertEquals(200, hendelseListeResponse.statusCode());

        StringBuilder comment = new StringBuilder();
        comment.append("\n").append(String.format("   Page fra sekvensnummer: %s - antall: %s%n", fromSequence, numberOfEvents));
        comment.append(String.format("   URL: %s%n", hendelseListeResponse.url()));
        long duration = (Instant.now().toEpochMilli() - past);
        comment.append(String.format("   Request duration: %s%n", duration));

        Document doc = XML.deserialize(hendelseListeResponse.body());
        XML.insertComment(doc, comment.toString());

        FileWriter fileWriter = new FileWriter(CommonUtils.currentPath().resolve("target").resolve("sirius").resolve(hendelse.value));
        fileWriter.writeHendelseListe(fromSequence, numberOfEvents, XML.toPrettyXMLWithCommentFix(doc));

        NodeList hendelser = doc.getDocumentElement().getElementsByTagName("hendelse");
        for (int i = 0; i < hendelser.getLength(); i++) {
            Element element = (Element) hendelser.item(i);

            String sekvensnummer = element.getElementsByTagName("sekvensnummer").item(0).getTextContent();
            String identifikator = element.getElementsByTagName("identifikator").item(0).getTextContent();
            String gjelderPeriode = element.getElementsByTagName("gjelderPeriode").item(0).getTextContent();
            String registreringstidspunkt = element.getElementsByTagName("registreringstidspunkt").item(0).getTextContent();

            CompletableFuture<Void> requestFuture = CompletableFuture
                    .supplyAsync(() -> {
                        final long past2 = Instant.now().toEpochMilli();
                        getSkattemelding(client, hendelse, identifikator, gjelderPeriode, registreringstidspunkt)
                                .thenApply(requestAndResponse -> {
                                    //assertEquals(200, skattemeldingResponse.statusCode());
                                    requestAndResponse.responseFuture.thenApply(response -> {
                                        StringBuilder comment2 = new StringBuilder();
                                        comment2.append("\n").append(String.format("   Sekvensnummer: %s%n", sekvensnummer));
                                        comment2.append(String.format("   URL: %s%n", response.url()));
                                        long duration2 = (Instant.now().toEpochMilli() - past2);
                                        comment2.append(String.format("   Request duration: %s%n", duration2));

                                        LOG.trace("Write: {} - duration: {} millis", sekvensnummer, duration2);
                                        HttpRequestInfo httpRequestInfo = new HttpRequestInfo(
                                                CorrelationIds.create(ExecutionContext.empty()),
                                                response.url(),
                                                response.statusCode(),
                                                requestAndResponse.request.headers(),
                                                response.headers(),
                                                duration2);
                                        String timestampAsString = FileWriter.getTimestampAsString();
                                        fileWriter.writeSkattemeldingMetadata(Integer.parseInt(sekvensnummer), httpRequestInfo, response.body(), timestampAsString);
                                        fileWriter.writeSkatteMelding(Integer.parseInt(sekvensnummer), XML.toPrettyXML(response.body(), comment2.toString()), timestampAsString);

                                        return response;
                                    }).join();
                                    return null;
                                }).join();
                        return null;
                    }, threadPool.getExecutor());

            futures.add(requestFuture);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .join();

        writtenFiles.addAll(fileWriter.positionAndFilenameMap.values());

        if (appendMessages) {
            fileWriter.appendMessages(fromSequence, numberOfEvents);
        }

        LOG.trace("END");
    }

    enum Hendelse {
        UTKAST("utkast"),
        FASTSATT("fastsatt");

        private final String value;

        Hendelse(String value) {
            this.value = value;
        }
    }

    static class RequestAndResponse {
        final Request request;
        final CompletableFuture<Response> responseFuture;

        public RequestAndResponse(Request request, CompletableFuture<Response> responseFuture) {
            this.request = request;
            this.responseFuture = responseFuture;
        }
    }

    static class FileWriter {
        private final Path outputPath;
        private final Map<String, Path> positionAndFilenameMap = new ConcurrentHashMap<>();

        FileWriter(Path outputPath) {
            this.outputPath = outputPath;
            LOG.trace("OutputPath: {}", outputPath.normalize().toString());
            try {
                Files.createDirectories(outputPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        static String getTimestampAsString() {
            return new SimpleDateFormat("yyyy-MM-dd_HHmmss").format(new Date());
        }

        void writeHendelseListe(Integer fromSequenceInclusive, Integer toSequenceInclusive, String xml) {
            try {
                String filename = String.format("%05d-%05d-hendelse-liste-%s.xml", fromSequenceInclusive, toSequenceInclusive, getTimestampAsString());
                Path filenamePath = outputPath.resolve(filename);
                if (!Files.exists(filenamePath)) {
                    Files.write(filenamePath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    positionAndFilenameMap.put(filename, filenamePath); // track hendelseliste for packing
                }
                BufferedWriter writer = Files.newBufferedWriter(filenamePath, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                writer.write(xml);
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        void writeSkattemeldingMetadata(Integer sequenceInclusive, HttpRequestInfo httpRequestInfo, byte[] content, String timestampAsString) {
            MetadataContent mc = new MetadataContent.Builder()
                    .resourceType(MetadataContent.ResourceType.DOCUMENT)
                    .correlationId(httpRequestInfo.getCorrelationIds())
                    .url(httpRequestInfo.getUrl())
                    .statusCode(httpRequestInfo.getStatusCode())
                    .topic("sirius-person-fastsatt")
                    .position(sequenceInclusive.toString())
                    .contentKey("skattemelding")
                    .contentType(httpRequestInfo.getResponseHeaders().firstValue("content-type").orElseGet(() -> "application/octet-stream"))
                    .contentLength(content.length)
                    .requestDurationNanoTime(httpRequestInfo.getRequestDurationNanoSeconds())
                    .requestHeaders(httpRequestInfo.getRequestHeaders())
                    .responseHeaders(httpRequestInfo.getResponseHeaders())
                    .build();

            try {
                String filename = String.format("%05d-skatte-melding-manifest-%s.json", sequenceInclusive, timestampAsString);
                Path filenamePath = outputPath.resolve(filename);
                positionAndFilenameMap.put(sequenceInclusive.toString() + "mf", filenamePath);
                BufferedWriter writer = Files.newBufferedWriter(filenamePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                writer.write(JsonParser.createJsonParser().toPrettyJSON(JsonParser.createJsonParser().fromJson(mc.toJSON(), ObjectNode.class)));
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        void writeSkatteMelding(Integer sequenceInclusive, String xml, String timestampAsString) {
            try {
                String filename = String.format("%05d-skatte-melding-%s.xml", sequenceInclusive, timestampAsString);
                Path filenamePath = outputPath.resolve(filename);
                positionAndFilenameMap.put(sequenceInclusive.toString(), filenamePath);
                BufferedWriter writer = Files.newBufferedWriter(filenamePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                writer.write(xml);
                writer.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        void appendMessages(Integer fromSequenceInclusive, Integer toSequenceInclusive) {
            try {
                String filename = String.format("%05d-%05d-alle-skattemeldinger-%s-xml.txt", fromSequenceInclusive, toSequenceInclusive, getTimestampAsString());
                Path filenamePath = outputPath.resolve(filename);
                Files.write(filenamePath, new byte[0], StandardOpenOption.CREATE);
                List<Path> files = positionAndFilenameMap.values().stream().sorted(Comparator.comparing(Path::toString)).skip(1).collect(Collectors.toList());
                BufferedWriter writer = Files.newBufferedWriter(filenamePath, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                for (Path file : files) {
                    if (file.toString().endsWith(".json")) continue;
                    String xml = Files.readString(file);
                    xml = xml.replace("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>", "");
                    writer.write(xml);
                }
                writer.flush();
                positionAndFilenameMap.put(filename, filenamePath);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static class XML {
        static byte[] serialize(Object document) {
            try (StringWriter writer = new StringWriter()) {
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
                transformer.transform(new DOMSource((Node) document), new StreamResult(writer));
                return writer.toString().getBytes();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new QueryException(e);
            }
        }

        static Document deserialize(byte[] source) {
            try {
                SAXParserFactory sax = SAXParserFactory.newInstance();
                sax.setNamespaceAware(false);
                XMLReader reader = sax.newSAXParser().getXMLReader();
                try (ByteArrayInputStream bais = new ByteArrayInputStream(source)) {
                    SAXSource saxSource = new SAXSource(reader, new InputSource(bais));
                    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
                    documentBuilderFactory.setNamespaceAware(false);
                    DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                    Document doc = documentBuilder.parse(saxSource.getInputSource());
                    doc.normalizeDocument();
                    return doc;
                }
            } catch (SAXException | IOException | ParserConfigurationException e) {
                throw new QueryException(new String(source), e);
            }
        }

        private static void insertComment(Document document, String comment) {
            Comment xmlComment = document.createComment(comment);
            Element documentElement = document.getDocumentElement();
            documentElement.getParentNode().insertBefore(xmlComment, documentElement);
        }

        static String toPrettyXML(Node node) {
            return new String(serialize(node), StandardCharsets.UTF_8);
        }

        static String toPrettyXML(byte[] bytes) {
            return toPrettyXML((Node) deserialize(bytes));
        }

        static String toPrettyXML(byte[] bytes, String comment) {
            Document deserialized = deserialize(bytes);
            insertComment(deserialized, comment);
            return toPrettyXMLWithCommentFix(deserialized);
        }

        private static String toPrettyXMLWithCommentFix(Node node) {
            return toPrettyXML((Node) node).replaceFirst("-->", "-->\n");
        }

        static String toPrettyXML(String xml) {
            return toPrettyXML((Node) deserialize(xml.getBytes()));
        }
    }

}
