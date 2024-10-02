/*
 * Copyright 2024 YCSB contributors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package site.ycsb.db;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.cloud.datastore.*;
import com.google.cloud.datastore.Entity;
import com.google.datastore.v1.*;
import com.google.datastore.v1.CommitRequest.Mode;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.ReadOptions.ReadConsistency;
import com.google.datastore.v1.Value;
import com.google.datastore.v1.client.DatastoreHelper;
import com.google.cloud.datastore.Datastore;
import com.google.cloud.datastore.DatastoreOptions;
//import com.google.cloud.opentelemetry.trace.TraceConfiguration;
import com.google.cloud.opentelemetry.trace.TraceExporter;
//import io.opentelemetry.api.GlobalOpenTelemetry;
//import io.opentelemetry.api.trace.Span;
//import io.opentelemetry.api.trace.SpanContext;
//import io.opentelemetry.api.trace.TraceFlags;
//import io.opentelemetry.api.trace.TraceState;
//import io.opentelemetry.api.trace.Tracer;
//import io.opentelemetry.context.Context;
//import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import static io.opentelemetry.semconv.resource.attributes.ResourceAttributes.SERVICE_NAME;

import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;

import javax.annotation.Nullable;

/**
 * Google Cloud Datastore Client for YCSB.
 */

public class GoogleDatastoreClient extends DB {
  /**
   * Defines a MutationType used in this class.
   */
  private enum MutationType {
    UPSERT,
    UPDATE,
    DELETE
  }

  /**
   * Defines a EntityGroupingMode enum used in this class.
   */
  private enum EntityGroupingMode {
    ONE_ENTITY_PER_GROUP,
    MULTI_ENTITY_PER_GROUP
  }

  private static Logger logger =
      Logger.getLogger(GoogleDatastoreClient.class);

  // Read consistency defaults to "STRONG" per YCSB guidance.
  // User can override this via configure.
  private ReadConsistency readConsistency = ReadConsistency.STRONG;

  private boolean isEventualConsistency = false;

  private EntityGroupingMode entityGroupingMode =
      EntityGroupingMode.ONE_ENTITY_PER_GROUP;

  private String rootEntityName;

  private Datastore datastore = null;

  private static boolean skipIndex = true;

  private Tracer tracer = null;

