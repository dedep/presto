/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.elasticsearch;

import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.airlift.log.Logging;
import io.airlift.tpch.TpchTable;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.plugin.tpch.TpchPlugin;
import io.prestosql.testing.QueryRunner;
import io.prestosql.tests.DistributedQueryRunner;
import io.prestosql.tests.TestingPrestoClient;

import java.io.File;
import java.net.URL;
import java.util.Map;

import static com.google.common.io.Resources.getResource;
import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.airlift.units.Duration.nanosSince;
import static io.prestosql.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.prestosql.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class ElasticsearchQueryRunner
{
    private ElasticsearchQueryRunner() {}

    private static final Logger LOG = Logger.get(ElasticsearchQueryRunner.class);
    private static final String TPCH_SCHEMA = "tpch";
    private static final int NODE_COUNT = 2;

    public static DistributedQueryRunner createElasticsearchQueryRunner(EmbeddedElasticsearchNode embeddedElasticsearchNode, Iterable<TpchTable<?>> tables)
            throws Exception
    {
        DistributedQueryRunner queryRunner = null;
        try {
            queryRunner = DistributedQueryRunner.builder(createSession())
                    .setNodeCount(NODE_COUNT)
                    .build();

            queryRunner.installPlugin(new TpchPlugin());
            queryRunner.createCatalog("tpch", "tpch");

            embeddedElasticsearchNode.start();

            ElasticsearchTableDescriptionProvider tableDescriptions = createTableDescriptions(queryRunner.getCoordinator().getMetadata());

            TestingElasticsearchConnectorFactory testFactory = new TestingElasticsearchConnectorFactory(tableDescriptions);

            installElasticsearchPlugin(queryRunner, testFactory);

            TestingPrestoClient prestoClient = queryRunner.getClient();

            LOG.info("Loading data...");
            long startTime = System.nanoTime();
            for (TpchTable<?> table : tables) {
                loadTpchTopic(embeddedElasticsearchNode, prestoClient, table);
            }
            LOG.info("Loading complete in %s", nanosSince(startTime).toString(SECONDS));

            return queryRunner;
        }
        catch (Exception e) {
            closeAllSuppress(e, queryRunner, embeddedElasticsearchNode);
            throw e;
        }
    }

    private static ElasticsearchTableDescriptionProvider createTableDescriptions(Metadata metadata)
            throws Exception
    {
        JsonCodec<ElasticsearchTableDescription> codec = new CodecSupplier<>(ElasticsearchTableDescription.class, metadata).get();

        URL metadataUrl = getResource(ElasticsearchQueryRunner.class, "/queryrunner");
        ElasticsearchConnectorConfig config = new ElasticsearchConnectorConfig()
                .setTableDescriptionDirectory(new File(metadataUrl.toURI()))
                .setDefaultSchema(TPCH_SCHEMA);
        return new ElasticsearchTableDescriptionProvider(config, codec);
    }

    private static void installElasticsearchPlugin(QueryRunner queryRunner, TestingElasticsearchConnectorFactory factory)
            throws Exception
    {
        queryRunner.installPlugin(new ElasticsearchPlugin(factory));
        URL metadataUrl = getResource(ElasticsearchQueryRunner.class, "/queryrunner");
        Map<String, String> config = ImmutableMap.<String, String>builder()
                .put("elasticsearch.default-schema-name", TPCH_SCHEMA)
                .put("elasticsearch.table-description-directory", metadataUrl.toURI().toString())
                .put("elasticsearch.scroll-size", "1000")
                .put("elasticsearch.scroll-timeout", "1m")
                .put("elasticsearch.request-timeout", "2m")
                .put("elasticsearch.max-request-retries", "3")
                .put("elasticsearch.max-request-retry-time", "5s")
                .build();

        queryRunner.createCatalog("elasticsearch", "elasticsearch", config);
    }

    private static void loadTpchTopic(EmbeddedElasticsearchNode embeddedElasticsearchNode, TestingPrestoClient prestoClient, TpchTable<?> table)
    {
        long start = System.nanoTime();
        LOG.info("Running import for %s", table.getTableName());
        ElasticsearchLoader loader = new ElasticsearchLoader(embeddedElasticsearchNode.getClient(), table.getTableName().toLowerCase(ENGLISH), prestoClient.getServer(), prestoClient.getDefaultSession());
        loader.execute(format("SELECT * from %s", new QualifiedObjectName(TPCH_SCHEMA, TINY_SCHEMA_NAME, table.getTableName().toLowerCase(ENGLISH))));
        LOG.info("Imported %s in %s", table.getTableName(), nanosSince(start).convertToMostSuccinctTimeUnit());
    }

    public static Session createSession()
    {
        return testSessionBuilder().setCatalog("elasticsearch").setSchema(TPCH_SCHEMA).build();
    }

    public static void main(String[] args)
            throws Exception
    {
        Logging.initialize();
        DistributedQueryRunner queryRunner = createElasticsearchQueryRunner(EmbeddedElasticsearchNode.createEmbeddedElasticsearchNode(), TpchTable.getTables());
        Thread.sleep(10);
        Logger log = Logger.get(ElasticsearchQueryRunner.class);
        log.info("======== SERVER STARTED ========");
        log.info("\n====\n%s\n====", queryRunner.getCoordinator().getBaseUrl());
    }
}
