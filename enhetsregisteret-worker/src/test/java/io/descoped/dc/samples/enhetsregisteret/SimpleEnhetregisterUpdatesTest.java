package io.descoped.dc.samples.enhetsregisteret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.descoped.dc.api.ConfigurationMap;
import io.descoped.dc.api.context.ExecutionContext;
import io.descoped.dc.api.el.ExpressionLanguage;
import io.descoped.dc.api.handler.QueryFeature;
import io.descoped.dc.api.http.Client;
import io.descoped.dc.api.http.Request;
import io.descoped.dc.api.http.Response;
import io.descoped.dc.api.node.JqPath;
import io.descoped.dc.api.node.builder.JqPathBuilder;
import io.descoped.dc.api.util.JsonParser;
import io.descoped.dc.core.handler.Queries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SimpleEnhetregisterUpdatesTest {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleEnhetregisterUpdatesTest.class);

    static final JsonParser jsonParser = JsonParser.createJsonParser();

    static String fetchPage() {
        Request request = Request.newRequestBuilder()
                .url("https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?dato=2020-10-05T00:00:00.000Z&page=0&size=2")
                .GET()
                .build();
        Response response = Client.newClient().send(request);
        assertEquals(200, response.statusCode());

        return new String(response.body());
    }

    static Stream<String> jsonData() {
        return Stream.of(
                fetchPage(),
                """
                        {
                            "_embedded" : {
                                "oppdaterteEnheter" : [ 
                                ]
                            }
                        }                
                        """
        );
    }

    @ParameterizedTest
    @MethodSource("jsonData")
    void thatQueryUpdateIdIsValid(String body) {
        assertDoesNotThrow(() -> {
            String json = jsonParser.toPrettyJSON(jsonParser.fromJson(body, JsonNode.class));

            JqPath jqPath = new JqPathBuilder("._embedded.oppdaterteEnheter[0]?.oppdateringsid").build();
            QueryFeature jq = Queries.from(jqPath);
            String updateId = jq.evaluateStringLiteral(json);

            LOG.trace("updateId: {}", updateId);
            LOG.trace("{}", json);
        });
    }

    @Test
    void thatQueryLastUpdateIdIsValid() {
        String json = """
                {
                  "_embedded": {
                    "oppdaterteEnheter": [
                      {
                        "oppdateringsid": 9400339,
                        "dato": "2020-10-05T04:01:22.299Z",
                        "organisasjonsnummer": "971421444",
                        "endringstype": "Sletting",
                        "_links": {
                          "enhet": {
                            "href": "https://data.brreg.no/enhetsregisteret/api/enheter/971421444"
                          }
                        }
                      },
                      {
                        "oppdateringsid": 9400341,
                        "dato": "2020-10-05T04:01:22.299Z",
                        "organisasjonsnummer": "915735045",
                        "endringstype": "Sletting",
                        "_links": {
                          "enhet": {
                            "href": "https://data.brreg.no/enhetsregisteret/api/enheter/915735045"
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        JqPath jqPath = new JqPathBuilder("._embedded.oppdaterteEnheter[-1]?.oppdateringsid").build();
        QueryFeature jq = Queries.from(jqPath);
        String updateId = jq.evaluateStringLiteral(json);
        assertEquals(9400341L, Long.valueOf(updateId));
    }

    @Test
    void thatNullELValueIsSupported() {
        {
            ExecutionContext context = ExecutionContext.empty();
            Map<String, String> map = new LinkedHashMap<>();
            map.put("updateId", null);
            ConfigurationMap config = new ConfigurationMap(map);
            context.services().register(ConfigurationMap.class, config);
            ExpressionLanguage el = new ExpressionLanguage(context);
            Object result = el.evaluateExpression("${ENV.updateId == null ? \"empty\" : ENV.updateId}");
            System.out.printf("eval: %s%n", result);
            assertEquals("empty", result);
        }
        {
            ExecutionContext context = ExecutionContext.empty();
            Map<String, String> map = new LinkedHashMap<>();
            map.put("updateId", "1");
            ConfigurationMap config = new ConfigurationMap(map);
            context.services().register(ConfigurationMap.class, config);
            ExpressionLanguage el = new ExpressionLanguage(context);
            Object result = el.evaluateExpression("${ENV.updateId == null ? \"empty\" : ENV.updateId}");
            System.out.printf("eval: %s%n", result);
            assertEquals("1", result);
        }
    }

    @Test
    void thatLastUpdateIdIsReturned() throws IOException {
        String json = """
                {
                  "_embedded" : {
                    "oppdaterteEnheter" : [ {
                      "oppdateringsid" : 9400339,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "971421444",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/971421444"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400341,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "915735045",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/915735045"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400344,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "993056219",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/993056219"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400345,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "922934029",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/922934029"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400346,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "997648501",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/997648501"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400347,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "920899404",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/920899404"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400348,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "914274931",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/914274931"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400349,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "913587456",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/913587456"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400350,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "917486026",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/917486026"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400351,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "921327528",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/921327528"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400352,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "912611620",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/912611620"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400353,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "996943372",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/996943372"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400354,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "911769727",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/911769727"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400355,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "894853492",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/894853492"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400356,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "925133817",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/925133817"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400357,
                      "dato" : "2020-10-05T04:01:22.299Z",
                      "organisasjonsnummer" : "922175551",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/922175551"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400358,
                      "dato" : "2020-10-05T04:01:43.945Z",
                      "organisasjonsnummer" : "912015335",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/912015335"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400359,
                      "dato" : "2020-10-05T04:01:43.945Z",
                      "organisasjonsnummer" : "923429905",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/923429905"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400362,
                      "dato" : "2020-10-05T04:01:43.945Z",
                      "organisasjonsnummer" : "994427237",
                      "endringstype" : "Sletting",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/994427237"
                        }
                      }
                    }, {
                      "oppdateringsid" : 9400372,
                      "dato" : "2020-10-05T04:01:43.945Z",
                      "organisasjonsnummer" : "987621370",
                      "endringstype" : "Endring",
                      "_links" : {
                        "enhet" : {
                          "href" : "https://data.brreg.no/enhetsregisteret/api/enheter/987621370"
                        }
                      }
                    } ]
                  },
                  "_links" : {
                    "first" : {
                      "href" : "https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?oppdateringsid=9400339&page=0&size=20"
                    },
                    "self" : {
                      "href" : "https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?oppdateringsid=9400339&page=0&size=20"
                    },
                    "next" : {
                      "href" : "https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?oppdateringsid=9400339&page=1&size=20"
                    },
                    "last" : {
                      "href" : "https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?oppdateringsid=9400339&page=24939&size=20"
                    }
                  },
                  "page" : {
                    "size" : 20,
                    "totalElements" : 498787,
                    "totalPages" : 24940,
                    "number" : 0
                  }
                }
                """;

        JqPath jqPath = new JqPathBuilder("._embedded | .oppdaterteEnheter[-1] | .oppdateringsid").build();
//        JqPath jqPath = new JqPathBuilder("_embedded.oppdaterteEnheter[-1].oppdateringsid").build();

        ObjectNode source = (ObjectNode) jsonParser.mapper().readTree(json.getBytes(StandardCharsets.UTF_8));

//        JqPath jqPath = new JqPathBuilder("._embedded.oppdaterteEnheter[-1] | select(.[]) | .oppdateringsid)").build();
        QueryFeature jq = Queries.from(jqPath);
        String updateId = jq.evaluateStringLiteral(source);
        LOG.trace("{}", updateId);
    }

    @Test
    void thatCannotIterateOfNullIsGuardedBugix() throws IOException {
        String json = """
                {"_links":{"self":{"href":"https://data.brreg.no/enhetsregisteret/api/oppdateringer/enheter?oppdateringsid=10713512&page=0&size=20"}},"page":{"size":20,"totalElements":0,"totalPages":0,"number":0}}
                """;

        JqPath jqPath = new JqPathBuilder("._embedded | .oppdaterteEnheter[]?").build();

        ObjectNode source = (ObjectNode) jsonParser.mapper().readTree(json.getBytes(StandardCharsets.UTF_8));
        QueryFeature jq = Queries.from(jqPath);

        String result = jq.evaluateStringLiteral(source);
        LOG.trace("{}", result);
    }
}
