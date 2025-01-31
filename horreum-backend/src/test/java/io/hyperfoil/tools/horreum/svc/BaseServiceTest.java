package io.hyperfoil.tools.horreum.svc;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Status;
import javax.transaction.TransactionManager;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;

import org.hibernate.query.NativeQuery;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladmihalcea.hibernate.type.json.JsonNodeBinaryType;

import io.hyperfoil.tools.horreum.action.GitHubIssueCommentAction;
import io.hyperfoil.tools.horreum.action.HttpAction;
import io.hyperfoil.tools.horreum.bus.MessageBus;
import io.hyperfoil.tools.horreum.changedetection.RelativeDifferenceChangeDetectionModel;
import io.hyperfoil.tools.horreum.entity.ExperimentComparison;
import io.hyperfoil.tools.horreum.entity.ExperimentProfile;
import io.hyperfoil.tools.horreum.entity.alerting.Change;
import io.hyperfoil.tools.horreum.entity.alerting.ChangeDetection;
import io.hyperfoil.tools.horreum.entity.alerting.DataPoint;
import io.hyperfoil.tools.horreum.entity.alerting.MissingDataRule;
import io.hyperfoil.tools.horreum.entity.alerting.Variable;
import io.hyperfoil.tools.horreum.entity.alerting.Watch;
import io.hyperfoil.tools.horreum.entity.json.Access;
import io.hyperfoil.tools.horreum.entity.json.AllowedSite;
import io.hyperfoil.tools.horreum.entity.json.DataSet;
import io.hyperfoil.tools.horreum.entity.json.Action;
import io.hyperfoil.tools.horreum.entity.json.Label;
import io.hyperfoil.tools.horreum.entity.json.Extractor;
import io.hyperfoil.tools.horreum.entity.json.Run;
import io.hyperfoil.tools.horreum.entity.json.Schema;
import io.hyperfoil.tools.horreum.entity.json.Test;
import io.hyperfoil.tools.horreum.entity.json.Transformer;
import io.hyperfoil.tools.horreum.entity.json.View;
import io.hyperfoil.tools.horreum.entity.json.ViewComponent;
import io.hyperfoil.tools.horreum.experiment.RelativeDifferenceExperimentModel;
import io.hyperfoil.tools.horreum.server.CloseMe;
import io.hyperfoil.tools.horreum.server.RoleManager;
import io.hyperfoil.tools.horreum.test.TestUtil;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.smallrye.jwt.build.Jwt;

public class BaseServiceTest {
   static final String[] UPLOADER_ROLES = { "foo-team", "foo-uploader", "uploader" };
   public static final String[] TESTER_ROLES = { "foo-team", "foo-tester", "tester", "viewer" };
   static final List<String> SYSTEM_ROLES = Collections.singletonList(Roles.HORREUM_SYSTEM);
   private static String UPLOADER_TOKEN;
   private static String TESTER_TOKEN;
   private static String ADMIN_TOKEN;

   protected final Logger log = Logger.getLogger(getClass());

   @Inject
   protected EntityManager em;

   @Inject
   TransactionManager tm;

   @Inject
   protected RoleManager roleManager;

   @Inject
   MessageBus messageBus;

   List<Runnable> afterMethodCleanup = new ArrayList<>();

   protected String getUploaderToken(){
      synchronized (BaseServiceTest.class){
         if(UPLOADER_TOKEN == null){
            UPLOADER_TOKEN = BaseServiceTest.getAccessToken("alice", UPLOADER_ROLES);
         }
         return UPLOADER_TOKEN;
      }
   }

   protected String getTesterToken(){
      synchronized (BaseServiceTest.class){
         if(TESTER_TOKEN == null){
            TESTER_TOKEN = BaseServiceTest.getAccessToken("alice", TESTER_ROLES);
         }
         return TESTER_TOKEN;
      }
   }

   protected String getAdminToken(){
      synchronized (BaseServiceTest.class){
         if(ADMIN_TOKEN == null){
            ADMIN_TOKEN = BaseServiceTest.getAccessToken("admin", "admin");
         }
         return ADMIN_TOKEN;
      }
   }
   protected static ObjectNode runWithValue(double value, Schema schema) {
      ObjectNode runJson = JsonNodeFactory.instance.objectNode();
      runJson.put("$schema", schema.uri);
      runJson.put("value", value);
      ArrayNode values = JsonNodeFactory.instance.arrayNode();
      values.add(++value);
      values.add(++value);
      values.add(++value);
      runJson.set("values", values);
      return runJson;
   }

