package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.FormatItem;
import com.catacumbas69.catasonarr.model.ProfileItem;
import com.catacumbas69.catasonarr.model.QualityProfile;
import com.catacumbas69.catasonarr.quality.Qualities;
import com.catacumbas69.catasonarr.quality.Quality;
import com.catacumbas69.catasonarr.quality.QualityModel;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A quality profile's order of qualities and custom format scores, as Sonarr's {@code QualityProfile},
 * {@code QualityIndex} and {@code QualityModelComparer} (GPL-3.0): the profile's items go from worst to best; a
 * group's qualities share its place.
 */
public final class ProfileQualities {

    private ProfileQualities() {
    }

    /**
     * A quality's (or a group's) place in the profile.
     *
     * @param profile the profile
     * @param id the quality's or group's id
     * @param respectGroupOrder also order the qualities inside a group
     * @return {index, group index}; {0, 0} when not in the profile, as Sonarr's
     */
    public static int[] index(QualityProfile profile, int id, boolean respectGroupOrder) {
        List<ProfileItem> items = profile.items();
        for (int i = 0; i < items.size(); i++) {
            ProfileItem item = items.get(i);
            if (item.quality() != null && item.quality() == id) {
                return new int[] {i, 0};
            }
            if (item.id() != null && item.id() > 0 && item.id() == id) {
                return new int[] {i, 0};
            }
            List<ProfileItem> group = item.items() == null ? List.of() : item.items();
            for (int g = 0; g < group.size(); g++) {
                if (group.get(g).quality() != null && group.get(g).quality() == id) {
                    return new int[] {i, respectGroupOrder ? g : 0};
                }
            }
        }
        return new int[] {0, 0};
    }

    /**
     * Compares two qualities by their place in the profile.
     *
     * @param profile the profile
     * @param left one quality's id
     * @param right the other's
     * @return negative, zero or positive
     */
    public static int compare(QualityProfile profile, int left, int right) {
        return Integer.compare(index(profile, left, false)[0], index(profile, right, false)[0]);
    }

    /**
     * Compares two qualities with their revisions.
     *
     * @param profile the profile
     * @param left one
     * @param right the other
     * @return negative, zero or positive
     */
    public static int compare(QualityProfile profile, QualityModel left, QualityModel right) {
        int result = compare(profile, left.quality.id(), right.quality.id());
        return result != 0 ? result : left.revision.compareTo(right.revision);
    }

    /**
     * Whether a quality is allowed by the profile.
     *
     * @param profile the profile
     * @param quality the quality
     * @return whether its item (or group) is allowed
     */
    public static boolean allowed(QualityProfile profile, Quality quality) {
        return profile.items().get(index(profile, quality.id(), false)[0]).allowed();
    }

    /**
     * The profile's first (worst) allowed quality.
     *
     * @param profile the profile
     * @return the quality
     */
    public static Quality firstAllowed(QualityProfile profile) {
        ProfileItem first = profile.items().stream().filter(ProfileItem::allowed).findFirst().orElseThrow();
        return Qualities.quality(first.quality() != null ? first.quality() : first.items().get(0).quality());
    }

    /**
     * The profile's last (best) allowed quality.
     *
     * @param profile the profile
     * @return the quality
     */
    public static Quality lastAllowed(QualityProfile profile) {
        List<ProfileItem> allowed = profile.items().stream().filter(ProfileItem::allowed).toList();
        ProfileItem last = allowed.get(allowed.size() - 1);
        return Qualities.quality(last.quality() != null ? last.quality()
                : last.items().get(last.items().size() - 1).quality());
    }

    /**
     * The name of the profile's item at a quality's place, as Sonarr's messages show the cutoff.
     *
     * @param profile the profile
     * @param id the quality's or group's id
     * @return its name
     */
    public static String name(QualityProfile profile, int id) {
        ProfileItem item = profile.items().get(index(profile, id, false)[0]);
        return item.quality() != null ? Qualities.quality(item.quality()).name() : item.name();
    }

    /**
     * The score of custom formats in the profile.
     *
     * @param profile the profile
     * @param formats the formats
     * @return the sum of their scores
     */
    public static int score(QualityProfile profile, List<CustomFormat> formats) {
        Set<Integer> ids = formats.stream().map(CustomFormat::id).collect(Collectors.toSet());
        return profile.formatItems() == null ? 0 : profile.formatItems().stream()
                .filter(f -> ids.contains(f.format())).mapToInt(FormatItem::score).sum();
    }
}
