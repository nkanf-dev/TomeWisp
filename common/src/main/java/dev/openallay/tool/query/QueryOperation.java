package dev.openallay.tool.query;

import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import java.util.List;

/** Closed, data-only operations over one detached OpenAllay virtual dataset. */
@ToolDescription("One closed query stage. Only fields declared by the selected dataset may be used.")
public record QueryOperation(
        @ToolDescription("Stage operation") Op op,
        @ToolDescription("RFC 6901 JSON Pointer returned by schema discovery; used by FILTER, SORT, GROUP, AGGREGATE, or EXPAND")
                @ToolOptional String field,
        @ToolDescription("Comparison used by FILTER") @ToolOptional Operator operator,
        @ToolDescription("Literal value used by FILTER or SEARCH") @ToolOptional String value,
        @ToolDescription("Fields retained by SELECT") @ToolOptional List<String> fields,
        @ToolDescription("Sort direction; defaults to ASC") @ToolOptional Direction direction,
        @ToolDescription("Aggregate function") @ToolOptional Aggregate aggregate,
        @ToolDescription("Optional grouping field for AGGREGATE") @ToolOptional String groupBy,
        @ToolDescription("Number of rows retained by TAKE") @ToolOptional Integer count) {

    public enum Op { SEARCH, FILTER, SELECT, SORT, GROUP, AGGREGATE, EXPAND, TAKE }
    public enum Operator { EQ, NE, CONTAINS, EXISTS, GT, GTE, LT, LTE }
    public enum Direction { ASC, DESC }
    public enum Aggregate { COUNT, MIN, MAX, SUM, AVG }

    public QueryOperation {
        fields = fields == null ? null : List.copyOf(fields);
    }
}