  /**
   * Initialize any state for this DB. Called once per DB instance; there is
   * one DB instance per client thread.
   */
  @Override
  public void init() throws DBException {
    String debug = getProperties().getProperty("googledatastore.debug", null);
    if (null != debug && "true".equalsIgnoreCase(debug)) {
      logger.setLevel(Level.DEBUG);
    }

    String skipIndexString = getProperties().getProperty(
        "googledatastore.skipIndex", null);
    if (null != skipIndexString && "false".equalsIgnoreCase(skipIndexString)) {
      skipIndex = false;
    }
    // We need the following 3 essential properties to initialize datastore:
    //
    // - ProjectId,
    // - DatasetId,
    // - Path to private key file,
    // - Service account email address.
    String projectId = getProperties().getProperty(
        "googledatastore.projectId", null);
    if (projectId == null) {
      throw new DBException(
          "Required property \"projectId\" missing.");
    }
    String datasetId = getProperties().getProperty(
        "googledatastore.datasetId", null);
    if (datasetId == null) {
      throw new DBException(
          "Required property \"datasetId\" missing.");
    }

    String privateKeyFile = getProperties().getProperty(
        "googledatastore.privateKeyFile", null);
    String serviceAccountEmail = getProperties().getProperty(
        "googledatastore.serviceAccountEmail", null);

    // Below are properties related to benchmarking.

    String readConsistencyConfig = getProperties().getProperty(
        "googledatastore.readConsistency", null);
    if (readConsistencyConfig != null) {
      try {
        this.readConsistency = ReadConsistency.valueOf(
            readConsistencyConfig.trim().toUpperCase());

        this.isEventualConsistency = readConsistencyConfig.trim().toUpperCase().equals("EVENTUAL");
        logger.debug("ReadConsistency: " + this.readConsistency + ". isEventual: " + this.isEventualConsistency);

      } catch (IllegalArgumentException e) {
        throw new DBException("Invalid read consistency specified: " +
            readConsistencyConfig + ". Expecting STRONG or EVENTUAL.");
      }
    }

    //
    // Entity Grouping Mode (googledatastore.entitygroupingmode), see
    // documentation in conf/googledatastore.properties.
    //
    String entityGroupingConfig = getProperties().getProperty(
        "googledatastore.entityGroupingMode", null);
    if (entityGroupingConfig != null) {
      try {
        this.entityGroupingMode = EntityGroupingMode.valueOf(
            entityGroupingConfig.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new DBException("Invalid entity grouping mode specified: " +
            entityGroupingConfig + ". Expecting ONE_ENTITY_PER_GROUP or " +
            "MULTI_ENTITY_PER_GROUP.");
      }
    }

    this.rootEntityName = getProperties().getProperty(
        "googledatastore.rootEntityName", "YCSB_ROOT_ENTITY");


    // Configure OpenTelemetry Tracing SDK
    Resource resource = Resource
        .getDefault().merge(Resource.builder().put(SERVICE_NAME, "My App").build());
    SpanExporter gcpTraceExporter;
    try {
      gcpTraceExporter = TraceExporter.createWithDefaultConfiguration();
    } catch (Exception exception) {
      throw new DBException("Unable to create gcp trace exporter " +
          exception.getMessage(), exception);
    }

    // Using a batch span processor
    // You can use `.setScheduleDelay()`, `.setExporterTimeout()`,
    // `.setMaxQueueSize`(), and `.setMaxExportBatchSize()` to further customize.
    SpanProcessor gcpBatchSpanProcessor =
        BatchSpanProcessor.builder(gcpTraceExporter).build();

    // Export directly Cloud Trace with 10% trace sampling ratio
    OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(gcpBatchSpanProcessor)
            .setSampler(Sampler.traceIdRatioBased(0.5))
            .build()).buildAndRegisterGlobal();

    tracer = otel.getTracer("YCSB_Datastore_Test");

    try {
      // Setup the connection to Google Cloud Datastore with the credentials
      // obtained from the configure.
      //DatastoreOptions.Builder options = new DatastoreOptions.Builder().projectId("cindy-cloud-sdk-test");
      Credential credential = GoogleCredential.getApplicationDefault();
      if (serviceAccountEmail != null && privateKeyFile != null) {
        credential = DatastoreHelper.getServiceAccountCredential(
            serviceAccountEmail, privateKeyFile);
        logger.info("Using JWT Service Account credential.");
        logger.info("DatasetID: " + datasetId + ", Service Account Email: " +
            serviceAccountEmail + ", Private Key File Path: " + privateKeyFile);
      } else {
        logger.info("Using default gcloud credential.");
        logger.info("DatasetID: " + datasetId
            + ", Service Account Email: " + ((GoogleCredential) credential).getServiceAccountId());
      }

      DatastoreOptions datastoreOptions = DatastoreOptions
          .newBuilder()
          .setProjectId(projectId)
          .setDatabaseId(datasetId)
          .setOpenTelemetryOptions(
              DatastoreOpenTelemetryOptions.newBuilder()
                  .setTracingEnabled(true)
                  .setOpenTelemetry(otel)
                  .build())
          .build();

      datastore = datastoreOptions.getService();

    } catch (GeneralSecurityException exception) {
      throw new DBException("Security error connecting to the datastore: " +
          exception.getMessage(), exception);

    } catch (IOException exception) {
      throw new DBException("I/O error connecting to the datastore: " +
          exception.getMessage(), exception);
    }

    logger.info("Datastore client instance created: " +
        datastore.toString());
  }

  @Override
  public Status read(String table, String key, Set<String> fields,
                     Map<String, ByteIterator> result) {

    KeyFactory keyFactory = datastore.newKeyFactory().setKind(table);
    Entity entity = null;
    Span readSpan = tracer.spanBuilder("ycsb-read").startSpan();
    try (Scope ignore = readSpan.makeCurrent()) {
      if (isEventualConsistency) {
        entity = datastore.get(keyFactory.newKey(key), ReadOption.eventualConsistency());
      } else {
        entity = datastore.get(keyFactory.newKey(key));
      }
    } catch (com.google.cloud.datastore.DatastoreException exception) {
      readSpan.setStatus(StatusCode.ERROR, exception.getMessage());
      logger.error(
          String.format("Datastore Exception when reading (%s): %s",
              exception.getMessage(),
              exception.getCode()));

      // DatastoreException.getCode() returns an HTTP response code which we
      // will bubble up to the user as part of the YCSB Status "name".
      return new Status("ERROR-" + exception.getCode(), exception.getMessage());
    } finally {
      readSpan.end();
    }

    System.out.println(entity);

    logger.debug("Read entity: " + entity.toString());

    Map<String, com.google.cloud.datastore.Value<?>> properties = entity.getProperties();
    Set<String> propertiesToReturn =
        (fields == null ? properties.keySet() : fields);

    for (String name : propertiesToReturn) {
      if (properties.containsKey(name)) {
        result.put(name, new StringByteIterator(properties.get(name)
            .toString()));
      }
    }
    return Status.OK;
  }

