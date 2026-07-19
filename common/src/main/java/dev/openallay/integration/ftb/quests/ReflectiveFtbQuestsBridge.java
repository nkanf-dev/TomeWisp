package dev.openallay.integration.ftb.quests;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class ReflectiveFtbQuestsBridge implements FtbQuestsBridge {
    public static final String API_CLASS = "dev.ftb.mods.ftbquests.api.FTBQuestsAPI";

    private final ClassLoader loader;
    private final String apiClassName;
    private volatile FtbQuestsMapping mapping;
    private final Map<MethodKey, MethodHandle> methods = new HashMap<>();

    public ReflectiveFtbQuestsBridge(ClassLoader loader) {
        this(loader, API_CLASS);
    }

    public ReflectiveFtbQuestsBridge(ClassLoader loader, String apiClassName) {
        this.loader = loader;
        this.apiClassName = apiClassName;
    }

    @Override
    public FtbQuestSnapshot.Result snapshot(Object player, boolean clientSide) {
        try {
            FtbQuestsMapping root = root();
            Object api = root.api().invoke();
            Object file = root.getQuestFile().invoke(api, clientSide);
            Optional<?> team = optional(invoke(file, "getTeamData", player));
            if (team.isEmpty()) {
                return FtbQuestSnapshot.Result.unavailable(
                        "ftb_team_data_unavailable", "FTB Quests has no visible team data for this player");
            }
            Object teamData = team.orElseThrow();
            List<Object> chapters = list(invoke(file, "getVisibleChapters", teamData));
            List<RawQuest> raw = new ArrayList<>();
            Set<String> visibleIds = new HashSet<>();
            for (Object chapter : chapters) {
                String chapterId = id(chapter);
                String chapterTitle = component(invoke(chapter, "getTitle"));
                for (Object quest : list(invoke(chapter, "getQuests"))) {
                    if (!(boolean) invoke(quest, "isVisible", teamData)) {
                        continue;
                    }
                    String questId = id(quest);
                    visibleIds.add(questId);
                    raw.add(new RawQuest(
                            quest,
                            questId,
                            chapterId,
                            chapterTitle,
                            component(invoke(quest, "getTitle")),
                            components(list(invoke(quest, "getDescription"))),
                            (boolean) invoke(teamData, "isCompleted", quest)));
                }
            }
            List<FtbQuestSnapshot> result = new ArrayList<>();
            for (RawQuest quest : raw) {
                Set<String> dependencies = new java.util.TreeSet<>();
                try (Stream<?> stream = stream(invoke(quest.raw, "streamDependencies"))) {
                    stream.map(this::id).filter(visibleIds::contains).forEach(dependencies::add);
                }
                result.add(new FtbQuestSnapshot(
                        quest.id,
                        quest.chapterId,
                        quest.chapterTitle,
                        quest.title,
                        quest.description,
                        dependencies,
                        quest.completed,
                        "ftbquests:public-api+validated-method-handles"));
            }
            result.sort(java.util.Comparator.comparing(FtbQuestSnapshot::chapterId)
                    .thenComparing(FtbQuestSnapshot::questId));
            return FtbQuestSnapshot.Result.available(result);
        } catch (ClassNotFoundException failure) {
            return FtbQuestSnapshot.Result.unavailable(
                    "integration_unavailable", "FTB Quests API is not installed");
        } catch (Throwable failure) {
            Throwable cause = failure;
            while (cause.getCause() != null
                    && (cause instanceof java.lang.reflect.InvocationTargetException
                            || cause instanceof java.lang.invoke.WrongMethodTypeException)) {
                cause = cause.getCause();
            }
            return FtbQuestSnapshot.Result.unavailable(
                    "integration_shape_mismatch",
                    "FTB Quests public API shape is unsupported: "
                            + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()));
        }
    }

    private FtbQuestsMapping root() throws ClassNotFoundException, IllegalAccessException {
        FtbQuestsMapping current = mapping;
        if (current != null) return current;
        synchronized (this) {
            if (mapping != null) return mapping;
            Class<?> apiClass = Class.forName(apiClassName, false, loader);
            Method api = unique(apiClass, "api", 0, true);
            MethodHandle apiHandle = MethodHandles.publicLookup().unreflect(api);
            Class<?> apiType = api.getReturnType();
            Method getQuestFile = unique(apiType, "getQuestFile", 1, false);
            if (getQuestFile.getParameterTypes()[0] != boolean.class) {
                throw new IllegalArgumentException("getQuestFile must accept boolean");
            }
            mapping = new FtbQuestsMapping(
                    apiHandle, MethodHandles.publicLookup().unreflect(getQuestFile));
            return mapping;
        }
    }

    private Object invoke(Object receiver, String name, Object... arguments) throws Throwable {
        MethodKey key = new MethodKey(receiver.getClass(), name, arguments.length);
        MethodHandle handle;
        synchronized (methods) {
            handle = methods.get(key);
            if (handle == null) {
                Method method = unique(receiver.getClass(), name, arguments.length, false);
                handle = MethodHandles.publicLookup().unreflect(method);
                methods.put(key, handle);
            }
        }
        List<Object> values = new ArrayList<>(arguments.length + 1);
        values.add(receiver);
        values.addAll(List.of(arguments));
        return handle.invokeWithArguments(values);
    }

    private static Method unique(Class<?> type, String name, int arity, boolean requireStatic) {
        List<Method> matches = Stream.of(type.getMethods())
                .filter(method -> method.getName().equals(name)
                        && method.getParameterCount() == arity
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isStatic(method.getModifiers()) == requireStatic)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "Expected one public " + name + "/" + arity + " on " + type.getName());
        }
        return matches.getFirst();
    }

    private String id(Object object) {
        try {
            Object value = invoke(object, "getCodeString");
            return String.valueOf(value);
        } catch (Throwable failure) {
            try {
                return Long.toHexString(((Number) invoke(object, "getId")).longValue());
            } catch (Throwable nested) {
                throw new IllegalArgumentException("Quest object lacks a public stable ID", nested);
            }
        }
    }

    private String component(Object component) throws Throwable {
        return String.valueOf(invoke(component, "getString"));
    }

    private String components(List<Object> values) throws Throwable {
        List<String> lines = new ArrayList<>();
        for (Object value : values) lines.add(component(value));
        return String.join("\n", lines);
    }

    private static Optional<?> optional(Object value) {
        if (!(value instanceof Optional<?> optional)) {
            throw new IllegalArgumentException("Expected Optional from getTeamData");
        }
        return optional;
    }

    private static List<Object> list(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("Expected Collection from FTB Quests API");
        }
        return new ArrayList<>(collection);
    }

    private static Stream<?> stream(Object value) {
        if (!(value instanceof Stream<?> stream)) {
            throw new IllegalArgumentException("Expected Stream from streamDependencies");
        }
        return stream;
    }

    private record MethodKey(Class<?> type, String name, int arity) {}
    private record RawQuest(
            Object raw,
            String id,
            String chapterId,
            String chapterTitle,
            String title,
            String description,
            boolean completed) {}
}