   protected static ObjectNode runWithValue(double value, Schema... schemas) {
      ObjectNode root = null;
      for (Schema s : schemas) {
         ObjectNode n = runWithValue(value, s);
         if (root == null ) {
            root = n;
         } else {
            root.set("field_"+s.name, n);
         }
      }
      return root;
   }

   @BeforeEach
   public void beforeMethod(TestInfo info) {
      log.debugf("Starting test %s.%s", info.getTestClass().map(Class::getSimpleName).orElse("<unknown>"), info.getDisplayName());
   }

   @AfterEach
   public void afterMethod(TestInfo info) {
      log.debugf("Completed test %s.%s", info.getTestClass().map(Class::getSimpleName).orElse("<unknown>"), info.getDisplayName());
      dropAllViewsAndTests();
      afterMethodCleanup.forEach(Runnable::run);
      afterMethodCleanup.clear();
      log.debugf("Finished cleanup of test %s.%s", info.getTestClass().map(Class::getSimpleName).orElse("<unknown>"), info.getDisplayName());
   }

   protected void dropAllViewsAndTests() {
      Util.withTx(tm, () -> {
         try (CloseMe ignored = roleManager.withRoles(Stream.concat(Stream.of(TESTER_ROLES), Stream.of(Roles.HORREUM_SYSTEM, Roles.ADMIN))
               .collect(Collectors.toList()))) {
            ViewComponent.deleteAll();
            View.deleteAll();

            em.createNativeQuery("DELETE FROM test_transformers").executeUpdate();
            em.createNativeQuery("DELETE FROM transformer_extractors").executeUpdate();
            Transformer.deleteAll();
            em.createNativeQuery("DELETE FROM test_token").executeUpdate();
            Test.deleteAll();
            Change.deleteAll();
            DataPoint.deleteAll();
            ChangeDetection.deleteAll();
            Variable.deleteAll();

            DataSet.deleteAll();
            Run.deleteAll();

            em.createNativeQuery("DELETE FROM label_extractors").executeUpdate();
            Label.deleteAll();
            Schema.deleteAll();

            Action.deleteAll();
            AllowedSite.deleteAll();

            for (var subscription : Watch.listAll()) {
               subscription.delete();
            }
         }
         return null;
      });
      TestUtil.eventually(() -> TestUtil.isMessageBusEmpty(tm, em));
   }

   public static Test createExampleTest(String testName) {
      Test test = new Test();
      test.name = testName;
      test.description = "Bar";
      test.owner = TESTER_ROLES[0];
      View defaultView = new View();
      defaultView.name = "Default";
      defaultView.components = new ArrayList<>();
      defaultView.components.add(new ViewComponent("Some column", null, "foo"));
      test.views = Collections.singleton(defaultView);
      test.transformers = new ArrayList<>();
      return test;
   }

   public static String getAccessToken(String userName, String... groups) {
      return Jwt.preferredUserName(userName)
            .groups(new HashSet<>(Arrays.asList(groups)))
            .issuer("https://server.example.com")
            .audience("https://service.example.com")
            .jws()
            .keyId("1")
            .sign();
   }

   protected int uploadRun(Object runJson, String test) {
      long timestamp = System.currentTimeMillis();
      int runId = uploadRun(timestamp, timestamp, runJson, test);
      assertNotEquals(-1, runId);
      return runId;
   }

   protected int uploadRun(long timestamp, Object runJson, String test) {
      return uploadRun(timestamp, timestamp, runJson, test);
   }

   protected int uploadRun(long start, long stop, Object runJson, String test) {
      return uploadRun(start, stop, runJson, test, UPLOADER_ROLES[0], Access.PUBLIC);
   }

   protected int uploadRun(long start, long stop, Object runJson, String test, String owner, Access access) {
      String runIdString = RestAssured.given().auth().oauth2(getUploaderToken())
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(runJson)
            .post("/api/run/data?start=" + start + "&stop=" + stop + "&test=" + test + "&owner=" + owner + "&access=" + access)
            .then()
            .statusCode(200)
            .extract().asString();
      return Integer.parseInt(runIdString);
   }

   protected int uploadRun(long timestamp, JsonNode data, JsonNode metadata, String testName) {
      return uploadRun(timestamp, timestamp, data, metadata, testName, UPLOADER_ROLES[0], Access.PUBLIC);
   }

