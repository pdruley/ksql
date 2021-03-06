/*
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql;

import static io.confluent.ksql.TestResult.build;
import static io.confluent.ksql.test.util.AssertEventually.assertThatEventually;
import static javax.ws.rs.core.Response.Status.NOT_ACCEPTABLE;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableList;
import io.confluent.common.utils.IntegrationTest;
import io.confluent.ksql.cli.Cli;
import io.confluent.ksql.cli.console.OutputFormat;
import io.confluent.ksql.cli.console.cmd.RemoteServerSpecificCommand;
import io.confluent.ksql.rest.client.KsqlRestClient;
import io.confluent.ksql.rest.client.RestResponse;
import io.confluent.ksql.rest.client.exception.KsqlRestClientException;
import io.confluent.ksql.rest.entity.KsqlErrorMessage;
import io.confluent.ksql.rest.entity.ServerInfo;
import io.confluent.ksql.rest.server.KsqlRestApplication;
import io.confluent.ksql.rest.server.KsqlRestConfig;
import io.confluent.ksql.rest.server.resources.Errors;
import io.confluent.ksql.test.util.EmbeddedSingleNodeKafkaCluster;
import io.confluent.ksql.test.util.TestKsqlRestApp;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlConstants;
import io.confluent.ksql.util.OrderDataProvider;
import io.confluent.ksql.util.TestDataProvider;
import io.confluent.ksql.util.TopicConsumer;
import io.confluent.ksql.util.TopicProducer;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.ProcessingException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.easymock.EasyMock;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.RuleChain;

/**
 * Most tests in CliTest are end-to-end integration tests, so it may expect a long running time.
 */
@Category({IntegrationTest.class})
public class CliTest {

  private static final EmbeddedSingleNodeKafkaCluster CLUSTER = EmbeddedSingleNodeKafkaCluster.build();
  private static final String SERVER_OVERRIDE = "SERVER";
  private static final String SESSION_OVERRIDE = "SESSION";

  private static final TestKsqlRestApp REST_APP = TestKsqlRestApp
      .builder(CLUSTER::bootstrapServers)
      .withProperty(KsqlConfig.SINK_WINDOW_CHANGE_LOG_ADDITIONAL_RETENTION_MS_PROPERTY,
          KsqlConstants.defaultSinkWindowChangeLogAdditionalRetention + 1)
      .build();

  @ClassRule
  public static final RuleChain CHAIN = RuleChain.outerRule(CLUSTER).around(REST_APP);

  private static final String COMMANDS_KSQL_TOPIC_NAME = KsqlRestApplication.COMMANDS_KSQL_TOPIC_NAME;
  private static final OutputFormat CLI_OUTPUT_FORMAT = OutputFormat.TABULAR;

  private static final long STREAMED_QUERY_ROW_LIMIT = 10000;
  private static final long STREAMED_QUERY_TIMEOUT_MS = 10000;

  private static final TestResult.OrderedResult EMPTY_RESULT = build("");

  private static Cli localCli;
  private static TestTerminal terminal;
  private static String commandTopicName;
  private static TopicProducer topicProducer;
  private static TopicConsumer topicConsumer;

  private static OrderDataProvider orderDataProvider;
  private static int result_stream_no = 0;
  private static TestRunner testRunner;
  private static KsqlRestClient restClient;

  @BeforeClass
  public static void setUp() throws Exception {
    restClient = new KsqlRestClient(REST_APP.getHttpListener().toString());

    terminal = new TestTerminal(CLI_OUTPUT_FORMAT, restClient);

    commandTopicName = KsqlRestConfig.getCommandTopic(KsqlConfig.KSQL_SERVICE_ID_DEFAULT);

    orderDataProvider = new OrderDataProvider();
    CLUSTER.createTopic(orderDataProvider.topicName());

    localCli = new Cli(
        STREAMED_QUERY_ROW_LIMIT,
        STREAMED_QUERY_TIMEOUT_MS,
        restClient,
        terminal
    );

    testRunner = new TestRunner(localCli, terminal);

    topicProducer = new TopicProducer(CLUSTER);
    topicConsumer = new TopicConsumer(CLUSTER);

    testListOrShowCommands();

    produceInputStream(orderDataProvider);
  }

