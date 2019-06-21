package org.gbif.pipelines.ingest.utils;

import org.gbif.api.util.VocabularyUtils;
import org.gbif.api.vocabulary.Country;
import org.gbif.dwc.terms.DcTerm;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.terms.TermFactory;
import org.gbif.occurrence.download.hive.HiveColumns;
import org.gbif.occurrence.download.hive.HiveDataTypes;
import org.gbif.occurrence.download.hive.OccurrenceHDFSTableDefinition;
import org.gbif.occurrence.download.hive.Terms;
import org.gbif.pipelines.core.utils.TemporalUtils;
import org.gbif.pipelines.io.avro.BasicRecord;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.gbif.pipelines.io.avro.IssueRecord;
import org.gbif.pipelines.io.avro.LocationRecord;
import org.gbif.pipelines.io.avro.MetadataRecord;
import org.gbif.pipelines.io.avro.Multimedia;
import org.gbif.pipelines.io.avro.MultimediaRecord;
import org.gbif.pipelines.io.avro.OccurrenceHdfsRecord;
import org.gbif.pipelines.io.avro.TaxonRecord;
import org.gbif.pipelines.io.avro.TemporalRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Strings;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.commons.beanutils.PropertyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("FallThrough")
@Slf4j
@Builder
public class AvroHdfsView {


  private static Map<Class<? extends SpecificRecordBase>, BiConsumer<OccurrenceHdfsRecord,SpecificRecordBase>>
    converters;

  static {
    converters = new HashMap<>();
    converters.put(ExtendedRecord.class, extendedRecordMapper());
    converters.put(BasicRecord.class, basicRecordMapper());
    converters.put(LocationRecord.class, locationMapper());
    converters.put(TaxonRecord.class, taxonMapper());
    converters.put(MetadataRecord.class, metadataMapper());
    converters.put(MultimediaRecord.class, multimediaMapper());
  }

  static final Function<TemporalAccessor, Date> TEMPORAL_TO_DATE =
    temporalAccessor -> {
      if (temporalAccessor instanceof ZonedDateTime) {
        return Date.from(((ZonedDateTime)temporalAccessor).toInstant());
      } else if (temporalAccessor instanceof LocalDateTime) {
        return Date.from(((LocalDateTime)temporalAccessor).toInstant(ZoneOffset.UTC));
      } else if (temporalAccessor instanceof LocalDate) {
        return Date.from((((LocalDate)temporalAccessor).atStartOfDay()).toInstant(ZoneOffset.UTC));
      } else if (temporalAccessor instanceof YearMonth) {
        return Date.from((((YearMonth)temporalAccessor).atDay(1)).atStartOfDay().toInstant(ZoneOffset.UTC));
      } else if (temporalAccessor instanceof Year) {
        return Date.from((((Year)temporalAccessor).atDay(1)).atStartOfDay().toInstant(ZoneOffset.UTC));
      } else {
        return null;
      }
    };

  private static final DateTimeFormatter FORMATTER =
    DateTimeFormatter.ofPattern(
      "[yyyy-MM-dd'T'HH:mm:ss.SSS XXX][yyyy-MM-dd'T'HH:mm:ss.SSSXXX][yyyy-MM-dd'T'HH:mm:ss.SSS]"
      + "[yyyy-MM-dd'T'HH:mm:ss][yyyy-MM-dd'T'HH:mm:ss XXX][yyyy-MM-dd'T'HH:mm:ssXXX][yyyy-MM-dd'T'HH:mm:ss]"
      + "[yyyy-MM-dd'T'HH:mm][yyyy-MM-dd][yyyy-MM][yyyy]")
      .withZone(ZoneId.of("UTC"));

  private static final Function<String, Date> STRING_TO_DATE =
    dateAsString -> {
      if (Strings.isNullOrEmpty(dateAsString)) {
        return null;
      }

      // parse string
      TemporalAccessor temporalAccessor = FORMATTER.parseBest(dateAsString,
                                                              ZonedDateTime::from,
                                                              LocalDateTime::from,
                                                              LocalDate::from,
                                                              YearMonth::from,
                                                              Year::from);
      return TEMPORAL_TO_DATE.apply(temporalAccessor);
    };

  private static final  TermFactory TERM_FACTORY =  TermFactory.instance();


  private static final Logger LOG = LoggerFactory.getLogger(AvroHdfsView.class);

  private static void addIssues(IssueRecord issueRecord, OccurrenceHdfsRecord hr) {
    if (Objects.nonNull(issueRecord) && Objects.nonNull(issueRecord.getIssueList())) {
      List<String> currentIssues =  hr.getIssue();
      currentIssues.addAll(issueRecord.getIssueList());
      hr.setIssue(currentIssues);
    }
  }

