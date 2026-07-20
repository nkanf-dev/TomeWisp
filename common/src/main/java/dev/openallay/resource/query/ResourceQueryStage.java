package dev.openallay.resource.query;

import dev.openallay.resource.vfs.ResourceValue;
import java.util.List;
import java.util.Objects;

/** Closed, immutable relational stages over VFS records. */
public sealed interface ResourceQueryStage permits ResourceQueryStage.Search,
        ResourceQueryStage.Filter, ResourceQueryStage.Select, ResourceQueryStage.Sort,
        ResourceQueryStage.Group, ResourceQueryStage.Aggregate, ResourceQueryStage.Expand,
        ResourceQueryStage.Take, ResourceQueryStage.Follow {

    enum Operator { EQ, NE, CONTAINS, EXISTS, GT, GTE, LT, LTE }
    enum Direction { ASC, DESC }
    enum AggregateFunction { COUNT, MIN, MAX, SUM, AVG }

    record Search(String query, String field) implements ResourceQueryStage {
        public Search {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
            if (field != null && (!field.startsWith("/") || field.isBlank())) {
                throw new IllegalArgumentException("field must be an RFC 6901-style pointer");
            }
        }
    }

    record Filter(String field, Operator operator, ResourceValue.Scalar value) implements ResourceQueryStage {
        public Filter {
            requireField(field);
            Objects.requireNonNull(operator, "operator");
            if (operator != Operator.EXISTS && value == null) {
                throw new IllegalArgumentException("Filter value is required unless operator is EXISTS");
            }
        }
    }

    record Select(List<String> fields) implements ResourceQueryStage {
        public Select {
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            if (fields.isEmpty()) throw new IllegalArgumentException("At least one SELECT field is required");
            fields.forEach(ResourceQueryStage::requireField);
        }
    }

    record Sort(String field, Direction direction) implements ResourceQueryStage {
        public Sort {
            requireField(field);
            Objects.requireNonNull(direction, "direction");
        }
    }

    record Group(String field) implements ResourceQueryStage {
        public Group { requireField(field); }
    }

    record Aggregate(String field, AggregateFunction function, String groupBy) implements ResourceQueryStage {
        public Aggregate {
            Objects.requireNonNull(function, "function");
            if (function != AggregateFunction.COUNT) requireField(field);
            if (groupBy != null) requireField(groupBy);
        }
    }

    record Expand(String field) implements ResourceQueryStage {
        public Expand { requireField(field); }
    }

    record Take(int count) implements ResourceQueryStage {
        public Take {
            if (count < 0) throw new IllegalArgumentException("TAKE count must be non-negative");
        }
    }

    record Follow(String relation, int maxDepth) implements ResourceQueryStage {
        public Follow {
            if (relation == null || relation.isBlank()) throw new IllegalArgumentException("relation is required");
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
        }
    }

    private static void requireField(String field) {
        if (field == null || field.isBlank() || !field.startsWith("/")) {
            throw new IllegalArgumentException("field must be an RFC 6901-style pointer");
        }
    }
}
