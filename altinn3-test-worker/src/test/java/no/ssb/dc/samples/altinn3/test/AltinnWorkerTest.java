package no.ssb.dc.samples.altinn3.test;

import no.ssb.config.DynamicConfiguration;
import no.ssb.config.StoreBasedDynamicConfiguration;
import no.ssb.dc.api.Builders;
import no.ssb.dc.api.Specification;
import no.ssb.dc.api.context.ExecutionContext;
import no.ssb.dc.api.node.Configuration;
import no.ssb.dc.api.node.Configurations;
import no.ssb.dc.api.node.Security;
import no.ssb.dc.api.node.builder.BuildContext;
import no.ssb.dc.api.node.builder.JwtIdentityBuilder;
import no.ssb.dc.api.node.builder.JwtIdentityTokenBodyPublisherProducerBuilder;
import no.ssb.dc.api.node.builder.SecurityBuilder;
import no.ssb.dc.api.node.builder.SpecificationBuilder;
import no.ssb.dc.api.util.CommonUtils;
import no.ssb.dc.core.executor.Worker;
import no.ssb.dc.core.handler.JwtTokenBodyPublisherProducerHandler;
import no.ssb.dc.core.security.CertificateFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static no.ssb.dc.api.Builders.addContent;
import static no.ssb.dc.api.Builders.body;
import static no.ssb.dc.api.Builders.bodyPublisher;
import static no.ssb.dc.api.Builders.claims;
import static no.ssb.dc.api.Builders.context;
import static no.ssb.dc.api.Builders.execute;
import static no.ssb.dc.api.Builders.forEach;
import static no.ssb.dc.api.Builders.get;
import static no.ssb.dc.api.Builders.headerClaims;
import static no.ssb.dc.api.Builders.jqpath;
import static no.ssb.dc.api.Builders.jwt;
import static no.ssb.dc.api.Builders.jwtToken;
import static no.ssb.dc.api.Builders.paginate;
import static no.ssb.dc.api.Builders.parallel;
import static no.ssb.dc.api.Builders.post;
import static no.ssb.dc.api.Builders.regex;
import static no.ssb.dc.api.Builders.security;
import static no.ssb.dc.api.Builders.sequence;
import static no.ssb.dc.api.Builders.status;
import static no.ssb.dc.api.Builders.whenVariableIsNull;
import static no.ssb.dc.api.node.builder.SpecificationBuilder.GLOBAL_CONFIGURATION;

public class AltinnWorkerTest {

    static final Logger LOG = LoggerFactory.getLogger(AltinnWorkerTest.class);

    static final DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
            .propertiesResource("application-override.properties") // gitignored
            .values("content.stream.connector", "rawdata")
            .values("rawdata.client.provider", "memory")
            .values("data.collector.worker.threads", "20")
            .build();


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
    void collect() {
        // Fix NodeBuilderDeserializer
        SpecificationBuilder specificationBuilder = Specification.start("ALTINN-TEST", "Altinn 3", "maskinporten-jwt-grant")
                .configure(context()
                        .topic("altinn-test")
                        .variable("nextPage", null) // TODO ask Altinn about self and next url parameters
                        .variable("appId", "${ENV.'ssb.altinn.app-id'}")
                        .variable("clientId", "${ENV.'ssb.altinn.clientId'}")
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
                                        .claim("scope", "altinn:instances.read altinn:instances.write")
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
                                .pipe(addContent("${position}", "entry"))
                                .pipe(forEach(jqpath(".data[]"))
                                        .pipe(execute("download-file")
                                                .requiredInput("accessToken")
                                                .inputVariable("dataId", jqpath(".id"))
                                                .inputVariable("instanceGuid", jqpath(".instanceGuid"))
                                        )
                                )
                        )
                        .validate(status().success(200))
                ).function(get("download-file")
                        .url("https://platform.tt02.altinn.no/storage/api/v1/instances/${ownerPartyId}/${instanceGuid}/data/${dataId}")
                        .header("Authorization", "Bearer ${accessToken}")
                        .pipe(addContent("${position}", "file-${dataId}"))
                        .validate(status()
                                .success(200)
                                .success(500) // inconsistent test data causes 500 error
                                .success(404) // 404 is due to success on 500 error
                        )
                );

        String serialized = specificationBuilder.serialize();
        LOG.trace("{}", serialized);

        Worker.newBuilder()
                .configuration(configuration.asMap())
                .specification(specificationBuilder)
                .buildCertificateFactory(CommonUtils.currentPath())
                .build()
                .run();

    }
}