   protected int uploadRun(long start, long stop, JsonNode data, JsonNode metadata, String testName, String owner, Access access) {
      String runIdString = given().auth().oauth2(getUploaderToken())
            .header(HttpHeaders.CONTENT_TYPE, MediaType.MULTIPART_FORM_DATA)
            // the .toString().getBytes(...) is required because RestAssured otherwise won't send the filename
            // and Quarkus in turn will use null FileUpload: https://github.com/quarkusio/quarkus/issues/20938
            .multiPart("data", "data.json", data.toString().getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON)
            .multiPart("metadata", "metadata.json", metadata.toString().getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON)
            .post("/api/run/data?start=" + start + "&stop=" + stop + "&test=" + testName + "&owner=" + owner + "&access=" + access)
            .then()
            .statusCode(200)
            .extract().asString();
      int runId = Integer.parseInt(runIdString);
      return runId;
   }

   protected Test createTest(Test test) {
      return jsonRequest()
            .body(test)
            .post("/api/test")
            .then()
            .statusCode(200)
            .extract().body().as(Test.class);
   }

   protected void deleteTest(Test test) {
      RestAssured.given().auth().oauth2(getTesterToken())
            .delete("/api/test/" + test.id)
            .then()
            .statusCode(204);
   }

   protected RequestSpecification jsonRequest() {
      return RestAssured.given().auth().oauth2(getTesterToken())
            .header(HttpHeaders.CONTENT_TYPE, "application/json");
   }

   protected RequestSpecification jsonUploaderRequest() {
      return RestAssured.given().auth().oauth2(getUploaderToken())
            .header(HttpHeaders.CONTENT_TYPE, "application/json");
   }

   protected String getTestName(TestInfo info) {
      return info.getTestClass().map(Class::getName).orElse("<unknown>") + "." + info.getDisplayName();
   }

   protected Schema createExampleSchema(TestInfo info) {
      String name = info.getTestClass().map(Class::getName).orElse("<unknown>") + "." + info.getDisplayName();
      Schema schema = createSchema(name, uriForTest(info, "1.0"));
      addLabel(schema, "value", null, new Extractor("value", "$.value", false));
      return schema;
   }

   protected Schema createExampleSchema(String name, String className, String displayName, boolean label) {
      Schema schema = new Schema();
      schema.owner = TESTER_ROLES[0];
      schema.access = Access.PUBLIC;
      schema.name = name + "." + displayName;
      schema.uri = "urn:" + className + ":" + displayName + ":1.0";
      Integer id = jsonRequest().body(schema).post("/api/schema").then()
         .statusCode(200).extract().as(Integer.class);
      schema.id = id;

      if (label) {
         addLabel(schema, "value", null, new Extractor("value", "$.value", false));
      }
      assertNotNull(schema.id);
      return schema;
   }

   protected Schema createSchema(String name, String uri) {
      return createSchema(name, uri, null);
   }

   protected Schema createSchema(String name, String uri, JsonNode jsonSchema) {
      Schema schema = new Schema();
      schema.owner = TESTER_ROLES[0];
      schema.name = name;
      schema.uri = uri;
      schema.schema = jsonSchema;
      return addOrUpdateSchema(schema);
   }

   protected Schema addOrUpdateSchema(Schema schema) {
      Response response = jsonRequest().body(schema).post("/api/schema");
      response.then().statusCode(200);
      schema.id = Integer.parseInt(response.body().asString());
      return schema;
   }

   protected void deleteSchema(Schema schema) {
      jsonRequest().delete("/api/schema/" + schema.id).then().statusCode(204);
   }

   protected String uriForTest(TestInfo info, String suffix) {
      return "urn:" + info.getTestClass().map(Class::getName).orElse("<unknown>") + ":" + info.getDisplayName() + ":" + suffix;
   }

   protected int addLabel(Schema schema, String name, String function, Extractor... extractors) {
      return postLabel(schema, name, function, null, extractors);
   }

   protected int updateLabel(Schema schema, int labelId, String name, String function, Extractor... extractors) {
      return postLabel(schema, name, function, l -> l.id = labelId, extractors);
   }

   protected int postLabel(Schema schema, String name, String function, Consumer<Label> mutate, Extractor... extractors) {
      Label l = new Label();
      l.name = name;
      l.function = function;
      l.schema = schema;
      l.owner = TESTER_ROLES[0];
      l.access = Access.PUBLIC;
      l.extractors = Arrays.asList(extractors);
      if (mutate != null) {
         mutate.accept(l);
      }
      Response response = jsonRequest().body(l).post("/api/schema/" + schema.id + "/labels");
      response.then().statusCode(200);
      return Integer.parseInt(response.body().asString());
   }

