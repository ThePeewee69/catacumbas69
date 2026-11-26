package com.catacumbas69.catasonarr.decision;

import com.catacumbas69.catasonarr.model.CustomFormat;
import com.catacumbas69.catasonarr.model.EpisodeFile;
import com.catacumbas69.catasonarr.model.Language;
import com.catacumbas69.catasonarr.model.Languages;
import com.catacumbas69.catasonarr.model.Series;
import com.catacumbas69.catasonarr.model.Specification;
import com.catacumbas69.catasonarr.parser.NetRegex;
import com.catacumbas69.catasonarr.parser.Parser;
import com.catacumbas69.catasonarr.parser.ParserText;
import com.catacumbas69.catasonarr.quality.QualityModel;
import com.catacumbas69.catasonarr.quality.QualitySource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Which custom formats a release or a file has, translated from Sonarr's {@code CustomFormatCalculationService} and
 * its specifications (GPL-3.0, orch.md decision 122): release title, release group, language, source, resolution,
 * size, release type and indexer flags. A format applies when, for each kind of condition it has, every required
 * one matches and at least one matches.
 */
@Service
public class CustomFormatCalculator {

    /**
     * What a format is checked against (Sonarr's {@code CustomFormatInput}).
     *
     * @param releaseTitle the release's or file's name
     * @param fileName the file's name, or {@code null}
     * @param releaseGroup the group
     * @param quality the quality
     * @param languages the languages
     * @param size bytes
     * @param indexerFlags Sonarr's flags
     * @param releaseType Sonarr's release type number
     * @param series the series, for its original language
     */
    public record Input(String releaseTitle, String fileName, String releaseGroup, QualityModel quality,
                        List<Language> languages, long size, int indexerFlags, int releaseType, Series series) {
    }

    private static final Map<String, NetRegex> REGEXES = new ConcurrentHashMap<>();

    private final MongoTemplate mongo;

    /**
     * Creates the calculator.
     *
     * @param mongo MongoDB, where the formats are
     */
    public CustomFormatCalculator(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * A release's formats.
     *
     * @param remote the release with its series
     * @return the formats, by name
     */
    public List<CustomFormat> forRelease(RemoteEpisode remote) {
        return formats(new Input(remote.parsed.releaseTitle, null, remote.parsed.releaseGroup, remote.parsed.quality,
                remote.languages, remote.release == null ? 0 : remote.release.size(),
                remote.release == null ? 0 : remote.release.indexerFlags(), remote.parsed.releaseType().value(),
                remote.series));
    }

    /**
     * A file's formats, from its scene name, original name or path.
     *
     * @param file the file
     * @param series its series
     * @return the formats, by name
     */
    public List<CustomFormat> forFile(EpisodeFile file, Series series) {
        String title = !ParserText.isBlank(file.sceneName()) ? file.sceneName()
                : !ParserText.isBlank(file.originalFilePath()) ? ParserText.fileName(file.originalFilePath())
                : ParserText.fileName(file.relativePath());
        return formats(new Input(title, ParserText.fileName(file.relativePath()), file.releaseGroup(), file.quality(),
                file.languages(), file.size(), file.indexerFlags(), file.releaseType(), series));
    }

    /**
     * The formats of something grabbed or blocked before, from its name and quality.
     *
     * @param sourceTitle its name
     * @param quality its quality
     * @param languages its languages
     * @param size bytes, or 0
     * @param series the series
     * @return the formats, by name
     */
    public List<CustomFormat> forTitle(String sourceTitle, QualityModel quality, List<Language> languages, long size,
                                       Series series) {
        String group = Parser.parseReleaseGroup(sourceTitle == null ? "" : sourceTitle);
        return formats(new Input(sourceTitle, null, group, quality, languages, size, 0, 0, series));
    }

    /**
     * The formats of an input.
     *
     * @param input what to check
     * @return the formats that apply, by name
     */
    public List<CustomFormat> formats(Input input) {
        List<CustomFormat> matches = new ArrayList<>();
        for (CustomFormat format : mongo.findAll(CustomFormat.class)) {
            Map<String, List<Boolean[]>> byType = new LinkedHashMap<>();
            for (Specification spec : format.specifications()) {
                boolean match = satisfied(spec, input);
                byType.computeIfAbsent(spec.implementation(), k -> new ArrayList<>())
                        .add(new Boolean[] {spec.required(), match});
            }
            boolean all = byType.values().stream().allMatch(group ->
                    group.stream().noneMatch(m -> m[0] && !m[1]) && group.stream().anyMatch(m -> m[1]));
            if (all) {
                matches.add(format);
            }
        }
        matches.sort(Comparator.comparing(CustomFormat::name));
        return matches;
    }

    private static boolean satisfied(Specification spec, Input input) {
        Map<String, Object> v = spec.values() == null ? Map.of() : spec.values();
        if ("LanguageSpecification".equals(spec.implementation())) {
            return language(spec, v, input);
        }
        boolean match = switch (spec.implementation()) {
            case "ReleaseTitleSpecification" -> matches(v, input.releaseTitle()) || matches(v, input.fileName());
            case "ReleaseGroupSpecification" -> matches(v, input.releaseGroup());
            case "SourceSpecification" -> input.quality() != null
                    && QualitySource.of(input.quality().quality).ordinal() == number(v.get("value"));
            case "ResolutionSpecification" -> input.quality() != null
                    && input.quality().quality.resolution() == number(v.get("value"));
            case "SizeSpecification" -> input.size() > decimal(v.get("min")) * 1024 * 1024 * 1024
                    && input.size() <= decimal(v.get("max")) * 1024 * 1024 * 1024;
            case "ReleaseTypeSpecification" -> input.releaseType() == number(v.get("value"));
            case "IndexerFlagSpecification" -> (input.indexerFlags() & number(v.get("value"))) == number(v.get("value"))
                    && number(v.get("value")) != 0;
            default -> false;
        };
        return spec.negate() != match;
    }

    /** Sonarr's {@code LanguageSpecification}, whose negation reads differently with {@code exceptLanguage}. */
    private static boolean language(Specification spec, Map<String, Object> v, Input input) {
        int id = number(v.get("value"));
        Language compared = id == Languages.ORIGINAL.id() && input.series() != null
                && input.series().originalLanguage() != null && input.series().originalLanguage().id() != 0
                ? input.series().originalLanguage() : Languages.byId(id);
        boolean except = Boolean.TRUE.equals(v.get("exceptLanguage"));
        List<Language> languages = input.languages();
        if (languages == null) {
            return false;
        }
        boolean result = except ? languages.stream().anyMatch(l -> l.id() != compared.id())
                : languages.stream().anyMatch(l -> l.id() == compared.id());
        return spec.negate() != result;
    }

    private static boolean matches(Map<String, Object> v, String text) {
        Object value = v.get("value");
        if (text == null || value == null || value.toString().isBlank()) {
            return false;
        }
        return REGEXES.computeIfAbsent(value.toString(), p -> NetRegex.compile(p, NetRegex.IGNORE_CASE)).isMatch(text);
    }

    private static int number(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double decimal(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? 0 : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
