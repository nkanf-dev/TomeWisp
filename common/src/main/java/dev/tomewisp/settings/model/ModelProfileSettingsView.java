package dev.tomewisp.settings.model;

import dev.tomewisp.guide.GuideFailure;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ResolvedModelProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Ordered, credential-free model profile projection for native settings. */
public record ModelProfileSettingsView(
        ModelProfilesConfig config,
        List<Profile> profiles,
        GuideFailure metadataFailure,
        ModelConnectionResult connectionResult) {
    public ModelProfileSettingsView {
        Objects.requireNonNull(config, "config");
        profiles = List.copyOf(profiles);
        if (profiles.size() != config.profiles().size()) {
            throw new IllegalArgumentException("every configured model profile needs a view");
        }
        for (int index = 0; index < profiles.size(); index++) {
            if (!profiles.get(index).definition().id().equals(config.profiles().get(index).id())) {
                throw new IllegalArgumentException("model profile view order must match configuration");
            }
        }
    }

    public static ModelProfileSettingsView from(
            ModelProfilesConfig config,
            List<Resolution> resolved,
            Set<String> presentEnvironmentNames,
            GuideFailure metadataFailure,
            ModelConnectionResult connectionResult) {
        Objects.requireNonNull(presentEnvironmentNames, "presentEnvironmentNames");
        Map<String, Resolution> byId = new HashMap<>();
        for (Resolution profile : resolved) {
            if (byId.put(profile.definition().id(), profile) != null) {
                throw new IllegalArgumentException("duplicate resolved model profile");
            }
        }
        List<Profile> views = new ArrayList<>();
        for (ModelProfileDefinition definition : config.profiles()) {
            Resolution profile = Objects.requireNonNull(
                    byId.get(definition.id()), "missing resolved model profile");
            views.add(new Profile(
                    definition,
                    profile.available(),
                    credentialPresent(definition.credentialRef(), presentEnvironmentNames),
                    profile.effectiveContextWindowTokens(),
                    profile.failure()));
        }
        return new ModelProfileSettingsView(
                config, views, metadataFailure, connectionResult);
    }

    private static boolean credentialPresent(
            String credentialRef, Set<String> presentEnvironmentNames) {
        dev.tomewisp.model.config.CredentialReference reference;
        try {
            reference = dev.tomewisp.model.config.CredentialReference.parse(credentialRef);
        } catch (RuntimeException failure) {
            return false;
        }
        return reference.kind()
                        == dev.tomewisp.model.config.CredentialReference.Kind.LOCAL
                || presentEnvironmentNames.contains(reference.value());
    }

    /** Redacted resolution retained by settings; runtime credentials never enter the snapshot owner. */
    public record Resolution(
            ModelProfileDefinition definition,
            boolean available,
            Integer effectiveContextWindowTokens,
            GuideFailure failure) {
        public Resolution {
            Objects.requireNonNull(definition, "definition");
            if (available == (failure != null)) {
                throw new IllegalArgumentException(
                        "available model profiles have no failure and unavailable profiles require one");
            }
        }

        public static Resolution from(ResolvedModelProfile profile) {
            Objects.requireNonNull(profile, "profile");
            return new Resolution(
                    profile.definition(),
                    profile.available(),
                    profile.runtimeConfig() == null
                            ? profile.definition().contextWindowTokens()
                            : profile.runtimeConfig().contextWindowTokens(),
                    profile.failure());
        }
    }

    public record Profile(
            ModelProfileDefinition definition,
            boolean available,
            boolean credentialPresent,
            Integer effectiveContextWindowTokens,
            GuideFailure failure) {
        public Profile {
            Objects.requireNonNull(definition, "definition");
            if (available == (failure != null)) {
                throw new IllegalArgumentException(
                        "available model profiles have no failure and unavailable profiles require one");
            }
        }
    }
}
