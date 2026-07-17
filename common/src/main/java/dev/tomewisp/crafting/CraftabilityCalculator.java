package dev.tomewisp.crafting;

import dev.tomewisp.context.DataCompleteness;
import dev.tomewisp.context.IngredientRequirementSnapshot;
import dev.tomewisp.context.InventorySnapshot;
import dev.tomewisp.context.ItemStackSnapshot;
import dev.tomewisp.context.RecipeEntrySnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure deterministic material allocation. It intentionally does not craft intermediates. */
public final class CraftabilityCalculator {
    public CraftabilityResult calculate(
            RecipeEntrySnapshot recipe, InventorySnapshot inventory, long requestedCrafts) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(inventory, "inventory");
        if (requestedCrafts <= 0) {
            throw new IllegalArgumentException("requestedCrafts must be positive");
        }

        Map<String, Long> available = inventoryCounts(inventory);
        Allocation requested = allocate(recipe, available, requestedCrafts);
        long maximumCrafts = maximumCrafts(recipe, available);
        boolean conclusive = recipe.evidence().completeness() == DataCompleteness.COMPLETE
                && inventory.evidence().completeness() == DataCompleteness.COMPLETE;
        return new CraftabilityResult(
                requested.missing().isEmpty(),
                conclusive,
                requestedCrafts,
                maximumCrafts,
                requested.allocations(),
                requested.missing());
    }

    private static long maximumCrafts(
            RecipeEntrySnapshot recipe, Map<String, Long> available) {
        List<IngredientRequirementSnapshot> requirements = requirements(recipe);
        if (requirements.stream().noneMatch(IngredientRequirementSnapshot::consumed)) {
            return allocate(recipe, available, 1).missing().isEmpty() ? Long.MAX_VALUE : 0;
        }
        long low = 0;
        long high = 1;
        while (allocate(recipe, available, high).missing().isEmpty()) {
            low = high;
            if (high > Long.MAX_VALUE / 2) {
                return Long.MAX_VALUE;
            }
            high *= 2;
        }
        while (low + 1 < high) {
            long middle = low + (high - low) / 2;
            if (allocate(recipe, available, middle).missing().isEmpty()) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static Allocation allocate(
            RecipeEntrySnapshot recipe, Map<String, Long> available, long crafts) {
        List<IngredientRequirementSnapshot> all = requirements(recipe);
        List<IngredientRequirementSnapshot> consumed = all.stream()
                .filter(IngredientRequirementSnapshot::consumed)
                .sorted(Comparator.comparing(IngredientRequirementSnapshot::key))
                .toList();
        List<MissingRequirement> missing = new ArrayList<>();

        all.stream()
                .filter(requirement -> !requirement.consumed())
                .sorted(Comparator.comparing(IngredientRequirementSnapshot::key))
                .forEach(requirement -> {
                    long present = compatibleItems(requirement).stream()
                            .mapToLong(item -> available.getOrDefault(item, 0L))
                            .reduce(0L, Math::addExact);
                    long allocated = Math.min(present, requirement.count());
                    if (allocated < requirement.count()) {
                        missing.add(new MissingRequirement(
                                requirement.key(),
                                requirement.count(),
                                allocated,
                                requirement.count() - allocated,
                                compatibleItems(requirement)));
                    }
                });

        if (consumed.isEmpty()) {
            return new Allocation(List.of(), missing);
        }

        Set<String> itemSet = new LinkedHashSet<>();
        consumed.forEach(requirement -> itemSet.addAll(compatibleItems(requirement)));
        List<String> items = itemSet.stream().sorted().toList();
        int source = 0;
        int requirementStart = 1;
        int itemStart = requirementStart + consumed.size();
        int sink = itemStart + items.size();
        Dinic flow = new Dinic(sink + 1);
        List<Edge> sourceEdges = new ArrayList<>();
        List<List<ItemEdge>> allocationEdges = new ArrayList<>();

        for (int index = 0; index < consumed.size(); index++) {
            IngredientRequirementSnapshot requirement = consumed.get(index);
            long required = Math.multiplyExact(requirement.count(), crafts);
            sourceEdges.add(flow.addEdge(source, requirementStart + index, required));
            List<ItemEdge> edges = new ArrayList<>();
            for (String item : compatibleItems(requirement)) {
                int itemIndex = java.util.Collections.binarySearch(items, item);
                Edge edge = flow.addEdge(requirementStart + index, itemStart + itemIndex, required);
                edges.add(new ItemEdge(item, edge));
            }
            allocationEdges.add(edges);
        }
        for (int index = 0; index < items.size(); index++) {
            flow.addEdge(itemStart + index, sink, available.getOrDefault(items.get(index), 0L));
        }
        flow.maxFlow(source, sink);

        List<IngredientAllocation> allocations = new ArrayList<>();
        for (int index = 0; index < consumed.size(); index++) {
            IngredientRequirementSnapshot requirement = consumed.get(index);
            for (ItemEdge itemEdge : allocationEdges.get(index)) {
                long used = itemEdge.edge().initialCapacity - itemEdge.edge().capacity;
                if (used > 0) {
                    allocations.add(new IngredientAllocation(requirement.key(), itemEdge.itemId(), used));
                }
            }
            long unfilled = sourceEdges.get(index).capacity;
            if (unfilled > 0) {
                long required = sourceEdges.get(index).initialCapacity;
                missing.add(new MissingRequirement(
                        requirement.key(),
                        required,
                        required - unfilled,
                        unfilled,
                        compatibleItems(requirement)));
            }
        }
        allocations.sort(Comparator.comparing(IngredientAllocation::requirementKey)
                .thenComparing(IngredientAllocation::itemId));
        missing.sort(Comparator.comparing(MissingRequirement::requirementKey));
        return new Allocation(allocations, missing);
    }

    private static List<IngredientRequirementSnapshot> requirements(RecipeEntrySnapshot recipe) {
        ArrayList<IngredientRequirementSnapshot> result = new ArrayList<>(recipe.ingredients());
        result.addAll(recipe.catalysts());
        return List.copyOf(result);
    }

    private static List<String> compatibleItems(IngredientRequirementSnapshot requirement) {
        return requirement.alternatives().stream()
                .flatMap(alternative -> {
                    if (alternative.kind().equals("item")) {
                        return java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(alternative.id()),
                                alternative.resolvedItems().stream());
                    }
                    return alternative.resolvedItems().stream();
                })
                .distinct()
                .sorted()
                .toList();
    }

    private static Map<String, Long> inventoryCounts(InventorySnapshot inventory) {
        TreeMap<String, Long> result = new TreeMap<>();
        inventory.slots().forEach(slot -> addStack(result, slot.stack()));
        addStack(result, inventory.offHand());
        return result;
    }

    private static void addStack(Map<String, Long> result, ItemStackSnapshot stack) {
        if (stack.count() > 0 && !stack.itemId().equals("minecraft:air")) {
            result.merge(stack.itemId(), (long) stack.count(), Math::addExact);
        }
    }

    private record Allocation(
            List<IngredientAllocation> allocations, List<MissingRequirement> missing) {}

    private record ItemEdge(String itemId, Edge edge) {}

    private static final class Edge {
        private final int to;
        private final int reverse;
        private final long initialCapacity;
        private long capacity;

        private Edge(int to, int reverse, long capacity) {
            this.to = to;
            this.reverse = reverse;
            this.initialCapacity = capacity;
            this.capacity = capacity;
        }
    }

    private static final class Dinic {
        private final List<List<Edge>> graph;
        private final int[] level;
        private final int[] next;

        private Dinic(int nodes) {
            graph = new ArrayList<>(nodes);
            for (int index = 0; index < nodes; index++) {
                graph.add(new ArrayList<>());
            }
            level = new int[nodes];
            next = new int[nodes];
        }

        private Edge addEdge(int from, int to, long capacity) {
            Edge forward = new Edge(to, graph.get(to).size(), capacity);
            Edge reverse = new Edge(from, graph.get(from).size(), 0);
            graph.get(from).add(forward);
            graph.get(to).add(reverse);
            return forward;
        }

        private long maxFlow(int source, int sink) {
            long result = 0;
            while (buildLevels(source, sink)) {
                Arrays.fill(next, 0);
                long pushed;
                while ((pushed = push(source, sink, Long.MAX_VALUE)) > 0) {
                    result = Math.addExact(result, pushed);
                }
            }
            return result;
        }

        private boolean buildLevels(int source, int sink) {
            Arrays.fill(level, -1);
            level[source] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty()) {
                int node = queue.removeFirst();
                for (Edge edge : graph.get(node)) {
                    if (edge.capacity > 0 && level[edge.to] < 0) {
                        level[edge.to] = level[node] + 1;
                        queue.addLast(edge.to);
                    }
                }
            }
            return level[sink] >= 0;
        }

        private long push(int node, int sink, long limit) {
            if (node == sink) {
                return limit;
            }
            List<Edge> edges = graph.get(node);
            while (next[node] < edges.size()) {
                Edge edge = edges.get(next[node]);
                if (edge.capacity > 0 && level[edge.to] == level[node] + 1) {
                    long pushed = push(edge.to, sink, Math.min(limit, edge.capacity));
                    if (pushed > 0) {
                        edge.capacity -= pushed;
                        graph.get(edge.to).get(edge.reverse).capacity += pushed;
                        return pushed;
                    }
                }
                next[node]++;
            }
            return 0;
        }
    }
}