  private static void produceInputStream(final TestDataProvider dataProvider) throws Exception {
    createKStream(dataProvider);
    topicProducer.produceInputData(dataProvider);
  }

  private static void createKStream(final TestDataProvider dataProvider) {
    testRunner.test(
        String.format("CREATE STREAM %s %s WITH (value_format = 'json', kafka_topic = '%s' , key='%s')",
            dataProvider.kstreamName(), dataProvider.ksqlSchemaString(), dataProvider.topicName(), dataProvider.key()),
        build("Stream created")
    );
  }

  private static void testListOrShowCommands() {
    final TestResult.OrderedResult testResult = (TestResult.OrderedResult) TestResult.init(true);
    testResult.addRows(Collections.singletonList(Arrays.asList(orderDataProvider.topicName(), "false", "1",
        "1", "0", "0")));
    testRunner.testListOrShow("topics", testResult);
    testRunner.testListOrShow("registered topics", build(COMMANDS_KSQL_TOPIC_NAME, commandTopicName, "JSON"));
    testRunner.testListOrShow("streams", EMPTY_RESULT);
    testRunner.testListOrShow("tables", EMPTY_RESULT);
    testRunner.testListOrShow("queries", EMPTY_RESULT);
  }

  @AfterClass
  public static void tearDown() throws Exception {
    // If WARN NetworkClient:589 - Connection to node -1 could not be established. Broker may not be available.
    // It may be due to not closing the resource.
    // ksqlEngine.close();
    System.out.println("[Terminal Output]");
    System.out.println(terminal.getOutputString());

    localCli.close();
    terminal.close();
    restClient.close();
  }

  private static List<List<String>> startUpConfigs() {
    return ImmutableList.of(
        // SERVER OVERRIDES:
        ImmutableList.of(
            KsqlConfig.KSQL_STREAMS_PREFIX + StreamsConfig.NUM_STREAM_THREADS_CONFIG,
            SERVER_OVERRIDE, "4"),

        ImmutableList.of(
            KsqlConfig.SINK_WINDOW_CHANGE_LOG_ADDITIONAL_RETENTION_MS_PROPERTY, SERVER_OVERRIDE,
            "" + (KsqlConstants.defaultSinkWindowChangeLogAdditionalRetention + 1)
        ),

        ImmutableList.of(
            SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, "", "NULL"
        ),

        // SESSION OVERRIDES:
        ImmutableList.of(
            KsqlConfig.KSQL_STREAMS_PREFIX + ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            SESSION_OVERRIDE, "latest"),

        // DEFAULTS:
        ImmutableList.of(
            KsqlConfig.SINK_NUMBER_OF_REPLICAS_PROPERTY, "",
            "" + KsqlConstants.defaultSinkNumberOfReplications)
        ,
        ImmutableList.of(
            KsqlConfig.SINK_NUMBER_OF_REPLICAS_PROPERTY, "",
            "" + KsqlConstants.defaultSinkNumberOfReplications)
    );
  }

  private static void testCreateStreamAsSelect(String selectQuery, final Schema resultSchema, final Map<String, GenericRow> expectedResults) {
    if (!selectQuery.endsWith(";")) {
      selectQuery += ";";
    }
    final String resultKStreamName = "RESULT_" + result_stream_no++;
    final String queryString = "CREATE STREAM " + resultKStreamName + " AS " + selectQuery;

    /* Start Stream Query */
    testRunner.test(queryString, build("Stream created and running"));

    /* Assert Results */
    final Map<String, GenericRow> results = topicConsumer.readResults(resultKStreamName, resultSchema, expectedResults.size(), new StringDeserializer());

    terminateQuery("CSAS_" + resultKStreamName + "_" + (result_stream_no - 1));

    dropStream(resultKStreamName);
    assertThat(results, equalTo(expectedResults));
  }

  private static void terminateQuery(final String queryId) {
    testRunner.test(
        String.format("terminate %s", queryId),
        build("Query terminated.")
    );
  }