   protected void deleteLabel(Schema schema, int labelId) {
      jsonRequest().delete("/api/schema/" + schema.id + "/labels/" + labelId).then().statusCode(204);
   }

   protected void setTestVariables(Test test, String name, String label, ChangeDetection... rds) {
      setTestVariables(test, name, Collections.singletonList(label), rds);
   }

   protected void setTestVariables(Test test, String name, List<String> labels, ChangeDetection... rds) {
      ArrayNode variables = JsonNodeFactory.instance.arrayNode();
      ObjectNode variable = JsonNodeFactory.instance.objectNode();
      variable.put("testid", test.id);
      variable.put("name", name);
      variable.set("labels", labels.stream().reduce(JsonNodeFactory.instance.arrayNode(), ArrayNode::add, ArrayNode::addAll));
      if (rds.length > 0) {
         ArrayNode rdsArray = JsonNodeFactory.instance.arrayNode();
         for (ChangeDetection rd : rds) {
            rdsArray.add(JsonNodeFactory.instance.objectNode().put("model", rd.model).set("config", rd.config));
         }
         variable.set("changeDetection", rdsArray);
      }
      variables.add(variable);
      jsonRequest().body(variables.toString()).post("/api/alerting/variables?test=" + test.id).then().statusCode(204);
   }

