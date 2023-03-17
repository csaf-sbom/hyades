package org.hyades;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Body;
import com.github.tomakehurst.wiremock.http.ContentTypeHeader;
import io.quarkus.kafka.client.serialization.ObjectMapperSerde;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import org.apache.http.HttpHeaders;
import org.apache.http.entity.ContentType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.eclipse.microprofile.config.ConfigProvider;
import org.hyades.common.KafkaTopic;
import org.hyades.model.Component;
import org.hyades.model.MetaModel;
import org.hyades.util.WireMockTestResource;
import org.hyades.util.WireMockTestResource.InjectWireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusIntegrationTest
@TestProfile(RepositoryMetaAnalyzerIT.TestProfile.class)
@QuarkusTestResource(KafkaCompanionResource.class)
@QuarkusTestResource(WireMockTestResource.class)
class RepositoryMetaAnalyzerIT {

    public static class TestProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.flyway.migrate-at-start", "true"
            );
        }
    }

    @InjectKafkaCompanion
    KafkaCompanion kafkaCompanion;

    @InjectWireMock
    WireMockServer wireMockServer;

    @BeforeEach
    void beforeEach() throws Exception {
        // Workaround for the fact that Quarkus < 2.17.0 does not support initializing the database container
        // with data. We can't use EntityManager etc. because the test is executed against an already built
        // artifact (JAR, container, or native image).
        // Can be replaced with quarkus.datasource.devservices.init-script-path after upgrading to Quarkus 2.17.0:
        // https://github.com/quarkusio/quarkus/pull/30455
        try (final Connection connection = DriverManager.getConnection(
                ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String.class),
                ConfigProvider.getConfig().getValue("quarkus.datasource.username", String.class),
                ConfigProvider.getConfig().getValue("quarkus.datasource.password", String.class))) {
            final PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO "REPOSITORY" ("ENABLED", "IDENTIFIER", "INTERNAL", "PASSWORD", "RESOLUTION_ORDER", "TYPE", "URL")
                    VALUES ('true', 'test', false, NULL, 1, 'GO_MODULES', 'http://localhost:%d');
                    """.formatted(wireMockServer.port()));
            ps.execute();
        }

        wireMockServer.stubFor(WireMock.get(WireMock.anyUrl())
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withResponseBody(Body.ofBinaryOrText("""
                               {
                                   "Version": "v6.6.6",
                                   "Time": "2022-09-28T21:59:32Z",
                                   "Origin": {
                                       "VCS": "git",
                                       "URL": "https://github.com/acme/acme-lib",
                                       "Ref": "refs/tags/v6.6.6",
                                       "Hash": "39a1d8f8f69040a53114e1ea481e48f6d792c05e"
                                   }
                               }
                                """.getBytes(), new ContentTypeHeader(MediaType.APPLICATION_JSON))
                        )));
    }

    @Test
    void test() {
        final var component = new Component();
        component.setUuid(UUID.randomUUID());
        component.setPurl("pkg:golang/github.com/acme/acme-lib@9.1.1");

        kafkaCompanion
                .produce(Serdes.UUID(), new ObjectMapperSerde<>(Component.class))
                .fromRecords(new ProducerRecord<>(KafkaTopic.REPO_META_ANALYSIS_COMMAND.getName(), component.getUuid(), component));

        final List<ConsumerRecord<UUID, MetaModel>> results = kafkaCompanion
                .consume(Serdes.UUID(), new ObjectMapperSerde<>(MetaModel.class))
                .fromTopics(KafkaTopic.REPO_META_ANALYSIS_RESULT.getName(), 1, Duration.ofSeconds(5))
                .awaitCompletion()
                .getRecords();

        assertThat(results).satisfiesExactly(
                record -> {
                    assertThat(record.key()).isEqualTo(component.getUuid());
                    assertThat(record.value()).isNotNull();

                    final MetaModel result = record.value();
                    assertThat(result.getComponent()).isNotNull();
                    assertThat(result.getLatestVersion()).isEqualTo("v6.6.6");
                    assertThat(result.getPublishedTimestamp()).isEqualTo("2022-09-28T21:59:32");
                }
        );
    }

}