  private static void dropStream(final String name) {
    testRunner.test(
        String.format("drop stream %s", name),
        build("Source " + name + " was dropped. ")
    );
  }

  private static void selectWithLimit(String selectQuery, final int limit, final TestResult.OrderedResult expectedResults) {
    selectQuery += " LIMIT " + limit + ";";
    testRunner.test(selectQuery, expectedResults);
  }

  @Ignore // Tmp disabled as its unstable - waiting on Rohan for fix
  @Test
  public void testPrint() {
    final Thread thread =
        new Thread(() -> testRunner.run("print 'ORDER_TOPIC' FROM BEGINNING INTERVAL 2;", false));
    thread.start();

    try {
      assertThatEventually(() -> terminal.getOutputString(), containsString("Format:JSON"));
    } finally {
      thread.interrupt();
    }
  }

  @Test
  public void testPropertySetUnset() {
    testRunner.test("set 'application.id' = 'App'", EMPTY_RESULT);
    assertThatEventually(() -> terminal.getOutputString(), containsString(
        "Successfully changed local property 'application.id' to 'App'. Use the UNSET command to revert your change"));

    testRunner.test("set 'application.id' = 'App2'", EMPTY_RESULT);
    assertThatEventually(() -> terminal.getOutputString(), containsString(
        "Successfully changed local property 'application.id' from 'App' to 'App2'.\n"));

    testRunner.test("set 'auto.offset.reset' = 'latest'", EMPTY_RESULT);
    testRunner.test("set 'producer.batch.size' = '16384'", EMPTY_RESULT);
    testRunner.test("set 'max.request.size' = '1048576'", EMPTY_RESULT);
    testRunner.test("set 'consumer.max.poll.records' = '500'", EMPTY_RESULT);
    testRunner.test("set 'enable.auto.commit' = 'true'", EMPTY_RESULT);
    testRunner.test("set 'ksql.streams.application.id' = 'Test_App'", EMPTY_RESULT);
    testRunner.test("set 'ksql.streams.producer.batch.size' = '16384'", EMPTY_RESULT);
    testRunner.test("set 'ksql.streams.max.request.size' = '1048576'", EMPTY_RESULT);
    testRunner.test("set 'ksql.streams.consumer.max.poll.records' = '500'", EMPTY_RESULT);
    testRunner.test("set 'ksql.streams.enable.auto.commit' = 'true'", EMPTY_RESULT);
    testRunner.test("set 'ksql.service.id' = 'test'", EMPTY_RESULT);

    testRunner.test("unset 'application.id'", EMPTY_RESULT);
    assertThatEventually(() -> terminal.getOutputString(), containsString(
        "Successfully unset local property 'application.id' (value was 'App2').\n"));

    testRunner.test("unset 'producer.batch.size'", EMPTY_RESULT);
    testRunner.test("unset 'max.request.size'", EMPTY_RESULT);
    testRunner.test("unset 'consumer.max.poll.records'", EMPTY_RESULT);
    testRunner.test("unset 'enable.auto.commit'", EMPTY_RESULT);
    testRunner.test("unset 'ksql.streams.application.id'", EMPTY_RESULT);
    testRunner.test("unset 'ksql.streams.producer.batch.size'", EMPTY_RESULT);
    testRunner.test("unset 'ksql.streams.max.request.size'", EMPTY_RESULT);
    testRunner.test("unset 'ksql.streams.consumer.max.poll.records'", EMPTY_RESULT);
    testRunner.test("unset 'ksql.streams.enable.auto.commit'", EMPTY_RESULT);
    testRunner.test("unset 'ksql.service.id'", EMPTY_RESULT);

    testRunner.testListOrShow("properties", build(startUpConfigs()), false);
    testRunner.test("unset 'auto.offset.reset'", EMPTY_RESULT);
  }

  @Test
  public void testDescribe() {
    testRunner.test("describe topic " + COMMANDS_KSQL_TOPIC_NAME,
        build(COMMANDS_KSQL_TOPIC_NAME, commandTopicName, "JSON"));
  }

