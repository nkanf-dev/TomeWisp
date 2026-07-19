package dev.openallay.settings.model;

import dev.openallay.guide.GuideFailure;
import dev.openallay.model.config.CredentialReference;
import dev.openallay.model.config.ModelProfileDefinition;
import dev.openallay.model.config.ModelProfilesConfig;
import dev.openallay.model.config.ResolvedModelProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            java.util.Set<String> ignoredPresentEnvironmentNames,
            GuideFailure metadataFailure,
            ModelConnectionResult connectionResult) {
        Objects.requireNonNull(ignoredPresentEnvironmentNames, "presentEnvironmentNames");
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
                    profile.credentialPresent(),
                    profile.effectiveContextWindowTokens(),
                    profile.failure()));
        }
        return new ModelProfileSettingsView(
                config, views, metadataFailure, connectionResult);
    }

    /** Redacted resolution retained by settings; runtime credentials never enter the snapshot owner. */
    public record Resolution(
            ModelProfileDefinition definition,
            boolean available,
            boolean credentialPresent,
            Integer effectiveContextWindowTokens,
            GuideFailure failure) {
        public Resolution {
            Objects.requireNonNull(definition, "definition");
            if (available == (failure != null)) {
                throw new IllegalArgumentException(
                        "available model profiles have no failure and unavailable profiles require one");
            }
        }

        public Resolution(
                ModelProfileDefinition definition,
                boolean available,
                Integer effectiveContextWindowTokens,
                GuideFailure failure) {
            this(definition, available, available, effectiveContextWindowTokens, failure);
        }

        public static Resolution from(ResolvedModelProfile profile) {
            return from(profile, profile.available());
        }

        public static Resolution from(
                ResolvedModelProfile profile, boolean credentialPresent) {
            Objects.requireNonNull(profile, "profile");
            return new Resolution(
                    profile.definition(),
                    profile.available(),
                    credentialPresent,
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

        public boolean credentialStoredLocally() {
            try {
                return credentialPresent
                        && CredentialReference.parse(definition.credentialRef()).kind()
                                == CredentialReference.Kind.LOCAL;
            } catch (RuntimeException failure) {
                return false;
            }
        }

        public boolean credentialFromEnvironment() {
            try {
                return credentialPresent
                        && CredentialReference.parse(definition.credentialRef()).kind()
                                == CredentialReference.Kind.ENVIRONMENT;
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }
}