   protected <E> BlockingQueue<E> eventConsumerQueue(Class<? extends E> eventClass, String eventType, Predicate<E> filter) {
      BlockingQueue<E> queue = new LinkedBlockingDeque<>();
      AutoCloseable closeable = messageBus.subscribe(eventType, getClass().getName() + "_" + ThreadLocalRandom.current().nextLong(), eventClass, msg -> {
         if (eventClass.isInstance(msg)) {
            E event = eventClass.cast(msg);
            if (filter.test(event)) {
               queue.add(event);
            } else {
               log.infof("Ignoring event %s", event);
            }
         } else {
            throw new IllegalStateException("Unexpected type for event " + eventType + ": " + msg);
         }
      });
      afterMethodCleanup.add(() -> {
         try {
            closeable.close();
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
      });
      return queue;
   }

   protected ArrayNode jsonArray(String... items) {
      ArrayNode array = JsonNodeFactory.instance.arrayNode(items.length);
      for (String item : items) {
         array.add(item);
      }
      return array;
   }

   protected BlockingQueue<Integer> trashRun(int runId) throws InterruptedException {
      BlockingQueue<Integer> trashedQueue = eventConsumerQueue(Integer.class, Run.EVENT_TRASHED, r -> true);
      jsonRequest().post("/api/run/" + runId + "/trash").then().statusCode(204);
      assertEquals(runId, trashedQueue.poll(10, TimeUnit.SECONDS));
      return trashedQueue;
   }

   protected <T> T withExampleDataset(Test test, JsonNode data, Function<DataSet, T> testLogic) {
      BlockingQueue<DataSet.EventNew> dataSetQueue = eventConsumerQueue(DataSet.EventNew.class, DataSet.EVENT_NEW, e -> e.dataset.testid.equals(test.id));
      try {
         Run run = new Run();
         tm.begin();
         try (CloseMe ignored = roleManager.withRoles(Arrays.asList(UPLOADER_ROLES))) {
            run.data = data;
            run.testid = test.id;
            run.start = run.stop = Instant.now();
            run.owner = UPLOADER_ROLES[0];
            run.persistAndFlush();
         } finally {
            if (tm.getTransaction().getStatus() == Status.STATUS_ACTIVE) {
               tm.commit();
            } else {
               tm.rollback();
               fail();
            }
         }
         DataSet.EventNew event = dataSetQueue.poll(10, TimeUnit.SECONDS);
         assertNotNull(event);
         assertNotNull(event.dataset);
         // only to cover the summary call in API
         jsonRequest().get("/api/dataset/" + event.dataset.id + "/summary").then().statusCode(200);
         T value = testLogic.apply(event.dataset);
         tm.begin();
         Throwable error = null;
         try (CloseMe ignored = roleManager.withRoles(SYSTEM_ROLES)) {
            DataSet oldDs = DataSet.findById(event.dataset.id);
            if (oldDs != null) {
               oldDs.delete();
            }
            DataSet.delete("runid", run.id);
            Run.findById(run.id).delete();
         } catch (Throwable t) {
            error = t;
         } finally {
            if (tm.getTransaction().getStatus() == Status.STATUS_ACTIVE) {
               tm.commit();
            } else {
               tm.rollback();
               fail(error);
            }
         }
         return value;
      } catch (Exception e) {
         fail(e);
         return null;
      }
   }

   protected void addToken(Test test, int permissions, String value) {
      ObjectNode token = JsonNodeFactory.instance.objectNode();
      token.put("value", value);
      token.put("permissions", permissions);
      token.put("description", "blablabla");

      jsonRequest().header(HttpHeaders.CONTENT_TYPE, "application/json").body(token.toString())
            .post("/api/test/" + test.id + "/addToken").then().statusCode(200);
   }

   protected RequestSpecification bareRequest() {
      return RestAssured.given().auth().oauth2(getTesterToken());
   }

   protected void addTransformer(Test test, Transformer... transformers){
      List<Integer> ids = new ArrayList<>();
      assertNotNull(test.id);
      for (Transformer t : transformers) {
         ids.add(t.id);
      }
      jsonRequest().body(ids).post("/api/test/" + test.id + "/transformers").then().assertThat().statusCode(204);
   }

   protected Transformer createTransformer(String name, Schema schema, String function, Extractor... paths) {
      Transformer transformer = new Transformer();
      transformer.name = name;
      transformer.extractors = new ArrayList<>();
      for (Extractor path : paths) {
         if (path != null) {
            transformer.extractors.add(path);
         }
      }
      transformer.owner = TESTER_ROLES[0];
      transformer.access = Access.PUBLIC;
      transformer.schema = schema;
      transformer.function = function;
      transformer.targetSchemaUri = postFunctionSchemaUri(schema);
      Integer id = jsonRequest().body(transformer).post("/api/schema/"+schema.id+"/transformers")
            .then().statusCode(200).extract().as(Integer.class);
      transformer.id = id;
      return transformer;
   }

   protected String postFunctionSchemaUri(Schema s) {
      return "uri:" + s.name + "-post-function";
   }

   protected boolean checkTestId(int datasetId, int testId) {
      return Util.withTx(tm, () -> {
         try (CloseMe ignored = roleManager.withRoles(Collections.singleton(Roles.HORREUM_SYSTEM))) {
            List<?> list = em.createNativeQuery("SELECT testid FROM dataset WHERE id = ?1").setParameter(1, datasetId).getResultList();
            assertEquals(1, list.size());
            return testId == (int) list.get(0);
         }
      });
   }

   protected boolean checkRunTestId(int runId, int testId) {
      return Util.withTx(tm, () -> {
         try (CloseMe ignored = roleManager.withRoles(Collections.singleton(Roles.HORREUM_SYSTEM))) {
            List<?> list = em.createNativeQuery("SELECT testid FROM run WHERE id = ?1").setParameter(1, runId).getResultList();
            assertEquals(1, list.size());
            return testId == (int) list.get(0);
         }
      });
   }

   protected void addAllowedSite(String prefix) {
      given().auth().oauth2(getAdminToken()).header(HttpHeaders.CONTENT_TYPE, "text/plain")
            .body(prefix).post("/api/action/allowedSites").then().statusCode(200);
   }

   protected Response addTestHttpAction(Test test, String event, String url) {
      Action action = new Action();
      action.event = event;
      action.type = HttpAction.TYPE_HTTP;
      action.active = true;
      action.config = JsonNodeFactory.instance.objectNode().put("url", url);
      return jsonRequest().body(action).post("/api/test/" + test.id + "/action");
   }

   protected Response addTestGithubIssueCommentAction(Test test, String event, String formatter, String owner, String repo, String issue, String secretToken) {
      Action action = new Action();
      action.event = event;
      action.type = GitHubIssueCommentAction.TYPE_GITHUB_ISSUE_COMMENT;
      action.active = true;
      action.config = JsonNodeFactory.instance.objectNode()
            .put("formatter", formatter)
            .put("owner", owner)
            .put("repo", repo)
            .put("issue", issue);
      action.secrets = JsonNodeFactory.instance.objectNode().put("token", secretToken);
      return jsonRequest().body(action).post("/api/test/" + test.id + "/action");
   }

   protected Response addGlobalAction(String event, String url) {
      Action action = new Action();
      action.event = event;
      action.type = "http";
      action.active = true;
      action.config = JsonNodeFactory.instance.objectNode().put("url", url);
      return given().auth().oauth2(getAdminToken())
            .header(HttpHeaders.CONTENT_TYPE, "application/json").body(action).post("/api/action");
   }

   protected ChangeDetection addChangeDetectionVariable(Test test) {
      return addChangeDetectionVariable(test, 0.1, 2);
   }

   protected ChangeDetection addChangeDetectionVariable(Test test, double threshold, int window) {
      ChangeDetection cd = new ChangeDetection();
      cd.model = RelativeDifferenceChangeDetectionModel.NAME;
      cd.config = JsonNodeFactory.instance.objectNode().put("threshold", threshold).put("minPrevious", window).put("window", window).put("filter", "mean");
      setTestVariables(test, "Value", "value", cd);
      return cd;
   }

   protected int addMissingDataRule(Test test, String ruleName, ArrayNode labels, String condition, int maxStaleness) {
      MissingDataRule rule = new MissingDataRule();
      rule.test = test;
      rule.name = ruleName;
      rule.condition = condition;
      rule.labels = labels;
      rule.maxStaleness = maxStaleness;
      String ruleIdString = jsonRequest().body(rule).post("/api/alerting/missingdatarule?testId=" + test.id).then().statusCode(200).extract().body().asString();
      return Integer.parseInt(ruleIdString);
   }

   protected void addExperimentProfile(Test test, String name, Variable... variables) {
      ExperimentProfile profile = new ExperimentProfile();
      profile.name = name;
      profile.test = test;
      profile.selectorLabels = JsonNodeFactory.instance.arrayNode().add("isSnapshot");
      profile.baselineLabels = JsonNodeFactory.instance.arrayNode().add("isSnapshot");
      profile.baselineFilter = "snapshot => !snapshot";
      profile.comparisons = Stream.of(variables).map(v -> {
         ExperimentComparison comp = new ExperimentComparison();
         comp.variable = v;
         comp.model = RelativeDifferenceExperimentModel.NAME;
         comp.config = JsonNodeFactory.instance.objectNode().setAll(new RelativeDifferenceExperimentModel().config().defaults);
         return comp;
      }).collect(Collectors.toList());

      jsonRequest().body(profile).post("/api/experiments/" + test.id + "/profiles");
   }

   protected void validateDatabaseContents(HashMap<String, List<JsonNode>> tableContents) {
      Util.withTx(tm, () -> {
         em.clear();
         try (var h = roleManager.withRoles(Collections.singleton(Roles.HORREUM_SYSTEM))) {
            for (String table : tableContents.keySet()) {
               //noinspection unchecked
               List<JsonNode> rows = em.createNativeQuery("SELECT to_jsonb(t) AS json FROM \"" + table + "\" t;")
                     .unwrap(NativeQuery.class).addScalar("json", JsonNodeBinaryType.INSTANCE).getResultList();
               List<JsonNode> expected = tableContents.get(table);

               assertEquals(expected.size(), rows.size());
               // If the table does not have ID column we won't compare values
               if (!rows.isEmpty() && rows.get(0).hasNonNull("id")) {
                  Map<Integer, JsonNode> byId = rows.stream().collect(Collectors.toMap(row -> row.path("id").asInt(), Function.identity()));
                  assertEquals(rows.size(), byId.size());
                  for (var expectedRow : expected) {
                     JsonNode row = byId.get(expectedRow.path("id").asInt());
                     assertEquals(expectedRow, row, "Comparison failed in table " + table);
                  }
               }
            }
         }
         return null;
      });
   }

   protected HashMap<String, List<JsonNode>> dumpDatabaseContents() {
      @SuppressWarnings("unchecked") List<String> tables = em.createNativeQuery(
            "SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public';").getResultList();
      tables.remove("databasechangelog");
      tables.remove("databasechangeloglock");
      tables.remove("dbsecret");
      tables.remove("view_recalc_queue");
      tables.remove("label_recalc_queue");
      tables.remove("fingerprint_recalc_queue");

      HashMap<String, List<JsonNode>> tableContents = new HashMap<>();
      Util.withTx(tm, () -> {
         try (var h = roleManager.withRoles(Collections.singleton(Roles.HORREUM_SYSTEM))) {
            for (String table : tables) {
               //noinspection unchecked
               tableContents.put(table, em.createNativeQuery("SELECT to_jsonb(t) AS json FROM \"" + table + "\" t;")
                     .unwrap(NativeQuery.class).addScalar("json", JsonNodeBinaryType.INSTANCE).getResultList());
            }
         }
         return null;
      });
      return tableContents;
   }
}