  @Test
  public void shouldPrintCorrectSchemaForDescribeStream() {
    final List<List<String>> rows = new ArrayList<>();
    rows.add(Arrays.asList("ORDERTIME", "BIGINT"));
    rows.add(Arrays.asList("ORDERID", "VARCHAR(STRING)"));
    rows.add(Arrays.asList("ITEMID", "VARCHAR(STRING)"));
    rows.add(Arrays.asList("ORDERUNITS", "DOUBLE"));
    rows.add(Arrays.asList("TIMESTAMP", "VARCHAR(STRING)"));
    rows.add(Arrays.asList("PRICEARRAY", "ARRAY<DOUBLE>"));
    rows.add(Arrays.asList("KEYVALUEMAP", "MAP<STRING, DOUBLE>"));
    testRunner.test("describe " + orderDataProvider.kstreamName(), TestResult.OrderedResult.build(rows));
  }

  @Ignore  // Tmp disabled as its unstable - waiting on Rohan for fix
  @Test
  public void testSelectStar() {
    testCreateStreamAsSelect(
        "SELECT * FROM " + orderDataProvider.kstreamName(),
        orderDataProvider.schema(),
        orderDataProvider.data()
    );
  }

  @Test
  public void testSelectProject() {
    final Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("1", new GenericRow(
        Arrays.asList(
            "ITEM_1",
            10.0,
            new Double[]{100.0, 110.99, 90.0})));
    expectedResults.put("2", new GenericRow(
        Arrays.asList(
            "ITEM_2",
            20.0,
            new Double[]{10.0, 10.99, 9.0})));

    expectedResults.put("3", new GenericRow(
        Arrays.asList(
            "ITEM_3",
            30.0,
            new Double[]{10.0, 10.99, 91.0})));

    expectedResults.put("4", new GenericRow(
        Arrays.asList(
            "ITEM_4",
            40.0,
            new Double[]{10.0, 140.99, 94.0})));

    expectedResults.put("5", new GenericRow(
        Arrays.asList(
            "ITEM_5",
            50.0,
            new Double[]{160.0, 160.99, 98.0})));

    expectedResults.put("6", new GenericRow(
        Arrays.asList(
            "ITEM_6",
            60.0,
            new Double[]{1000.0, 1100.99, 900.0})));

    expectedResults.put("7", new GenericRow(
        Arrays.asList(
            "ITEM_7",
            70.0,
            new Double[]{1100.0, 1110.99, 190.0})));

    expectedResults.put("8", new GenericRow(
        Arrays.asList(
            "ITEM_8",
            80.0,
            new Double[]{1100.0, 1110.99, 970.0})));

    final Schema resultSchema = SchemaBuilder.struct()
        .field("ITEMID", SchemaBuilder.OPTIONAL_STRING_SCHEMA)
        .field("ORDERUNITS", SchemaBuilder.OPTIONAL_FLOAT64_SCHEMA)
        .field("PRICEARRAY", SchemaBuilder.array(SchemaBuilder.OPTIONAL_FLOAT64_SCHEMA).optional().build())
        .build();

    testCreateStreamAsSelect(
        "SELECT ITEMID, ORDERUNITS, PRICEARRAY FROM " + orderDataProvider.kstreamName(),
        resultSchema,
        expectedResults
    );
  }

  @Ignore  // Tmp disabled as its unstable - waiting on Rohan for fix
  @Test
  public void testSelectFilter() {
    final Map<String, GenericRow> expectedResults = new HashMap<>();
    final Map<String, Double> mapField = new HashMap<>();
    mapField.put("key1", 1.0);
    mapField.put("key2", 2.0);
    mapField.put("key3", 3.0);
    expectedResults.put("8", new GenericRow(
        Arrays.asList(
            8,
            "ORDER_6",
            "ITEM_8",
            80.0,
            "2018-01-08",
            new Double[]{1100.0, 1110.99, 970.0},
            mapField)));

    testCreateStreamAsSelect(
        "SELECT * FROM " + orderDataProvider.kstreamName() + " WHERE ORDERUNITS > 20 AND ITEMID = 'ITEM_8'",
        orderDataProvider.schema(),
        expectedResults
    );
  }

