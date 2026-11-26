package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.util.List;

/**
 * Whether a release is an upgrade of what there is, translated from Sonarr's {@code UpgradableSpecification}
 * (GPL-3.0, orch.md decision 122).
 */
public final class Upgradable {

    /** Why it is not an upgrade (Sonarr's {@code UpgradeableRejectReason}). */
    public enum Reason {
        /** It is an upgrade. */
        NONE,
        /** What there is is of a better quality. */
        BETTER_QUALITY,
        /** What there is has a better revision. */
        BETTER_REVISION,
        /** What there is meets the quality cutoff. */
        QUALITY_CUTOFF,
        /** What there is meets the custom format cutoff. */
        CUSTOM_FORMAT_CUTOFF,
        /** What there is scores as much or more. */
        CUSTOM_FORMAT_SCORE,
        /** The new one does not score enough more. */
        MIN_CUSTOM_FORMAT_SCORE,
        /** The profile does not allow upgrades. */
        UPGRADES_NOT_ALLOWED
    }

    private Upgradable() {
    }

    /**
     * Sonarr's {@code IsUpgradable}.
     *
     * @param profile the series' profile
     * @param current what there is
     * @param currentFormats its custom formats
     * @param candidate the new release's quality
     * @param candidateFormats its custom formats
     * @param propers {@code preferAndUpgrade}, {@code doNotUpgrade} or {@code doNotPrefer}
     * @return why not, or {@link Reason#NONE}
     */
    public static Reason isUpgradable(QualityProfile profile, QualityModel current, List<CustomFormat> currentFormats,
                                      QualityModel candidate, List<CustomFormat> candidateFormats, String propers) {
        int qualityCompare = ProfileQualities.compare(profile, candidate.quality.id(), current.quality.id());
        if (qualityCompare > 0 && qualityCutoffNotMet(profile, current, candidate)) {
            return Reason.NONE;
        }
        if (qualityCompare < 0) {
            return Reason.BETTER_QUALITY;
        }
        int revisionCompare = candidate.revision.compareTo(current.revision);
        boolean prefer = !"doNotPrefer".equals(propers);
        if (prefer && revisionCompare > 0) {
            return Reason.NONE;
        }
        if (!profile.upgradeAllowed()) {
            return Reason.UPGRADES_NOT_ALLOWED;
        }
        if (prefer && revisionCompare < 0) {
            return Reason.BETTER_REVISION;
        }
        if (qualityCompare > 0) {
            return Reason.QUALITY_CUTOFF;
        }
        int currentScore = ProfileQualities.score(profile, currentFormats);
        int newScore = ProfileQualities.score(profile, candidateFormats);
        if (newScore <= currentScore) {
            return Reason.CUSTOM_FORMAT_SCORE;
        }
        if (currentScore >= profile.cutoffFormatScore()) {
            return Reason.CUSTOM_FORMAT_CUTOFF;
        }
        if (newScore < currentScore + profile.minUpgradeFormatScore()) {
            return Reason.MIN_CUSTOM_FORMAT_SCORE;
        }
        return Reason.NONE;
    }

    /**
     * Sonarr's {@code QualityCutoffNotMet}.
     *
     * @param profile the profile
     * @param current what there is
     * @param candidate the new one, or {@code null}
     * @return whether the quality cutoff is not met yet
     */
    public static boolean qualityCutoffNotMet(QualityProfile profile, QualityModel current, QualityModel candidate) {
        int cutoff = profile.upgradeAllowed() ? profile.cutoff() : ProfileQualities.firstAllowed(profile).id();
        if (ProfileQualities.compare(profile, current.quality.id(), cutoff) < 0) {
            return true;
        }
        return candidate != null && isRevisionUpgrade(current, candidate);
    }

    /**
     * Sonarr's {@code CutoffNotMet}: the quality's or the custom formats' cutoff.
     *
     * @param profile the profile
     * @param current what there is
     * @param currentFormats its custom formats
     * @param candidate the new one, or {@code null}
     * @return whether a cutoff is not met yet
     */
    public static boolean cutoffNotMet(QualityProfile profile, QualityModel current, List<CustomFormat> currentFormats,
                                       QualityModel candidate) {
        if (qualityCutoffNotMet(profile, current, candidate)) {
            return true;
        }
        int score = ProfileQualities.score(profile, currentFormats);
        int cutoff = profile.upgradeAllowed() ? profile.cutoffFormatScore() : profile.minFormatScore();
        return score < cutoff;
    }

    /**
     * Sonarr's {@code IsRevisionUpgrade}: the same quality with a better revision.
     *
     * @param current what there is
     * @param candidate the new one
     * @return whether it is a PROPER or REPACK of it
     */
    public static boolean isRevisionUpgrade(QualityModel current, QualityModel candidate) {
        return current.quality.equals(candidate.quality) && candidate.revision.compareTo(current.revision) > 0;
    }

    /**
     * Sonarr's {@code IsUpgradeAllowed}.
     *
     * @param profile the profile
     * @param current what there is
     * @param currentFormats its custom formats
     * @param candidate the new one
     * @param candidateFormats its custom formats
     * @return whether the profile lets it replace what there is
     */
    public static boolean isUpgradeAllowed(QualityProfile profile, QualityModel current,
                                           List<CustomFormat> currentFormats, QualityModel candidate,
                                           List<CustomFormat> candidateFormats) {
        boolean qualityUpgrade = ProfileQualities.compare(profile, candidate, current) > 0;
        boolean formatUpgrade = ProfileQualities.score(profile, candidateFormats)
                > ProfileQualities.score(profile, currentFormats);
        if (isRevisionUpgrade(current, candidate)) {
            return true;
        }
        return !(qualityUpgrade || formatUpgrade) || profile.upgradeAllowed();
    }
}