  public static Schema avroDefinition() {
    SchemaBuilder.FieldAssembler<Schema> builder = SchemaBuilder
      .record("OccurrenceHdfsRecord")
      .namespace("org.gbif.pipelines.io.avro").fields();
    OccurrenceHDFSTableDefinition.definition().forEach(initializableField -> {
      switch (initializableField.getHiveDataType()) {
        case HiveDataTypes.TYPE_INT:
          builder.name(initializableField.getHiveField()).type().nullable().intType().noDefault();
          break;
        case HiveDataTypes.TYPE_BIGINT:
          if (initializableField.getHiveField().equalsIgnoreCase("gbifid")) {
            builder.name(initializableField.getHiveField()).type().longType().noDefault();
          } else {
            builder.name(initializableField.getHiveField()).type().nullable().longType().noDefault();
          }
          break;
        case HiveDataTypes.TYPE_BOOLEAN:
          builder.name(initializableField.getHiveField()).type().nullable().booleanType().noDefault();
          break;
        case HiveDataTypes.TYPE_DOUBLE:
          builder.name(initializableField.getHiveField()).type().nullable().doubleType().noDefault();
          break;
        case HiveDataTypes.TYPE_ARRAY_STRING:
          builder.name(initializableField.getHiveField()).type().nullable().array().items().nullable().stringType().noDefault();
          break;
        default:
          builder.name(initializableField.getHiveField()).type().nullable().stringType().noDefault();
          break;
      }
    });
    return builder.endRecord();
  }


  private static BiConsumer<OccurrenceHdfsRecord,SpecificRecordBase> locationMapper() {
    return (hr, sr) -> {
      LocationRecord lr = (LocationRecord)sr;
      hr.setCountrycode(lr.getCountryCode());
      hr.setContinent(lr.getContinent());
      hr.setDecimallatitude(lr.getDecimalLatitude());
      hr.setDecimallongitude(lr.getDecimalLongitude());
      hr.setCoordinateprecision(lr.getCoordinatePrecision());
      hr.setCoordinateuncertaintyinmeters(lr.getCoordinateUncertaintyInMeters());
      hr.setDepth(lr.getDepth());
      hr.setDepthaccuracy(lr.getDepthAccuracy());
      hr.setElevation(lr.getElevation());
      hr.setElevationaccuracy(lr.getElevationAccuracy());
      if (Objects.nonNull(lr.getMaximumDistanceAboveSurfaceInMeters())) {
        hr.setMaximumdistanceabovesurfaceinmeters(lr.getMaximumDistanceAboveSurfaceInMeters()
                                                                      .toString());
      }
      if (Objects.nonNull(lr.getMinimumDistanceAboveSurfaceInMeters())) {
        hr.setMinimumdistanceabovesurfaceinmeters(lr.getMinimumDistanceAboveSurfaceInMeters()
                                                                      .toString());
      }
      hr.setStateprovince(lr.getStateProvince());
      hr.setWaterbody(lr.getWaterBody());
      hr.setHascoordinate(lr.getHasCoordinate());
      hr.setHasgeospatialissues(lr.getHasGeospatialIssue());
      hr.setRepatriated(lr.getRepatriated());
      addIssues(lr.getIssues(), hr);
    };
  }

  private static BiConsumer<OccurrenceHdfsRecord, SpecificRecordBase> metadataMapper() {
    return (hr, sr) -> {
      MetadataRecord mr = (MetadataRecord)sr;
      hr.setCrawlid(mr.getCrawlId());
      hr.setDatasetkey(mr.getDatasetKey());
      hr.setPublishingcountry(mr.getDatasetPublishingCountry());
      hr.setDatasetname(mr.getDatasetTitle());
      hr.setInstallationkey(mr.getInstallationKey());
      hr.setLicense(mr.getLicense());
      hr.setProtocol(mr.getProtocol());
      hr.setNetworkkey(mr.getNetworkKeys());
      hr.setPublisher(mr.getPublisherTitle());
      hr.setPublishingorgkey(mr.getPublishingOrganizationKey());
      hr.setPublishingcountry(mr.getDatasetPublishingCountry());
      hr.setLastcrawled(mr.getLastCrawled());
      addIssues(mr.getIssues(), hr);
    };
  }

