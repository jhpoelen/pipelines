package au.org.ala.pipelines.beam;

import static au.org.ala.pipelines.beam.ALAUUIDMintingPipeline.UNIQUE_COMPOSITE_KEY_JOIN_CHAR;
import static au.org.ala.pipelines.beam.ALAUUIDMintingPipeline.getDwcTerm;
import static java.util.stream.Collectors.joining;

import au.org.ala.kvs.ALAPipelinesConfig;
import au.org.ala.kvs.ALAPipelinesConfigFactory;
import au.org.ala.kvs.cache.ALAAttributionKVStoreFactory;
import au.org.ala.kvs.client.ALACollectoryMetadata;
import au.org.ala.pipelines.common.ALARecordTypes;
import au.org.ala.pipelines.options.UUIDPipelineOptions;
import au.org.ala.pipelines.util.VersionInfo;
import au.org.ala.utils.CombinedYamlConfiguration;
import au.org.ala.utils.ValidationResult;
import au.org.ala.utils.ValidationUtils;
import java.time.LocalDateTime;
import java.util.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.file.CodecFactory;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.fs.EmptyMatchTreatment;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.commons.lang.StringUtils;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.terms.UnknownTerm;
import org.gbif.kvs.KeyValueStore;
import org.gbif.pipelines.common.beam.metrics.MetricsHandler;
import org.gbif.pipelines.common.beam.options.PipelinesOptionsFactory;
import org.gbif.pipelines.core.utils.ModelUtils;
import org.gbif.pipelines.io.avro.ALAUUIDRecord;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.slf4j.MDC;

