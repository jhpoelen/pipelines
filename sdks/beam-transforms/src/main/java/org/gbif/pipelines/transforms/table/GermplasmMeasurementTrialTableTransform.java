package org.gbif.pipelines.transforms.table;

import static org.gbif.pipelines.common.PipelinesVariables.Metrics.MEASUREMENT_TRIAL_TABLE_RECORDS_COUNT;

import java.io.Serializable;
import lombok.Builder;
import lombok.NonNull;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;
import org.gbif.pipelines.common.PipelinesVariables;
import org.gbif.pipelines.core.converters.GermplasmMeasurementTrialTableConverter;
import org.gbif.pipelines.io.avro.BasicRecord;
import org.gbif.pipelines.io.avro.ExtendedRecord;
import org.gbif.pipelines.io.avro.extension.GermplasmMeasurementTrialTable;
import org.gbif.pipelines.transforms.Transform;

@SuppressWarnings("ConstantConditions")
@Builder
public class GermplasmMeasurementTrialTableTransform implements Serializable {

  @NonNull private final TupleTag<ExtendedRecord> extendedRecordTag;
  @NonNull private final TupleTag<BasicRecord> basicRecordTag;

  public ParDo.SingleOutput<KV<String, CoGbkResult>, GermplasmMeasurementTrialTable> converter() {
    DoFn<KV<String, CoGbkResult>, GermplasmMeasurementTrialTable> fn =
        new DoFn<KV<String, CoGbkResult>, GermplasmMeasurementTrialTable>() {

          private final Counter counter =
              Metrics.counter(
                  GermplasmMeasurementTrialTableTransform.class,
                  MEASUREMENT_TRIAL_TABLE_RECORDS_COUNT);

          @ProcessElement
          public void processElement(ProcessContext c) {
            CoGbkResult v = c.element().getValue();
            String k = c.element().getKey();

            ExtendedRecord er =
                v.getOnly(extendedRecordTag, ExtendedRecord.newBuilder().setId(k).build());

            BasicRecord br = v.getOnly(basicRecordTag, BasicRecord.newBuilder().setId(k).build());

            GermplasmMeasurementTrialTableConverter.convert(br, er)
                .ifPresent(
                    record -> {
                      c.output(record);
                      counter.inc();
                    });
          }
        };
    return ParDo.of(fn);
  }

  public AvroIO.Write<GermplasmMeasurementTrialTable> write(String toPath, Integer numShards) {
    AvroIO.Write<GermplasmMeasurementTrialTable> write =
        AvroIO.write(GermplasmMeasurementTrialTable.class)
            .to(toPath)
            .withSuffix(PipelinesVariables.Pipeline.AVRO_EXTENSION)
            .withCodec(Transform.getBaseCodec());

    if (numShards == null || numShards <= 0) {
      return write;
    } else {
      int shards = -Math.floorDiv(-numShards, 2);
      return write.withNumShards(shards);
    }
  }
}
