package org.gbif.pipelines.core.functions.interpretation;

import org.gbif.api.vocabulary.Continent;
import org.gbif.common.parsers.ContinentParser;
import org.gbif.common.parsers.core.ParseResult;
import org.gbif.pipelines.core.functions.interpretation.error.Issue;
import org.gbif.pipelines.core.functions.interpretation.error.IssueType;
import org.gbif.pipelines.core.functions.interpretation.error.Lineage;
import org.gbif.pipelines.core.functions.interpretation.error.LineageType;

import java.util.Collections;
import java.util.List;

class ContinentInterpreter implements Interpretable<String> {

  @Override
  public String interpret(String input) throws InterpretationException {
    final ParseResult<Continent> parse = ContinentParser.getInstance().parse(input);
    if (parse.isSuccessful()) {
      return (parse.getPayload().getTitle());
    } else {
      List<Issue> issues = Collections.singletonList(Issue.newBuilder()
                                                       .setRemark("Could not parse continent because "
                                                                  + (parse.getError() != null ? parse.getError()
                                                         .getMessage() : " is null"))
                                                       .setIssueType(IssueType.PARSE_ERROR)
                                                       .build());
      List<Lineage> lineages = Collections.singletonList(Lineage.newBuilder()
                                                           .setRemark(
                                                             "Could not parse the continent or invalid value setting it to null")
                                                           .setLineageType(LineageType.SET_TO_NULL)
                                                           .build());
      throw new InterpretationException(issues, lineages, null);
    }
  }
}