/**
 * Pipeline that is only used during migration. The beam pipeline will:
 *
 * <ul>
 *   <li>Load a CSV export for a data set that contains "uuid, firstLoadDate". This is generated by
 *       a spark job in a previous step.
 *   <li>Retrieve the UUID from the Verbatim.avro (as we are using the UUID in the migrated DwCAs)
 *   <li>Construct the unique key using the unique terms in the collector (with support for
 *       stripping spaces, and default values)
 *   <li>Generate the UUID AVRO records.
 * </ul>
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UUIDMigrationPipeline {

  private static final CodecFactory BASE_CODEC = CodecFactory.snappyCodec();
  public static final String IDENTIFIERS_DIR = "identifiers";

  public static void main(String[] args) throws Exception {
    VersionInfo.print();
    String[] combinedArgs = new CombinedYamlConfiguration(args).toArgs("general", "uuid");
    UUIDPipelineOptions options =
        PipelinesOptionsFactory.create(UUIDPipelineOptions.class, combinedArgs);
    options.setMetaFileName(ValidationUtils.UUID_METRICS);
    MDC.put("datasetId", options.getDatasetId());
    MDC.put("attempt", options.getAttempt().toString());
    MDC.put("step", "UUID_MIGRATION");
    PipelinesOptionsFactory.registerHdfs(options);
    run(options);
    System.exit(0);
  }

  public static void run(UUIDPipelineOptions options) throws Exception {

    log.info("Pipeline has been started - {}", LocalDateTime.now());
    boolean verbatimAvroAvailable = ValidationUtils.isVerbatimAvroAvailable(options);
    if (!verbatimAvroAvailable) {
      log.warn("Verbatim AVRO not available for {}", options.getDatasetId());
      return;
    }

    // delete metrics if it exists
    MetricsHandler.deleteMetricsFile(options);

    // run the validation pipeline
    log.info("Running validation pipeline");
    ALAUUIDValidationPipeline.run(options);

    // check validation results
    ValidationResult validationResult = ValidationUtils.checkValidationFile(options);

    log.info("Validation result: {} ", validationResult.getMessage());
    if (!validationResult.getValid()) {
      log.error(
          "Unable to run UUID Migration pipeline for dataset. Please check validation file: "
              + ValidationUtils.getValidationFilePath(options));
      return;
    }

    ALAPipelinesConfig config =
        ALAPipelinesConfigFactory.getInstance(
                options.getHdfsSiteConfig(), options.getCoreSiteConfig(), options.getProperties())
            .get();

    // create key value store for data resource metadata
    ALACollectoryMetadata collectoryMetadata;
    try (KeyValueStore<String, ALACollectoryMetadata> dataResourceKvStore =
        ALAAttributionKVStoreFactory.create(config)) {

      // lookup collectory metadata for this data resource
      collectoryMetadata = dataResourceKvStore.get(options.getDatasetId());
    } catch (RuntimeException e) {
      log.error("Unable to retrieve metadata for " + options.getDatasetId(), e);
      collectoryMetadata = ALACollectoryMetadata.EMPTY;
    }

    // get unique terms
    List<String> uniqueTerms = Collections.emptyList();
    Boolean stripSpaces = false;
    Map<String, String> defaultValues = null;

    // construct unique list of darwin core terms
    if (collectoryMetadata.getConnectionParameters() != null) {
      uniqueTerms = collectoryMetadata.getConnectionParameters().getTermsForUniqueKey();
      stripSpaces = collectoryMetadata.getConnectionParameters().getStrip();
      defaultValues = collectoryMetadata.getDefaultDarwinCoreValues();
      if (uniqueTerms == null) {
        uniqueTerms = Collections.emptyList();
      }

      if (uniqueTerms.isEmpty()) {
        log.error(
            "Unable to proceed, No unique terms specified for dataset: " + options.getDatasetId());
      } else {
        log.info("Unique terms specified: " + String.join(",", uniqueTerms));
      }
    }

    String defaultValuesAsString = null;
    if (defaultValues != null) {
      defaultValuesAsString =
          defaultValues.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(joining(","));
    }

    log.info(
        "Connection param: uniqueTerms {};  stripSpaces {};  defaultValues {}",
        String.join(",", uniqueTerms),
        stripSpaces,
        defaultValuesAsString);

    final List<Term> uniqueDwcTerms = new ArrayList<>();
    for (String uniqueTerm : uniqueTerms) {
      Optional<DwcTerm> dwcTerm = getDwcTerm(uniqueTerm);
      if (dwcTerm.isPresent()) {
        uniqueDwcTerms.add(dwcTerm.get());
      } else {
        // create a UnknownTerm for non DWC fields
        uniqueDwcTerms.add(UnknownTerm.build(uniqueTerm.trim()));
      }
    }

    // create pipeline
    Pipeline p = Pipeline.create(options);

    String csvPath =
        String.join(
            "/",
            options.getTargetPath(),
            options.getDatasetId().trim(),
            options.getAttempt().toString(),
            "migration",
            "*.csv");

    // load CSV - UUID, firstLoadedDate map
    PCollection<KV<String, String>> firstLoaded =
        p.apply(TextIO.read().from(csvPath).withEmptyMatchTreatment(EmptyMatchTreatment.ALLOW))
            .apply(
                Filter.by(
                    (SerializableFunction<String, Boolean>) input -> input.split(",").length > 1))
            .apply(
                "Format results",
                MapElements.into(
                        TypeDescriptors.kvs(TypeDescriptors.strings(), TypeDescriptors.strings()))
                    .via(line -> KV.of(line.split(",")[0], line.split(",")[1])));

    // load verbatim AVRO - keyed on ID  (UUID)
    PCollection<KV<String, ExtendedRecord>> records =
        p.apply(
                AvroIO.read(ExtendedRecord.class)
                    .from(
                        String.join(
                            "/",
                            options.getTargetPath(),
                            options.getDatasetId().trim(),
                            options.getAttempt().toString(),
                            "verbatim.avro"))
                    .withEmptyMatchTreatment(EmptyMatchTreatment.ALLOW))
            .apply(
                MapElements.via(
                    new SimpleFunction<ExtendedRecord, KV<String, ExtendedRecord>>() {
                      @Override
                      public KV<String, ExtendedRecord> apply(ExtendedRecord input) {
                        return KV.of(input.getId(), input);
                      }
                    }));

    // join
    log.info("Create join collection");
    PCollection<KV<String, KV<ExtendedRecord, String>>> joinedPcollection =
        org.apache.beam.sdk.extensions.joinlibrary.Join.leftOuterJoin(records, firstLoaded, "");

    final String datasetID = options.getDatasetId();
    final Boolean stripSpacesFinal = stripSpaces != null && stripSpaces;
    final Map<String, String> defaultValuesFinal =
        defaultValues == null ? Collections.emptyMap() : defaultValues;

    PCollection<ALAUUIDRecord> uuidsRecords =
        joinedPcollection.apply(
            ParDo.of(
                new DoFn<KV<String, KV<ExtendedRecord, String>>, ALAUUIDRecord>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<String, KV<ExtendedRecord, String>> source,
                      OutputReceiver<ALAUUIDRecord> out,
                      ProcessContext c) {

                    Long firstLoadedDate = null;

                    try {
                      String date = source.getValue().getValue();
                      firstLoadedDate = Long.parseLong(date);
                    } catch (NumberFormatException ne) {
                      //
                    }

                    if (firstLoadedDate == null) {
                      firstLoadedDate = System.currentTimeMillis();
                    }

                    ALAUUIDRecord record =
                        ALAUUIDRecord.newBuilder()
                            .setUuid(source.getKey())
                            .setId(source.getKey())
                            .setUniqueKey(
                                generateUniqueKey(
                                    datasetID,
                                    source.getValue().getKey(),
                                    uniqueDwcTerms,
                                    defaultValuesFinal,
                                    stripSpacesFinal,
                                    true))
                            .setFirstLoaded(firstLoadedDate) // convert to long
                            .build();

                    out.output(record);
                  }
                }));

    // write the AVRO
    // build the directory path for existing identifiers
    String alaRecordDirectoryPath =
        String.join(
            "/",
            options.getTargetPath(),
            options.getDatasetId().trim(),
            options.getAttempt().toString(),
            IDENTIFIERS_DIR,
            ALARecordTypes.ALA_UUID.name().toLowerCase());

    log.info("Output path {}", alaRecordDirectoryPath);

    uuidsRecords.apply(
        AvroIO.write(ALAUUIDRecord.class)
            .to(alaRecordDirectoryPath + "/interpret")
            .withSuffix(".avro")
            .withCodec(BASE_CODEC));

    PipelineResult result = p.run();
    result.waitUntilFinish();

    log.info("Writing metrics.....");
    MetricsHandler.saveCountersToTargetPathFile(options, result.metrics());
    log.info("Writing metrics written.");

    log.info("Finished. Output written to  {}", alaRecordDirectoryPath);
  }

  /**
   * Generate a unique key based on the darwin core fields. This works the same was unique keys
   * where generated in the biocache-store. This is repeated to maintain backwards compatibility
   * with existing data holdings.
   *
   * @param datasetID DatasetID for this key
   * @param source ExtendedRecord to source values from
   * @param uniqueTerms Terms to use to contruct the key
   * @param stripSpaces Whether to strip internal spaces the strings in the key
   * @param errorOnEmpty Whether to thrown an error on empty unique term values or return an empty
   *     key
   */
  public static String generateUniqueKey(
      String datasetID,
      ExtendedRecord source,
      List<Term> uniqueTerms,
      Map<String, String> defaultValues,
      boolean stripSpaces,
      Boolean errorOnEmpty) {

    List<String> uniqueValues = new ArrayList<>();
    boolean allUniqueValuesAreEmpty = true;
    for (Term term : uniqueTerms) {
      String value = ModelUtils.extractNullAwareValue(source, term);

      // if null or empty, check default values...
      if (value == null
          || StringUtils.trimToNull(value) == null
              && defaultValues.containsKey(term.simpleName())) {
        value = defaultValues.get(term.simpleName());
      }

      if (value != null && StringUtils.trimToNull(value) != null) {
        // we have a term with a value
        allUniqueValuesAreEmpty = false;

        // if configured, strip spaces from the keys
        if (stripSpaces) {
          uniqueValues.add(value.replaceAll("\\s", ""));
        } else {
          uniqueValues.add(value.trim());
        }
      }
    }

    if (allUniqueValuesAreEmpty) {
      if (errorOnEmpty) {
        String termList = uniqueTerms.stream().map(Term::simpleName).collect(joining(","));
        String errorMessage =
            String.format(
                "Unable to load dataset %s, All supplied unique terms (%s) where empty record with ID %s",
                datasetID, termList, source.getId());

        log.warn(errorMessage);
        throw new RuntimeException(errorMessage);
      } else {
        return "";
      }
    }

    // add the datasetID
    uniqueValues.add(0, datasetID);

    // create the unique key
    return String.join(UNIQUE_COMPOSITE_KEY_JOIN_CHAR, uniqueValues);
  }
}