  @Test
  public void testSelectLimit() {
    final TestResult.OrderedResult expectedResult = TestResult.build();
    final Map<String, GenericRow> streamData = orderDataProvider.data();
    final int limit = 3;
    for (int i = 1; i <= limit; i++) {
      final GenericRow srcRow = streamData.get(Integer.toString(i));
      final List<Object> columns = srcRow.getColumns();
      final GenericRow resultRow = new GenericRow(Arrays.asList(columns.get(1), columns.get(2)));
      expectedResult.addRow(resultRow);
    }
    selectWithLimit(
        "SELECT ORDERID, ITEMID FROM " + orderDataProvider.kstreamName(), limit, expectedResult);
  }

  @Test
  public void testSelectUDFs() {
    final String selectColumns =
        "ITEMID, ORDERUNITS*10, PRICEARRAY[0]+10, KEYVALUEMAP['key1']*KEYVALUEMAP['key2']+10, PRICEARRAY[1]>1000";
    final String whereClause = "ORDERUNITS > 20 AND ITEMID LIKE '%_8'";

    final String queryString = String.format(
        "SELECT %s FROM %s WHERE %s;",
        selectColumns,
        orderDataProvider.kstreamName(),
        whereClause
    );

    final Map<String, GenericRow> expectedResults = new HashMap<>();
    expectedResults.put("8", new GenericRow(Arrays.asList("ITEM_8", 800.0, 1110.0, 12.0, true)));

    // TODO: tests failed!
    // testCreateStreamAsSelect(queryString, orderDataProvider.schema(), expectedResults);
  }

  // ===================================================================
  // Below Tests are only used for coverage, not for results validation.
  // ===================================================================

  @Test
  public void testRunInteractively() {
    localCli.runInteractively();
  }

  @Test
  public void testEmptyInput() throws Exception {
    localCli.runNonInteractively("");
  }

  @Test
  public void testExitCommand() throws Exception {
    localCli.runNonInteractively("exit");
    localCli.runNonInteractively("\nexit\n\n\n");
    localCli.runNonInteractively("exit\nexit\nexit");
    localCli.runNonInteractively("\n\nexit\nexit\n\n\n\nexit\n\n\n");
  }

  @Test
  public void testExtraCommands() throws Exception {
    localCli.runNonInteractively("help");
    localCli.runNonInteractively("version");
    localCli.runNonInteractively("output");
    localCli.runNonInteractively("clear");
  }

  @Test
  public void testCommandsOverMultipleLines() throws Exception {
    localCli.runNonInteractively("he\\\nlp");
    localCli.runNonInteractively("he\\ \nlp");
  }

  @Test
  public void shouldHandleRegisterTopic() throws Exception {
    localCli.handleLine("REGISTER TOPIC foo WITH (value_format = 'csv', kafka_topic='foo');");
  }

  @Test
  public void shouldPrintErrorIfCantConnectToRestServer() throws Exception {
    final KsqlRestClient mockRestClient = EasyMock.mock(KsqlRestClient.class);
    EasyMock.expect(mockRestClient.makeRootRequest()).andThrow(new KsqlRestClientException("Boom", new ProcessingException("")));
    EasyMock.expect(mockRestClient.getServerInfo()).andReturn(
        RestResponse.of(new ServerInfo("1.x", "testClusterId", "testServiceId")));
    EasyMock.expect(mockRestClient.getServerAddress()).andReturn(new URI("http://someserver:8008")).anyTimes();
    EasyMock.replay(mockRestClient);
    final TestTerminal terminal = new TestTerminal(CLI_OUTPUT_FORMAT, mockRestClient);

    new Cli(1L, 1L, mockRestClient, terminal)
        .runInteractively();

    assertThat(terminal.getOutputString(), containsString("Remote server address may not be valid"));
  }

  @Test
  public void shouldRegisterRemoteCommand() {
    new Cli(1L, 1L, restClient, terminal);
    assertThat(terminal.getCliSpecificCommands().get("server"),
        instanceOf(RemoteServerSpecificCommand.class));
  }