  @Override
  public Status scan(String table, String startkey, int recordcount,
                     Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
    // TODO: Implement Scan as query on primary key.
    return Status.NOT_IMPLEMENTED;
  }

  @Override
  public Status update(String table, String key,
                       Map<String, ByteIterator> values) {

    return doSingleItemMutation(table, key, values, MutationType.UPDATE);
  }

  @Override
  public Status insert(String table, String key,
                       Map<String, ByteIterator> values) {
    // Use Upsert to allow overwrite of existing key instead of failing the
    // load (or run) just because the DB already has the key.
    // This is the same behavior as what other DB does here (such as
    // the DynamoDB client).
    return doSingleItemMutation(table, key, values, MutationType.UPSERT);
  }

  @Override
  public Status delete(String table, String key) {
    return doSingleItemMutation(table, key, null, MutationType.DELETE);
  }

  private Key.Builder buildPrimaryKey(String table, String key) {
    Key.Builder result = Key.newBuilder();

    if (this.entityGroupingMode == EntityGroupingMode.MULTI_ENTITY_PER_GROUP) {
      // All entities are in side the same group when we are in this mode.
      result.addPath(Key.PathElement.newBuilder().setKind(table).
          setName(rootEntityName));
    }

    return result.addPath(Key.PathElement.newBuilder().setKind(table)
        .setName(key));
  }

  private com.google.cloud.datastore.Key buildPrimaryKey1(String table, String key) {

    return datastore.newKeyFactory().setKind(table).newKey(key);
  }

  private Status doSingleItemMutation(String table, String key,
                                      @Nullable Map<String, ByteIterator> values,
                                      MutationType mutationType) {
    // First build the key.
    Key.Builder datastoreKey = buildPrimaryKey(table, key);
    Span singleItemSpan = tracer.spanBuilder("ycsb-update").startSpan();

    // Build a commit request in non-transactional mode.
    // Single item mutation to google datastore
    // is always atomic and strongly consistent. Transaction is only necessary
    // for multi-item mutation, or Read-modify-write operation.
    CommitRequest.Builder commitRequest = CommitRequest.newBuilder();
    commitRequest.setMode(Mode.NON_TRANSACTIONAL);

    if (mutationType == MutationType.DELETE) {
      commitRequest.addMutationsBuilder().setDelete(datastoreKey);

    } else {
      // If this is not for delete, build the entity.
      Entity.Builder entityBuilder = Entity.newBuilder(buildPrimaryKey1(table, key));
      //  entityBuilder.setKey(buildPrimaryKey1(table, key));

      // Entity entity1 = Entity.newBuilder(datastoreKey);
      for (Entry<String, ByteIterator> val : values.entrySet()) {
        entityBuilder.set(val.getKey(), com.google.cloud.datastore.Value.fromPb(Value.newBuilder()
            .setStringValue(val.getValue().toString())
            .setExcludeFromIndexes(skipIndex).build()));
      }
      Entity entity = entityBuilder.build();
      logger.debug("entity built as: " + entity.toString());

      try (Scope ignore = singleItemSpan.makeCurrent()) {
        if (mutationType == MutationType.UPSERT) {
          //  commitRequest.addMutationsBuilder().setUpsert(entity);
          datastore.put(entity);
        } else if (mutationType == MutationType.UPDATE) {
          // commitRequest.addMutationsBuilder().setUpdate(entity);
          datastore.update(entity);
        } else {
          throw new RuntimeException("Impossible MutationType, code bug.");
        }
      } catch (Exception exception) {
        singleItemSpan.setStatus(StatusCode.ERROR, exception.getMessage());
        // Catch all Datastore rpc errors.
        // Log the exception, the name of the method called and the error code.
        logger.error(
            String.format("Datastore Exception when committing : %s",
                exception.getMessage()));

        // DatastoreException.getCode() returns an HTTP response code which we
        // will bubble up to the user as part of the YCSB Status "name".
        return new Status("ERROR-", exception.getMessage());
      } finally {
        singleItemSpan.end();
      }
    }

    return Status.OK;
  }
}
 