package org.gbif.pipelines.core.interpreters.core;

import static org.gbif.pipelines.core.utils.ModelUtils.extractNullAwareOptValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.dwc.terms.GbifTerm;
import org.gbif.dwc.terms.Term;
import org.gbif.pipelines.core.parsers.vocabulary.VocabularyService;
import org.gbif.pipelines.io.avro.BasicRecord;
import org.gbif.pipelines.io.avro.EventCoreRecord;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.gbif.pipelines.io.avro.VocabularyConcept;
import org.gbif.vocabulary.lookup.LookupConcept;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class VocabularyInterpreter {

  /** {@link DwcTerm#lifeStage} interpretation. */
  public static BiConsumer<ExtendedRecord, BasicRecord> interpretLifeStage(
      VocabularyService vocabularyService) {
    return (er, br) ->
        interpretVocabulary(er, DwcTerm.lifeStage, vocabularyService).ifPresent(br::setLifeStage);
  }

  /** {@link DwcTerm#establishmentMeans} interpretation. */
  public static BiConsumer<ExtendedRecord, BasicRecord> interpretEstablishmentMeans(
      VocabularyService vocabularyService) {
    return (er, br) ->
        interpretVocabulary(er, DwcTerm.establishmentMeans, vocabularyService)
            .ifPresent(br::setEstablishmentMeans);
  }

  /** {@link DwcTerm#degreeOfEstablishment} interpretation. */
  public static BiConsumer<ExtendedRecord, BasicRecord> interpretDegreeOfEstablishment(
      VocabularyService vocabularyService) {
    return (er, br) ->
        interpretVocabulary(er, DwcTerm.degreeOfEstablishment, vocabularyService)
            .ifPresent(br::setDegreeOfEstablishment);
  }

  /** {@link DwcTerm#pathway} interpretation. */
  public static BiConsumer<ExtendedRecord, BasicRecord> interpretPathway(
      VocabularyService vocabularyService) {
    return (er, br) ->
        interpretVocabulary(er, DwcTerm.pathway, vocabularyService).ifPresent(br::setPathway);
  }

  /** {@link DwcTerm#pathway} interpretation. */
  public static BiConsumer<ExtendedRecord, EventCoreRecord> interpretEventType(
      VocabularyService vocabularyService) {
    return (er, ecr) ->
        interpretVocabulary(er, GbifTerm.eventType, vocabularyService).ifPresent(ecr::setEventType);
  }

  /**
   * Extracts the value of vocabulary concept and set
   *
   * @param c to extract the value from
   */
  protected static VocabularyConcept getConcept(LookupConcept c) {

    // we sort the parents starting from the top as in taxonomy
    List<String> parents = new ArrayList<>(c.getParents());
    Collections.reverse(parents);

    // add the concept itself
    parents.add(c.getConcept().getName());

    return VocabularyConcept.newBuilder()
        .setConcept(c.getConcept().getName())
        .setLineage(parents)
        .build();
  }

  /** {@link DwcTerm#lifeStage} interpretation. */
  private static Optional<VocabularyConcept> interpretVocabulary(
      ExtendedRecord er, Term term, VocabularyService vocabularyService) {

    if (vocabularyService != null) {
      return vocabularyService
          .get(term)
          .flatMap(lookup -> extractNullAwareOptValue(er, term).flatMap(lookup::lookup))
          .map(VocabularyInterpreter::getConcept);
    }
    return Optional.empty();
  }
}