  private static BiConsumer<OccurrenceHdfsRecord, SpecificRecordBase> temporalMapper() {
    return (hr, sr) -> {
      TemporalRecord tr = (TemporalRecord)sr;
      Optional.ofNullable(tr.getDateIdentified()).map(STRING_TO_DATE).ifPresent(date -> hr.setDateidentified(date.getTime()));
      hr.setDay(tr.getDay());
      hr.setMonth(tr.getMonth());
      hr.setYear(tr.getYear());

      if (Objects.nonNull(tr.getStartDayOfYear())) {
        hr.setStartdayofyear(tr.getStartDayOfYear().toString());
      }

      if (Objects.nonNull(tr.getEndDayOfYear())) {
        hr.setEnddayofyear(tr.getEndDayOfYear().toString());
      }

      TemporalUtils.getTemporal(tr.getYear(), tr.getMonth(), tr.getDay())
        .map(TEMPORAL_TO_DATE)
        .ifPresent(eventDate -> hr.setEventdate(eventDate.getTime()));
      addIssues(tr.getIssues(), hr);
    };
  }

  private static BiConsumer<OccurrenceHdfsRecord, SpecificRecordBase> taxonMapper() {
    return (hr, sr) -> {
      TaxonRecord tr = (TaxonRecord)sr;
      hr.setTaxonkey(tr.getUsage().getKey());
      if (Objects.nonNull(tr.getClassification())) {
        tr.getClassification().forEach(rankedName -> {
          switch (rankedName.getRank()) {
            case KINGDOM:
              hr.setKingdom(rankedName.getName());
              hr.setTaxonkey(rankedName.getKey());
              break;
            case PHYLUM:
              hr.setPhylum(rankedName.getName());
              hr.setPhylumkey(rankedName.getKey());
              break;
            case CLASS:
              hr.setClass$(rankedName.getName());
              hr.setClasskey(rankedName.getKey());
              break;
            case ORDER:
              hr.setOrder(rankedName.getName());
              hr.setOrderkey(rankedName.getKey());
              break;
            case FAMILY:
              hr.setFamily(rankedName.getName());
              hr.setFamilykey(rankedName.getKey());
              break;
            case GENUS:
              hr.setGenus(rankedName.getName());
              hr.setGenuskey(rankedName.getKey());
              break;
            case SUBGENUS:
              hr.setSubgenus(rankedName.getName());
              hr.setSubgenuskey(rankedName.getKey());
              break;
            case SPECIES:
              hr.setSpecies(rankedName.getName());
              hr.setSpecieskey(rankedName.getKey());
              break;
          }
        });
      }

      if (Objects.nonNull(tr.getAcceptedUsage())) {
        hr.setAcceptedscientificname(tr.getAcceptedUsage().getName());
        hr.setAcceptednameusageid(tr.getAcceptedUsage().getKey().toString());
        if (Objects.nonNull(tr.getAcceptedUsage().getKey())) {
          hr.setAcceptedtaxonkey(tr.getAcceptedUsage().getKey());
        }
      } else if (Objects.nonNull(tr.getUsage())) {
        hr.setAcceptedtaxonkey(tr.getUsage().getKey());
        hr.setAcceptedscientificname(tr.getUsage().getName());
      }

      if (Objects.nonNull(tr.getUsageParsedName())) {
        hr.setGenericname(Objects.nonNull(tr.getUsageParsedName().getGenus())
                                              ? tr.getUsageParsedName().getGenus()
                                              : tr.getUsageParsedName().getUninomial());
        hr.setSpecificepithet(tr.getUsageParsedName().getSpecificEpithet());
        hr.setInfraspecificepithet(tr.getUsageParsedName().getInfraspecificEpithet());
      }
      addIssues(tr.getIssues(), hr);
    };
  }

  private static BiConsumer<OccurrenceHdfsRecord, SpecificRecordBase> basicRecordMapper() {
    return (hr, sr) -> {
      BasicRecord br = (BasicRecord)sr;
      hr.setBasisofrecord(br.getBasisOfRecord());
      hr.setEstablishmentmeans(br.getEstablishmentMeans());
      hr.setIndividualcount(br.getIndividualCount());
      hr.setLifestage(br.getLifeStage());
      hr.setReferences(br.getReferences());
      hr.setSex(br.getSex());
      hr.setTypestatus(br.getTypeStatus());
      hr.setVTypestatus(br.getTypifiedName());
      if (Objects.nonNull(br.getCreated())) {
        hr.setLastcrawled(br.getCreated());
        hr.setLastinterpreted(br.getCreated());
        hr.setLastinterpreted(br.getCreated());
      }
      hr.setCreated(new Date(br.getCreated()).toString());
      addIssues(br.getIssues(), hr);
    };
  }