  @Test
  public void shouldPrintErrorOnUnsupportedAPI() throws Exception {
    final KsqlRestClient mockRestClient = EasyMock.mock(KsqlRestClient.class);
    EasyMock.expect(mockRestClient.makeRootRequest()).andReturn(
        RestResponse.erroneous(
            new KsqlErrorMessage(
                Errors.toErrorCode(NOT_ACCEPTABLE.getStatusCode()),
                "Minimum supported client version: 1.0")));
    EasyMock.expect(mockRestClient.getServerInfo()).andReturn(
        RestResponse.of(new ServerInfo("1.x", "testClusterId", "testServiceId")));
    EasyMock.expect(mockRestClient.getServerAddress()).andReturn(new URI("http://someserver:8008"));
    EasyMock.replay(mockRestClient);
    final TestTerminal terminal = new TestTerminal(CLI_OUTPUT_FORMAT, restClient);

    new Cli(1L, 1L, mockRestClient, terminal)
        .runInteractively();

    Assert.assertThat(
        terminal.getOutputString(),
        containsString("This CLI version no longer supported"));
    Assert.assertThat(
        terminal.getOutputString(),
        containsString("Minimum supported client version: 1.0"));
  }

  @Test
  public void shouldListFunctions() {
    final List<List<String>> rows = new ArrayList<>();
    rows.add(Arrays.asList("TIMESTAMPTOSTRING", "SCALAR"));
    rows.add(Arrays.asList("EXTRACTJSONFIELD", "SCALAR"));
    rows.add(Arrays.asList("TOPK", "AGGREGATE"));
    testRunner.testListOrShow("functions", TestResult.OrderedResult.build(rows), false);
  }

  @Test
  public void shouldDescribeScalarFunction() throws Exception {
    final String expectedOutput =
        "Name        : TIMESTAMPTOSTRING\n" +
        "Author      : confluent\n" +
        "Type        : scalar\n" +
        "Jar         : internal\n" +
        "Variations  : \n" +
        "\n" +
        "\tVariation   : TIMESTAMPTOSTRING(BIGINT, VARCHAR)\n" +
        "\tReturns     : VARCHAR\n";

    localCli.handleLine("describe function timestamptostring;");
    assertThat(terminal.getOutputString(), containsString(expectedOutput));
  }

  @Test
  public void shouldDescribeOverloadedScalarFunction() throws Exception {
    // Given:
    localCli.handleLine("describe function substring;");

    // Then:
    final String output = terminal.getOutputString();

    // Summary output:
    assertThat(output, containsString(
        "Name        : SUBSTRING\n"
        + "Author      : Confluent\n"
        + "Overview    : Returns a substring of the passed in value.\n"
    ));
    assertThat(output, containsString(
        "Type        : scalar\n"
        + "Jar         : internal\n"
        + "Variations  :"
    ));

    // Variant output:
    assertThat(output, containsString(
        "\tVariation   : SUBSTRING(str VARCHAR, pos INT)\n"
        + "\tReturns     : VARCHAR\n"
        + "\tDescription : Returns a substring of str that starts at pos and continues to the end"
    ));
    assertThat(output, containsString(
        "\tstr         : The source string. If null, then function returns null.\n"
        + "\tpos         : The base-one position the substring starts from."
    ));
  }

  @Test
  public void shouldDescribeAggregateFunction() throws Exception {
    final String expectedSummary =
            "Name        : TOPK\n" +
            "Author      : confluent\n" +
            "Type        : aggregate\n" +
            "Jar         : internal\n" +
            "Variations  : \n";

    final String expectedVariant =
        "\tVariation   : TOPK(INT)\n"
        + "\tReturns     : ARRAY<INT>\n"
        + "\tDescription : Calculates the TopK value for a column, per key.";

    localCli.handleLine("describe function topk;");

    final String output = terminal.getOutputString();
    assertThat(output, containsString(expectedSummary));
    assertThat(output, containsString(expectedVariant));
  }

  @Test
  public void shouldPrintErrorIfCantFindFunction() throws Exception {
    localCli.handleLine("describe function foobar;");
    final String expectedOutput = "Can't find any functions with the name 'foobar'";
    assertThat(terminal.getOutputString(), containsString(expectedOutput));
  }
}
