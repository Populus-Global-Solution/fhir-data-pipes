/*
 * Copyright 2020-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.fhir.analytics;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.ParserOptions;
import ca.uhn.fhir.parser.IParser;
import com.cerner.bunsen.FhirContexts;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import com.google.fhir.analytics.JdbcConnectionPools.DataSourceConfig;
import com.google.fhir.analytics.model.DatabaseConfiguration;
import com.google.fhir.analytics.view.ViewApplicationException;
import java.beans.PropertyVetoException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.sql.DataSource;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.hl7.fhir.r4.hapi.fluentpath.FhirPathR4;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the common functionality for all Fns that need to fetch FHIR resources and convert them
 * to Avro and JSON records. The non-abstract sub-classes should implement `ProcessElement` using
 * `processBundle` auxiliary method. Note the code reuse pattern that we really need here is
 * composition (not inheritance) but because of Beam complexities (e.g., certain work need to be
 * done during `setup()` where PipelienOptions not available) we use inheritance.
 *
 * @param <T> The type of the elements of the input PCollection.
 */
abstract class FetchSearchPageFn<T> extends DoFn<T, KV<String, Integer>> {

  private static final Logger log = LoggerFactory.getLogger(FetchSearchPageFn.class);

  private final Counter numFetchedResources;

  private final Counter totalFetchTimeMillis;

  private final Counter totalGenerateTimeMillis;

  private final Counter totalPushTimeMillis;

  private final String sourceUrl;

  private final String sourceUser;

  private final String sourcePw;

  private final String oAuthTokenEndpoint;

  private final String oAuthClientId;

  private final String oAuthClientSecret;

  private final boolean mergeGoldenResources;

  private final boolean treatPossibleMatchesAsMatches;

  private final List<String> goldenResourceTypes;

  protected final String sinkPath;

  private final String sinkUsername;

  private final String sinkPassword;

  protected final String stageIdentifier;

  protected final String parquetFile;

  private final int secondsToFlush;

  private final int rowGroupSize;

  protected final DataSourceConfig sinkDbConfig;

  protected final String viewDefinitionsDir;

  private final int initialPoolSize;

  private final int maxPoolSize;

  private static final Cache<String, String> goldenResources = Caffeine.newBuilder().maximumSize(10000).build();;

  @VisibleForTesting protected ParquetUtil parquetUtil;

  protected FetchUtil fetchUtil;

  protected FhirSearchUtil fhirSearchUtil;

  protected FhirStoreUtil fhirStoreUtil;

  protected JdbcResourceWriter jdbcWriter;

  protected IParser parser;

  protected FhirContext fhirContext;

  protected FhirPathR4 fhirPath;

  FetchSearchPageFn(FhirEtlOptions options, String stageIdentifier) {
    this.sinkPath = options.getFhirSinkPath();
    this.sinkUsername = options.getSinkUserName();
    this.sinkPassword = options.getSinkPassword();
    this.sourceUrl = options.getFhirServerUrl();
    this.sourceUser = options.getFhirServerUserName();
    this.sourcePw = options.getFhirServerPassword();
    this.oAuthTokenEndpoint = options.getFhirServerOAuthTokenEndpoint();
    this.oAuthClientId = options.getFhirServerOAuthClientId();
    this.oAuthClientSecret = options.getFhirServerOAuthClientSecret();
    this.mergeGoldenResources = options.getMergeGoldenResources();
    this.treatPossibleMatchesAsMatches = options.getTreatPossibleMatchesAsMatches();
    this.goldenResourceTypes = List.of(options.getGoldenResourceTypes().split(","));
    this.stageIdentifier = stageIdentifier;
    this.parquetFile = options.getOutputParquetPath();
    this.secondsToFlush = options.getSecondsToFlushParquetFiles();
    this.rowGroupSize = options.getRowGroupSizeForParquetFiles();
    this.viewDefinitionsDir = options.getViewDefinitionsDir();
    if (options.getSinkDbConfigPath().isEmpty()) {
      this.sinkDbConfig = null;
    } else {
      try {
        this.sinkDbConfig =
            JdbcConnectionPools.dbConfigToDataSourceConfig(
                DatabaseConfiguration.createConfigFromFile(options.getSinkDbConfigPath()));
      } catch (IOException e) {
        String error = "Cannot access file " + options.getSinkDbConfigPath();
        log.error(error);
        throw new IllegalArgumentException(error);
      }
    }
    this.initialPoolSize = options.getJdbcInitialPoolSize();
    this.maxPoolSize = options.getJdbcMaxPoolSize();
    this.numFetchedResources =
        Metrics.counter(
            MetricsConstants.METRICS_NAMESPACE,
            MetricsConstants.NUM_FETCHED_RESOURCES + stageIdentifier);
    this.totalFetchTimeMillis =
        Metrics.counter(
            MetricsConstants.METRICS_NAMESPACE,
            MetricsConstants.TOTAL_FETCH_TIME_MILLIS + stageIdentifier);
    this.totalGenerateTimeMillis =
        Metrics.counter(
            MetricsConstants.METRICS_NAMESPACE,
            MetricsConstants.TOTAL_GENERATE_TIME_MILLIS + stageIdentifier);
    this.totalPushTimeMillis =
        Metrics.counter(
            MetricsConstants.METRICS_NAMESPACE,
            MetricsConstants.TOTAL_PUSH_TIME_MILLIS + stageIdentifier);
  }

