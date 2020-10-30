package au.org.ala.pipelines.interpreters;

import static org.junit.Assert.assertArrayEquals;

import java.util.HashMap;
import java.util.Map;
import org.gbif.dwc.terms.DwcTerm;
import org.gbif.pipelines.io.avro.BasicRecord;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.junit.Test;

public class ALABasicInterpreterTest {
  private static final String ID = "777";

  @Test
  public void interpretRecordedByEmtyOrNullTest() {
    // State
    Map<String, String> coreMap = new HashMap<>();
    coreMap.put(
        DwcTerm.recordedBy.qualifiedName(),
        "Hegedus, Ms Alexandra; hegedus@ams.org.au - Australian Museum - Science; Field, Ross P.; Dr NL Kirby");
    ExtendedRecord er = ExtendedRecord.newBuilder().setId(ID).setCoreTerms(coreMap).build();
    BasicRecord br = BasicRecord.newBuilder().setId(ID).build();

    // When
    ALABasicInterpreter.interpretRecordedBy(er, br);

    // Should
    assertArrayEquals(
        new String[] {
          "Hegedus, Alexandra",
          "hegedus@ams.org.au",
          "Australian Museum",
          "Science",
          "Field, P. Ross",
          "Kirby, N.L."
        },
        br.getRecordedBy().toArray());
  }
}