  private static void setHdfsRecordField(OccurrenceHdfsRecord occurrenceHdfsRecord, Schema.Field avroField, String fieldName, String value) {
    try {
      Schema.Type fieldType = avroField.schema().getType();
      if (Schema.Type.UNION == avroField.schema().getType()) {
        fieldType = avroField.schema().getTypes().get(0).getType();
      }
      switch (fieldType) {
        case INT:
          PropertyUtils.setProperty(occurrenceHdfsRecord, fieldName, Integer.valueOf(value));
          break;
        case LONG:
          PropertyUtils.setProperty(occurrenceHdfsRecord, fieldName, Long.valueOf(value));
          break;
        case BOOLEAN:
          PropertyUtils.setProperty(occurrenceHdfsRecord, fieldName, Boolean.valueOf(value));
          break;
        case DOUBLE:
          PropertyUtils.setProperty(occurrenceHdfsRecord, fieldName, Double.valueOf(value));
          break;
        case FLOAT:
          PropertyUtils.setProperty(occurrenceHdfsRecord, fieldName, Float.valueOf(value));
          break;
        default:
          PropertyUtils.setProperty(occurrenceHdfsRecord, fieldName, value);
          break;
      }
    } catch (Exception ex) {
      LOG.error("Ignoring error setting field {}", avroField, ex);
    }
  }
  private static BiConsumer<OccurrenceHdfsRecord, SpecificRecordBase> extendedRecordMapper() {
    return (hr, sr) -> {
      ExtendedRecord er = (ExtendedRecord)sr;
      hr.setGbifid(Long.parseLong(er.getId()));
      er.getCoreTerms().forEach((k, v) -> Optional.ofNullable(TERM_FACTORY.findTerm(k)).ifPresent(term -> {

        if (Terms.verbatimTerms().contains(term)) {
          Optional.ofNullable(AvroHdfsView.verbatimSchemaField(term)).ifPresent(field -> {
            String verbatimField = "V" + field.name().substring(2, 3).toUpperCase() + field.name().substring(3);
            setHdfsRecordField(hr, field, verbatimField, v);
          });
        }
        Optional.ofNullable(interpretedSchemaField(term)).ifPresent(field -> {
          String interpretedFieldname = field.name();
          if (DcTerm.abstract_ == term) {
            interpretedFieldname = "abstract$";
          } else if (DwcTerm.class_ == term) {
            interpretedFieldname = "class$";
          } else if (DcTerm.format == term) {
            interpretedFieldname = interpretedFieldname.substring(0, interpretedFieldname.length() - 1);
          }
          setHdfsRecordField(hr, field, interpretedFieldname, v);
        });
      }));
    };
  }

  public static OccurrenceHdfsRecord toOccurrenceHdfsRecord(SpecificRecordBase...records) {
    OccurrenceHdfsRecord occurrenceHdfsRecord = new OccurrenceHdfsRecord();
    occurrenceHdfsRecord.setIssue(new ArrayList<>());
    for (SpecificRecordBase record : records) {
      Optional.ofNullable(converters.get(record.getClass())).ifPresent(consumer -> consumer.accept(occurrenceHdfsRecord,record));
    }
    return occurrenceHdfsRecord;
  }

  private static BiConsumer<OccurrenceHdfsRecord, SpecificRecordBase> multimediaMapper() {
    return (hr, sr) -> {
      MultimediaRecord mr = (MultimediaRecord)sr;
      // media types
      List<String> mediaTypes = mr.getMultimediaItems().stream()
        .filter(i -> !Strings.isNullOrEmpty(i.getType()))
        .map(Multimedia::getType)
        .map(TextNode::valueOf)
        .map(TextNode::asText)
        .collect(Collectors.toList());

      hr.setMediatype(mediaTypes);
    };
  }

  private static Schema.Field verbatimSchemaField(Term term) {
    return OccurrenceHdfsRecord.SCHEMA$.getField("v_" + HiveColumns.columnFor(term));
  }

  private static Schema.Field interpretedSchemaField(Term term) {
    return OccurrenceHdfsRecord.SCHEMA$.getField(HiveColumns.columnFor(term));
  }

  public static void main(String[] args) throws Exception {
    Schema schema = avroDefinition();
    ExtendedRecord extendedRecord = new ExtendedRecord();
    extendedRecord.setId("1");
    HashMap<String,String> terms = new HashMap<>();
    terms.put(DwcTerm.decimalLongitude.simpleName(), "77.2");
    terms.put(DwcTerm.decimalLatitude.simpleName(), "37.2");
    terms.put(DwcTerm.countryCode.simpleName(), Country.DENMARK.getIso2LetterCode());
    terms.put(DcTerm.abstract_.simpleName(), "abbbbbssssssstraaacttt");
    terms.put(DwcTerm.class_.simpleName(), "classsss");
    terms.put(DcTerm.format.simpleName(), "format");
    extendedRecord.setCoreTerms(terms);
    OccurrenceHdfsRecord occurrenceHdfsRecord = toOccurrenceHdfsRecord(extendedRecord);
    System.out.println(occurrenceHdfsRecord);
  }

}