  @Setup
  public void setup() throws SQLException, PropertyVetoException {
    log.debug("Starting setup for stage " + stageIdentifier);
    // TODO make this configurable
    //   https://github.com/GoogleCloudPlatform/openmrs-fhir-analytics/issues/400
    fhirContext = FhirContexts.forR4();
    // The documentation for `FhirContext` claims that it is thread-safe but looking at the code,
    // it is not obvious if it is. This might be an issue when we write to it, like the next line.
    fhirContext.setParserOptions(
        // We want to keep the original IDs; this is particularly useful when the `fullUrl` is not
        // a URL but a URN, e.g., `urn:uuid:...`; for example when Bundles come from JSON files.
        // Note `IIdType.getIdPart` extracts only the logical ID part when exporting but that code
        // path does not work for URNs (e.g., when importing files).
        new ParserOptions().setOverrideResourceIdWithBundleEntryFullUrl(false));
    fhirContext.getRestfulClientFactory().setSocketTimeout(40000);
    // Note this parser is not used when fetching resources from a HAPI server. That's why we need
    // to change the `setOverrideResourceIdWithBundleEntryFullUrl` globally above such that the
    // parsers used in the HAPI client code is impacted too.
    parser = fhirContext.newJsonParser();
    fhirStoreUtil =
        FhirStoreUtil.createFhirStoreUtil(
            sinkPath, sinkUsername, sinkPassword, fhirContext.getRestfulClientFactory());
    fetchUtil =
        new FetchUtil(
            sourceUrl,
            sourceUser,
            sourcePw,
            oAuthTokenEndpoint,
            oAuthClientId,
            oAuthClientSecret,
            fhirContext);
    fhirSearchUtil = new FhirSearchUtil(fetchUtil);
    parquetUtil =
        new ParquetUtil(
            fhirContext.getVersion().getVersion(),
            parquetFile,
            secondsToFlush,
            rowGroupSize,
            stageIdentifier + "_");
    if (sinkDbConfig != null) {
      DataSource jdbcSink =
          JdbcConnectionPools.getInstance()
              .getPooledDataSource(sinkDbConfig, initialPoolSize, maxPoolSize);
      // TODO separate view generation from writing; TBD in a more generic version of:
      //  https://github.com/google/fhir-data-pipes/issues/288
      jdbcWriter = new JdbcResourceWriter(jdbcSink, viewDefinitionsDir, fhirContext);
    }

    this.fhirPath = new FhirPathR4(fhirContext);
  }

  @Teardown
  public void teardown() throws IOException {
    parquetUtil.closeAllWriters();
  }

  protected void addFetchTime(long millis) {
    totalFetchTimeMillis.inc(millis);
  }

  protected void processBundle(Bundle bundle)
      throws IOException, SQLException, ViewApplicationException {
    this.processBundle(bundle, null);
  }

  protected void processBundle(Bundle bundle, @Nullable Set<String> resourceTypes)
      throws IOException, SQLException, ViewApplicationException {
    if (bundle != null && bundle.getEntry() != null) {
      numFetchedResources.inc(bundle.getEntry().size());

      if (mergeGoldenResources) {
        processGoldenMerging(bundle);
      }

      if (!parquetFile.isEmpty()) {
        long startTime = System.currentTimeMillis();
        parquetUtil.writeRecords(bundle, resourceTypes);
        totalGenerateTimeMillis.inc(System.currentTimeMillis() - startTime);
      }
      if (!this.sinkPath.isEmpty()) {
        long pushStartTime = System.currentTimeMillis();
        fhirStoreUtil.uploadBundle(bundle);
        totalPushTimeMillis.inc(System.currentTimeMillis() - pushStartTime);
      }
      if (sinkDbConfig != null) {
        if (bundle.getEntry() == null) {
          return;
        }
        for (BundleEntryComponent entry : bundle.getEntry()) {
          Resource resource = entry.getResource();
          if (resourceTypes == null || resourceTypes.contains(resource.getResourceType().name())) {
            jdbcWriter.writeResource(resource);
          }
        }
      }
    }
  }

  private void processGoldenMerging(Bundle bundle) {
    log.debug("Processing golden resources for Bundle");

    bundle
        .getEntry()
        .removeIf(
            entry -> {
              Resource resource = entry.getResource();
              if (goldenResourceTypes.contains(resource.fhirType())) {
                String goldenResourceId =
                    getGoldenResourceReference(resource.getId()).getReference();
                return !resource.getId().equals(goldenResourceId);
              }

              return false;
            });

    for (BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();

      Optional<StringType> subject =
          fhirPath.evaluateFirst(resource, "subject.reference", StringType.class);
      if (subject.isPresent()) {
        resource.setProperty("subject", getGoldenResourceReference(subject.get().getValue()));
        continue;
      }

      Optional<StringType> patient =
          fhirPath.evaluateFirst(resource, "patient.reference", StringType.class);
      patient.ifPresent(
          reference ->
              resource.setProperty(
                  "patient", getGoldenResourceReference(patient.get().getValue())));
    }
  }

  private Reference getGoldenResourceReference(String fhirId) {
    return new Reference(
        goldenResources.get(
            fhirId,
            key -> fhirSearchUtil.getGoldenResourceFromId(key, treatPossibleMatchesAsMatches)));
  }
}
