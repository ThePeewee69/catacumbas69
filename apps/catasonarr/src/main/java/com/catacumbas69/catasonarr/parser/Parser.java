package com.catacumbas69.catasonarr.parser;

import static com.catacumbas69.catasonarr.parser.ParserText.isBlank;

import com.catacumbas69.catasonarr.quality.Qualities;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Sonarr's release-name parser, translated from Sonarr 4.0.20's {@code Parser.cs} (GPL-3.0, orch.md decisions 109
 * and 122): from a release's title or a file's name, the series' title, the season and episodes (or the air date,
 * or the absolute numbers of anime), the quality, the languages, the release group and an anime release's hash.
 * Sonarr's regular expressions are kept as Sonarr writes them, in its order, and run through {@link NetRegex}; the
 * tests are Sonarr's.
 */
@SuppressWarnings({"checkstyle:linelength", "checkstyle:nestedifdepth"})
public final class Parser {

    private static final RegexReplace[] PRE_SUBSTITUTION_REGEX = {
        // Korean series without season number, replace with S01Exxx and remove airdate
        new RegexReplace("\\.E(\\d{2,4})\\.\\d{6}\\.(.*-NEXT)$", ".S01E$1.$2", 0),

        // Some Chinese anime releases contain both English and Chinese titles, remove the Chinese title and replace with normal anime pattern
        new RegexReplace("^\\[(?:(?<subgroup>[^\\]]+?)(?:[\\u4E00-\\u9FCC]+)?)\\]\\[(?<title>[^\\]]+?)(?:\\s(?<chinesetitle>[\\u4E00-\\u9FCC][^\\]]*?))\\]\\[(?:(?:[\\u4E00-\\u9FCC]+?)?(?<episode>\\d{1,4})(?:[\\u4E00-\\u9FCC]+?)?)\\]", "[${subgroup}] ${title} - ${episode} - ", 0),

        // Chinese LoliHouse/ZERO/Lilith-Raws/Skymoon-Raws/orion origin releases don't use the expected brackets, normalize using brackets
        new RegexReplace("^\\[(?<subgroup>[^\\]]*?(?:LoliHouse|ZERO|Lilith-Raws|Skymoon-Raws|orion origin)[^\\]]*?)\\](?<title>[^\\[\\]]+?)(?: - (?<episode>[0-9-]+)\\s*|\\[第?(?<episode>[0-9]+(?:-[0-9]+)?)话?(?:END|完)?\\])\\[", "[${subgroup}][${title}][${episode}][", 0),

        // Most Chinese anime releases contain additional brackets/separators for chinese and non-chinese titles, remove junk first and if it has S0x as season number, convert it to Sx
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:\\s?★[^\\[ -]+\\s?)?\\[?(?:(?<chinesetitle>(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*?)(?:\\]\\[|\\s*[_/·]\\s*)){0,2}(?<title>[^\\[\\]]+?)(?:\\s(?:S?(?<!\\d+)((0)(?<season>\\d)|(?<season>[1-9]\\d))(?!\\d+)))\\]?(?:\\[\\d{4}\\])?\\[第?(?<episode>[0-9]+(?:-[0-9]+)?)(?:话|集)?(?: ?END|完| ?Fin)?\\]", "[${subgroup}] ${title} S${season} - ${episode} ", 0),

        // Some Chinese releases don't include a separation between Chinese and English titles within the same bracketed group
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\]\\[(?<chinesetitle>(?<![^a-zA-Z0-9])[^a-zA-Z0-9]+)(?<title>[^\\]]+?)\\](?:\\[\\d{4}\\])?\\[第?(?<episode>[0-9]+(?:-[0-9]+)?)(?:话|集)?(?: ?END|完| ?Fin)?\\]", "[${subgroup}] ${title} - ${episode} ", 0),

        // Most Chinese anime releases contain additional brackets/separators for chinese and non-chinese titles, remove junk and replace with normal anime pattern
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:\\s?★[^\\[ -]+\\s?)?\\[?(?:(?<chinesetitle>(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*?)(?:\\]\\[|\\s*[_/·]\\s*)){0,2}(?<title>[^\\]]+?)\\]?(?:\\[\\d{4}\\])?\\[第?(?<episode>[0-9]{1,4}(?:-[0-9]{1,4})?)(?:话|集)?(?: ?END|完| ?Fin)?\\]", "[${subgroup}] ${title} - ${episode} ", 0),

        // Some Chinese anime releases contain both Chinese and English titles, remove the Chinese title first and if it has S0x as season number, convert it to Sx
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:\\s)(?:(?<chinesetitle>(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*?)(?:\\s/\\s))(?<title>[^\\[\\]]+?)(?:\\s(?:S?(?<!\\d+)((0)(?<season>\\d)|(?<season>[1-9]\\d))(?!\\d+)))(?:[- ]+)(?<episode>[0-9]+(?:-[0-9]+)?)话?(?:END|完)?", "[${subgroup}] ${title} S${season} - ${episode} ", 0),

        // Some Chinese anime releases contain both English and Chinese titles, remove the Chinese title and replace with normal anime pattern
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:\\s)(?:(?<title>[^\\]]+?)(?:\\s/\\s))(?<chinesetitle>(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*?)(?:[- ]+)(?<episode>[0-9]+(?:-[0-9]+)?(?![a-z]))话?(?:END|完)?", "[${subgroup}] ${title} - ${episode} ", 0),

        // Some Chinese anime releases contain both Chinese and English titles, remove the Chinese title and replace with normal anime pattern
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:\\s)(?:(?<chinesetitle>(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*?)(?:\\s/\\s))(?<title>[^\\]]+?)(?:[- ]+)(?<episode>[0-9]+(?:-[0-9]+)?(?![a-z]))话?(?:END|完)?", "[${subgroup}] ${title} - ${episode} ", 0),

        // GM-Team releases with lots of square brackets
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:(?<chinesubgroup>\\[(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*\\])+)\\[(?<title>[^\\]]+?)\\](?<junk>\\[[^\\]]+\\])*\\[(?<episode>[0-9]+(?:-[0-9]+)?)( END| Fin)?\\]", "[${subgroup}] ${title} - ${episode} ", 0),

        // Some Chinese anime releases contain both Chinese and English titles separated by | instead of /, remove the Chinese title and replace with normal anime pattern
        new RegexReplace("^\\[(?<subgroup>[^\\]]+)\\](?:\\s)(?:(?<chinesetitle>(?=[^\\]]*?[\\u4E00-\\u9FCC])[^\\]]*?)(?:\\s\\|\\s))(?<title>[^\\]]+?)(?:[- ]+)(?<episode>[0-9]+(?:-[0-9]+)?(?![a-z]))话?(?:END|完)?", "[${subgroup}] ${title} - ${episode} ", 0),

        // Spanish releases with information in brackets
        new RegexReplace("^(?<title>.+?(?=[ ._-]\\()).+?\\((?<year>\\d{4})\\/(?<info>S[^\\/]+)", "${title} (${year}) - ${info} ", 0),
    };

    private static final NetRegex[] REPORT_TITLE_REGEX = {
        // Anime - Absolute Episode Number + Title + Season+Episode
        // Todo: This currently breaks series that start with numbers
//                NetRegex.compile(@"^(?:(?<absoluteepisode>\d{2,3})(?:_|-|\s|\.)+)+(?<title>.+?)(?:\W|_)+(?:S?(?<season>(?<!\d+)\d{1,2}(?!\d+))(?:(?:\-|[ex]|\W[ex]){1,2}(?<episode>\d{2}(?!\d+)))+)",
//                          RegexOptions.IgnoreCase | RegexOptions.Compiled),

        // Daily episode with year in series title and air time after date (Plex DVR format)
        NetRegex.compile("^^(?<title>.+?\\((?<titleyear>\\d{4})\\))[-_. ]+(?<airyear>19[4-9]\\d|20\\d\\d)(?<sep>[-_]?)(?<airmonth>0\\d|1[0-2])\\k<sep>(?<airday>[0-2]\\d|3[01])[-_. ]\\d{2}[-_. ]\\d{2}[-_. ]\\d{2}", NetRegex.IGNORE_CASE),

        // Daily episodes without title (2018-10-12, 20181012) (Strict pattern to avoid false matches)
        NetRegex.compile("^(?<airyear>19[6-9]\\d|20\\d\\d)(?<sep>[-_]?)(?<airmonth>0\\d|1[0-2])\\k<sep>(?<airday>[0-2]\\d|3[01])(?!\\d)", NetRegex.IGNORE_CASE),

        // Multi-Part episodes without a title (S01E05.S01E06)
        NetRegex.compile("^(?:\\W*S(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:e{1,2}(?<episode>\\d{1,3}(?!\\d+)))+){2,}", NetRegex.IGNORE_CASE),

        // Multi-Part episodes without a title (1x05.1x06)
        NetRegex.compile("^(?:\\W*(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:x{1,2}(?<episode>\\d{1,3}(?!\\d+)))+){2,}", NetRegex.IGNORE_CASE),

        // Episodes without a title, Multi (S01E04E05, 1x04x05, etc)
        NetRegex.compile("^(?:S?(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:(?:[-_]|[ex]){1,2}(?<episode>\\d{2,3}(?!\\d+))){2,})", NetRegex.IGNORE_CASE),

        // Split episodes (S01E05a, S01E05b, etc)
        NetRegex.compile("^(?<title>.+?)(?:S?(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:(?:[-_ ]?[ex])(?<episode>\\d{2,3}(?!\\d+))(?<splitepisode>[a-d])(?:[ _.])))", NetRegex.IGNORE_CASE),

        // Episodes without a title, Single (S01E05, 1x05)
        NetRegex.compile("^(?:S?(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:(?:[-_ ]?[ex])(?<episode>\\d{2,3}(?!\\d+))))", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Absolute (Season+Episode)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\](?:_|-|\\s|\\.)?)(?<title>.+?)[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+))(?:[-_. ])+\\((?:S(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:[ex]|\\W[ex]){1,2}(?<episode>\\d{2}(?!\\d+))))(?:v\\d+)?(?:\\)(?!\\d+)).*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Absolute - Season+Episode
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\](?:_|-|\\s|\\.)?)(?<title>.+?)[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+))(?:[-_. ](?<![()\\[!]))+(?:S(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:[ex]|\\W[ex]){1,2}(?<episode>\\d{2}(?!\\d+))))(?:v\\d+)?(?:[_. ](?!\\d+)).*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Season+Episode
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\](?:_|-|\\s|\\.)?)(?<title>.+?)(?:[-_\\W](?<![()\\[!]))+(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:[ex]|\\W[ex]){1,2}(?<episode>\\d{2}(?!\\d+)))+)(?:v\\d+)?(?:[_. ](?!\\d+)).*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Episode Absolute Episode Number ([SubGroup] Series Title Episode 01)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)(?<title>.+?)[-_. ]+?(?:Episode)(?:[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+.*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Absolute Episode Number + Season+Episode
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\](?:_|-|\\s|\\.)?)(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?))+(?:_|-|\\s|\\.)+(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:\\-|[ex]|\\W[ex]){1,2}(?<episode>\\d{2}(?!\\d+)))+).*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Season+Episode + Absolute Episode Number
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\](?:_|-|\\s|\\.)?)(?<title>.+?)(?:[-_\\W](?<![()\\[!]))+(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:\\-|[ex]|\\W[ex]){1,2}(?<episode>\\d{2}(?!\\d+)))+)(?:(?:_|-|\\s|\\.)+(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+|\\-[a-z])))+.*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title with trailing number Absolute Episode Number - Batch separated with tilde
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>.+?[^-]+?)(?:(?<![-_. ]|\\b[0]\\d+) - )[-_. ]?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+))\\s?~\\s?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+))(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title with season number in brackets Absolute Episode Number
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>[^-]+?)[_. ]+?\\(Season[_. ](?<season>\\d+)\\)[-_. ]+?(?:[-_. ]?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title with trailing 3-digit number and sub title - Absolute Episode Number
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>[^]]+?)(?:[-_. ]{3}?(?<absoluteepisode>\\d{2}(\\.\\d{1,2})?(?!-?\\d+|-[a-z]+)))+(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title with trailing number Absolute Episode Number
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>[^-]+?)(?:(?<![-_. ]|\\b[0]\\d+) - )(?:[-_. ]?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title with trailing number S## (Full season)
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>.+?)[-_. ]+(?:S(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?![ex]?\\d+))).+?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title with trailing number Absolute Episode Number
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>[^-]+?)(?:(?<![-_. ]|\\b[0]\\d+)[_ ]+)(?:[-_. ]?(?<absoluteepisode>\\d{3}(\\.\\d{1,2})?(?!\\d+|-[a-z]+)))+(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title - Absolute Episode Number
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>.+?)(?:(?<!\\b[0]\\d+))(?:[. ]-[. ](?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+|[-])))+(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Absolute Episode Number - Absolute Episode Number (batches without full separator between title and absolute episode numbers)
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>.+?)(?:(?<!\\b[0]\\d+))(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+|[-]))[. ]-[. ](?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+|[-]))(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - [SubGroup] Title Absolute Episode Number
        NetRegex.compile("^\\[(?<subgroup>.+?)\\][-_. ]?(?<title>.+?)[-_. ]+\\(?(?:[-_. ]?#?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+|-[a-z]+)))+\\)?(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])?(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Multi-episode Repeated (S01E05 - S01E06)
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+S(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:(?:e|[-_. ]e){1,2}(?<episode>\\d{1,3}(?!\\d+)))+){2,}", NetRegex.IGNORE_CASE),

        // Multi-episode Repeated (1x05 - 1x06)
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:x{1,2}(?<episode>\\d{1,3}(?!\\d+)))+){2,}", NetRegex.IGNORE_CASE),

        // Single episodes with a title (S01E05, 1x05, etc) followed by ".5 [SP]"
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+S?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))(?:[ex]|\\W[ex]|_){1,2}(?<episode>\\d{2,3}(?!\\d+|(?:[ex]|\\W[ex]|_|-){1,2}\\d+))(?<special>\\.5[ .]\\[SP\\]))", NetRegex.IGNORE_CASE),

        // Single episodes with a title (S01E05, 1x05, etc) and trailing info in slashes
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+S?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))(?:[ex]|\\W[ex]|_){1,2}(?<episode>\\d{2,3}(?!\\d+|(?:[ex]|\\W[ex]|_|-){1,2}\\d+))).+?(?:\\[.+?\\])(?!\\\\)", NetRegex.IGNORE_CASE),

        // Anime - Title Season EpisodeNumber + Absolute Episode Number [SubGroup]
        NetRegex.compile("^(?<title>.+?)(?:[-_\\W](?<![()\\[!]))+(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:[ex]|\\W[ex]|-){1,2}(?<episode>(?<!\\d+)\\d{2}(?!\\d+)))+)[-_. (]+?(?:[-_. ]?(?<absoluteepisode>(?<!\\d+)\\d{3}(\\.\\d{1,2})?(?!\\d+|[pi])))+.+?\\[(?<subgroup>.+?)\\](?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Multi-Episode with a title (S01E05E06, S01E05-06, S01E05 E06, etc) and trailing info in slashes
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+S?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))(?:[ex]|\\W[ex]|_){1,2}(?<episode>\\d{2,3}(?!\\d+))(?:(?:\\-|[ex]|\\W[ex]|_){1,2}(?<episode>\\d{2,3}(?!\\d+)))+).+?(?:\\[.+?\\])(?!\\\\)", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number [SubGroup] [Hash]? (Series Title Episode 99-100 [RlsGroup] [ABCD1234])
        NetRegex.compile("^(?<title>.+?)[-_. ]Episode(?:[-_. ]+(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+(?:.+?)\\[(?<subgroup>.+?)\\].*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number [SubGroup] [Hash]
        NetRegex.compile("^(?<title>.+?)(?:(?:_|-|\\s|\\.)+(?<absoluteepisode>\\d{3}(\\.\\d{1,2})(?!\\d+)))+(?:.+?)\\[(?<subgroup>.+?)\\].*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number (Year) [SubGroup]
        NetRegex.compile("^(?<title>.+?)[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2}(?!\\d+))[-_. ](\\(\\d{4}\\))[-_. ]\\[(?<subgroup>.+?)\\]", NetRegex.IGNORE_CASE),

        // Anime - Title with trailing number, Absolute Episode Number and hash
        NetRegex.compile("^(?<title>[^-]+?)(?:(?<![-_. ]|\\b[0]\\d+) - )(?:[-_. ]?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+(?:[-_. ]+(?<special>special|ova|ovd))?.*?(?<hash>[(\\[]\\w{8}[)\\]])(?:$|\\.mkv)", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number [Hash]
        NetRegex.compile("^(?<title>.+?)(?:(?:_|-|\\s|\\.)+(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+(?:[-_. ]+(?<special>special|ova|ovd))?[-_. ]+.*?(?<hash>[(\\[]\\w{8}[)\\]])$", NetRegex.IGNORE_CASE),

        // Episodes with airdate AND season/episode number, capture season/episode only
        NetRegex.compile("^(?<title>.+?)?\\W*(?<airdate>\\d{4}\\W+[0-1][0-9]\\W+[0-3][0-9])(?!\\W+[0-3][0-9])[-_. ](?:s?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+)))(?:[ex](?<episode>(?<!\\d+)(?:\\d{1,3})(?!\\d+)))", NetRegex.IGNORE_CASE),

        // Episodes with airdate AND season/episode number
        NetRegex.compile("^(?<title>.+?)?\\W*(?<airyear>\\d{4})\\W+(?<airmonth>[0-1][0-9])\\W+(?<airday>[0-3][0-9])(?!\\W+[0-3][0-9]).+?(?:s?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+)))(?:[ex](?<episode>(?<!\\d+)(?:\\d{1,3})(?!\\d+)))", NetRegex.IGNORE_CASE),

        // Episodes with absolute episode number AND airdate (TJET Wrestling)
        NetRegex.compile("^(?<title>.+?)?[-_. ](?:e\\d{2,3}(?!\\d+))[-_. ](?<airyear>\\d{4})\\W+(?<airmonth>[0-1][0-9])\\W+(?<airday>[0-3][0-9])(?!\\W+[0-3][0-9]).+?(?:\\[[a-z]+\\])", NetRegex.IGNORE_CASE),

        // Single or multi episode releases with multiple titles, then season and episode numbers after the last title. (Title1 / Title2 / ... / S1E1-2 of 6)
        NetRegex.compile("^((?<title>.*?)[ ._]\\/[ ._])+\\(?S(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:\\W|_)?E?[ ._]?(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+))(?:-(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+)))?(?:[ ._]of[ ._](?<episodecount>\\d{1,2}))?\\)?[ ._][\\(\\[]", NetRegex.IGNORE_CASE),

        // Multi-episode with title (S01E99-100, S01E05-06)
        NetRegex.compile("^(?<title>.+?)(?:[-_\\W](?<![()\\[!]))+S(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))E(?<episode>\\d{2,3}(?!\\d+))(?:-(?<episode>\\d{2,3}(?!\\d+)))+(?:[-_. ]|$)", NetRegex.IGNORE_CASE),

        // Multi-episode with title (S01E05-06, S01E05-6)
        NetRegex.compile("^(?<title>.+?)(?:[-_\\W](?<![()\\[!]))+S(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))E(?<episode>\\d{1,2}(?!\\d+))(?:-(?<episode>\\d{1,2}(?!\\d+)))+(?:[-_. ]|$)", NetRegex.IGNORE_CASE),

        // Episodes with a title, Single episodes (S01E05, 1x05, etc) & Multi-episode (S01E05E06, S01E05-06, S01E05 E06, etc)
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+S?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))(?:[ex]|\\W[ex]){1,2}(?<episode>\\d{2,3}(?!\\d+))(?:(?:\\-|[ex]|\\W[ex]|_){1,2}(?<episode>\\d{2,3}(?!\\d+)))*)(?:[-_. ]|$)", NetRegex.IGNORE_CASE),

        // Episodes with a title, 4 digit season number, Single episodes (S2016E05, etc) & Multi-episode (S2016E05E06, S2016E05-06, S2016E05 E06, etc)
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+S(?<season>(?<!\\d+)(?:\\d{4})(?!\\d+))(?:e|\\We|_){1,2}(?<episode>\\d{2,4}(?!\\d+))(?:(?:\\-|e|\\We|_){1,2}(?<episode>\\d{2,3}(?!\\d+)))*)\\W?(?!\\\\)", NetRegex.IGNORE_CASE),

        // Episodes with a title, 4 digit season number, Single episodes (2016x05, etc) & Multi-episode (2016x05x06, 2016x05-06, 2016x05 x06, etc)
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+(?<season>(?<!\\d+)(?:\\d{4})(?!\\d+))(?:x|\\Wx){1,2}(?<episode>\\d{2,4}(?!\\d+))(?:(?:\\-|x|\\Wx|_){1,2}(?<episode>\\d{2,3}(?!\\d+)))*)\\W?(?!\\\\)", NetRegex.IGNORE_CASE),

        // Episodes with a title, Single episodes (s01.05)
        NetRegex.compile("^(?<title>.+?)(?:[-_\\W](?<![()\\[!]))+S(?<season>(?<!\\d+)(?:\\d{2})(?!\\d+))(?:\\.)(?<episode>\\d{2,3}(?!\\d+))(?:[-_. ]|$)", NetRegex.IGNORE_CASE),

        // Multi-season pack
        NetRegex.compile("^(?<title>.+?)(Complete Series)?[-_. ]+(?:S|(?:Season|Saison|Series|Stagione)[_. ])(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))(?:[-_. ]{1}|[-_. ]{3})(?:S|(?:Season|Saison|Series|Stagione)[_. ])?(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))", NetRegex.IGNORE_CASE),

        // Partial season pack
        NetRegex.compile("^(?<title>.+?)(?:\\W+S(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))\\W+(?:(?:(?:Part|Vol)\\W?|(?<!\\d+\\W+)e|p)(?<seasonpart>\\d{1,2}(?!\\d+)))+)", NetRegex.IGNORE_CASE),

        // Anime - 4 digit absolute episode number
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)(?<title>.+?)[-_. ]+?(?<absoluteepisode>\\d{4}(\\.\\d{1,2})?(?!\\d+))", NetRegex.IGNORE_CASE),

        // Anime - Title 4-digit Absolute Episode Number [SubGroup]
        NetRegex.compile("^(?<title>.+?)[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{4}(?!\\d+))[-_. ]\\[(?<subgroup>.+?)\\]", NetRegex.IGNORE_CASE),

        // Mini-Series with year in title, treated as season 1, episodes are labelled as Part01, Part 01, Part.1
        NetRegex.compile("^(?<title>.+?\\d{4})(?:\\W+(?:(?:Part\\W?|e)(?<episode>\\d{1,2}(?!\\d+)))+)", NetRegex.IGNORE_CASE),

        // Mini-Series, treated as season 1, multi episodes are labelled as E1-E2
        NetRegex.compile("^(?<title>.+?)(?:[-._ ][e])(?<episode>\\d{2,3}(?!\\d+))(?:(?:\\-?[e])(?<episode>\\d{2,3}(?!\\d+)))+", NetRegex.IGNORE_CASE),

        // Episodes with airdate and part (2018.04.28.Part.2)
        NetRegex.compile("^(?<title>.+?)?\\W*(?<airyear>\\d{4})[-_. ]+(?<airmonth>[0-1][0-9])[-_. ]+(?<airday>[0-3][0-9])(?![-_. ]+[0-3][0-9])[-_. ]+Part[-_. ]?(?<part>[1-9])", NetRegex.IGNORE_CASE),

        // Mini-Series, treated as season 1, episodes are labelled as Part01, Part 01, Part.1
        NetRegex.compile("^(?<title>.+?)(?:\\W+(?:(?:(?<!\\()Part\\W?|(?<!\\d+\\W+)e)(?<episode>\\d{1,2}(?!\\d+|\\))))+)", NetRegex.IGNORE_CASE),

        // Mini-Series, treated as season 1, episodes are labelled as Part One/Two/Three/...Nine, Part.One, Part_One
        NetRegex.compile("^(?<title>.+?)(?:\\W+(?:Part[-._ ](?<episode>One|Two|Three|Four|Five|Six|Seven|Eight|Nine)(?>[-._ ])))", NetRegex.IGNORE_CASE),

        // Mini-Series, treated as season 1, episodes are labelled as XofY
        NetRegex.compile("^(?<title>.+?)(?:\\W+(?:(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+))of\\d+)+)", NetRegex.IGNORE_CASE),

        // Supports Season 01 Episode 03
        NetRegex.compile("(?:.*(?:\\\"|^))(?<title>.*?)(?:[-_\\W](?<![()\\[]))+(?:\\W?Season\\W?)(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:\\W|_)+(?:Episode\\W)(?:[-_. ]?(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+)))+", NetRegex.IGNORE_CASE),

        // Multi-episode with episodes in square brackets (Series Title [S01E11E12] or Series Title [S01E11-12])
        NetRegex.compile("(?:.*(?:^))(?<title>.*?)[-._ ]+\\[S(?<season>(?<!\\d+)\\d{2}(?!\\d+))(?:[E-]{1,2}(?<episode>(?<!\\d+)\\d{2}(?!\\d+)))+\\]", NetRegex.IGNORE_CASE),

        // Multi-episode with episodes in brackets (Series Title (S01E11E12) or Series Title (S01E11-12))
        NetRegex.compile("(?:.*(?:^))(?<title>.*?)[-._ ]+\\(S(?<season>(?<!\\d+)\\d{2}(?!\\d+))(?:[E-]{1,2}(?<episode>(?<!\\d+)\\d{2}(?!\\d+)))+\\)", NetRegex.IGNORE_CASE),

        // Multi-episode release with no space between series title and season (S01E11E12)
        NetRegex.compile("(?:.*(?:^))(?<title>.*?)S(?<season>(?<!\\d+)\\d{2}(?!\\d+))(?:E(?<episode>(?<!\\d+)\\d{2}(?!\\d+)))+", NetRegex.IGNORE_CASE),

        // Multi-episode with single episode numbers (S6.E1-E2, S6.E1E2, S6E1E2, etc)
        NetRegex.compile("^(?<title>.+?)[-_. ]S(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:[-_. ]?[ex]?(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+)))+", NetRegex.IGNORE_CASE),

        // Single or multi episode releases with multiple titles, each followed by season and episode numbers in brackets
        NetRegex.compile("^(?<title>.*?)[ ._]\\(S(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:\\W|_)?E?[ ._]?(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+))(?:-(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+)))?\\)(?:[ ._]\\/[ ._])(?<title>.*?)[ ._]\\(", NetRegex.IGNORE_CASE),

        // Single episode season or episode S1E1 or S1-E1 or S1.Ep1 or S01.Ep.01
        NetRegex.compile("(?:.*(?:\\\"|^))(?<title>.*?)(?:\\W?|_)S(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:\\W|_)?Ep?[ ._]?(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+))", NetRegex.IGNORE_CASE),

        // 3 digit season S010E05
        NetRegex.compile("(?:.*(?:\\\"|^))(?<title>.*?)(?:\\W?|_)S(?<season>(?<!\\d+)\\d{3}(?!\\d+))(?:\\W|_)?E(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+))", NetRegex.IGNORE_CASE),

        // 5 digit episode number with a title
        NetRegex.compile("^(?:(?<title>.+?)(?:_|-|\\s|\\.)+)(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+)))(?:(?:\\-|[ex]|\\W[ex]|_){1,2}(?<episode>(?<!\\d+)\\d{5}(?!\\d+)))", NetRegex.IGNORE_CASE),

        // 5 digit multi-episode with a title
        NetRegex.compile("^(?:(?<title>.+?)(?:_|-|\\s|\\.)+)(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+)))(?:(?:[-_. ]{1,3}ep){1,2}(?<episode>(?<!\\d+)\\d{5}(?!\\d+)))+", NetRegex.IGNORE_CASE),

        // Separated season and episode numbers S01 - E01
        NetRegex.compile("^(?<title>.+?)(?:_|-|\\s|\\.)+S(?<season>\\d{2}(?!\\d+))(\\W-\\W)E(?<episode>(?<!\\d+)\\d{2}(?!\\d+))(?!\\\\)", NetRegex.IGNORE_CASE),

        // Season and episode numbers in square brackets (single and multi-episode)
        // Series Title - [02x01] - Episode 1
        // Series Title - [02x01x02] - Episode 1
        NetRegex.compile("^(?<title>.+?)?(?:[-_\\W](?<![()\\[!]))+\\[(?:s)?(?<season>(?<!\\d+)\\d{1,2})(?:(?:[ex])(?<episode>\\d{2}))(?:(?:[-ex]){1,2}(?<episode>\\d{2}))*\\].+?(?:\\.|$)", NetRegex.IGNORE_CASE),

        // Anime - Title with season number - Absolute Episode Number (Title S01 - EP14)
        NetRegex.compile("^(?<title>.+?S\\d{1,2})[-_. ]{3,}(?:EP)?(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+|[-]))", NetRegex.IGNORE_CASE),

        // Anime - French titles with single episode numbers, with or without leading sub group ([RlsGroup] Title - Episode 1)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)[-_. ]+?(?:Episode[-_. ]+?)(?<absoluteepisode>\\d{1}(\\.\\d{1,2})?(?!\\d+))", NetRegex.IGNORE_CASE),

        // Anime - Absolute episode number in square brackets
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)(?<title>.+?)[-_. ]+?\\[(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?(?!\\d+))\\]", NetRegex.IGNORE_CASE),

        // Japanese variety shows with leading date
        NetRegex.compile("^(?<airyear>\\d{2})(?<airmonth>[0-1][0-9])(?<airday>[0-3][0-9])(?![-_. ]+[0-3][0-9])[-_. ](?<title>.+?)[-_. ](?:Season[-_. ]?(?<season>\\d{1,2})[-_. ])?(?:ep|#)(?<episode>\\d{2,3})", NetRegex.IGNORE_CASE),

        // Season only releases followed by year
        NetRegex.compile("^(?<title>.+?)[-_. ]+?(?:S|Season|Saison|Series|Stagione)[-_. ]?(?<season>\\d{1,2}(?=[-_. ]\\d{4}[-_. ]+))(?<extras>EXTRAS|SUBPACK)?(?!\\\\)", NetRegex.IGNORE_CASE),

        // Season only releases
        NetRegex.compile("^(?<title>.+?)[-_. ]+?(?:S|Season|Saison|Series|Stagione)[-_. ]?(?<season>\\d{1,2}(?![-_. ]?\\d+))(?:[-_. ]|$)+(?<extras>EXTRAS|SUBPACK)?(?!\\\\)", NetRegex.IGNORE_CASE),

        // 4 digit season only releases
        NetRegex.compile("^(?<title>.+?)[-_. ]+?(?:S|Season|Saison|Series|Stagione)[-_. ]?(?<season>\\d{4}(?![-_. ]?\\d+))(\\W+|_|$)(?<extras>EXTRAS|SUBPACK)?(?!\\\\)", NetRegex.IGNORE_CASE),

        // Spanish tracker releases
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_. ]+?Temporada.+?|\\[.+?\\])\\[Cap)(?:[-_. ]+(?<season>(?<!\\d+)\\d{1,2})(?<episode>(?<!e|x)(?:[1-9][0-9]|[0][1-9])))+(?:\\])", NetRegex.IGNORE_CASE),

        // Supports 103/113 naming
        NetRegex.compile("^(?<title>.+?)?(?:(?:[_.-](?<![()\\[!]))+(?<season>(?<!\\d+)[1-9])(?<episode>[1-9][0-9]|[0][1-9])(?![a-z]|\\d+))+(?:[_.]|$)", NetRegex.IGNORE_CASE),

        // 4 digit episode number
        // Episodes without a title, Single (S01E05, 1x05) AND Multi (S01E04E05, 1x04x05, etc)
        NetRegex.compile("^(?:S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:\\-|[ex]|\\W[ex]|_){1,2}(?<episode>\\d{4}(?!\\d+|i|p)))+)(\\W+|_|$)(?!\\\\)", NetRegex.IGNORE_CASE),

        // 4 digit episode number
        // Episodes with a title, Single episodes (S01E05, 1x05, etc) & Multi-episode (S01E05E06, S01E05-06, S01E05 E06, etc)
        NetRegex.compile("^(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]|\\d{1,2}-))+S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:\\-|[ex]|\\W[ex]|_){1,2}(?<episode>\\d{4}(?!\\d+|i|p)))+)\\W?(?!\\\\)", NetRegex.IGNORE_CASE),

        // Episodes with airdate (2018.04.28)
        NetRegex.compile("^(?<title>.+?)?\\W*(?<airyear>\\d{4})[-_. ]+(?<airmonth>[0-1][0-9])[-_. ]+(?<airday>[0-3][0-9])(?![-_. ]+[0-3][0-9])", NetRegex.IGNORE_CASE),

        // Turkish tracker releases (01 BLM, 3. Blm, 04.Bolum, etc)
        NetRegex.compile("^(?<title>.+?)[_. ](?<absoluteepisode>\\d{1,4})(?:[_. ]+)(?:BLM|B[oö]l[uü]m)", NetRegex.IGNORE_CASE),

        // Episodes with airdate (04.28.2018)
        NetRegex.compile("^(?<title>.+?)?\\W*(?<ambiguousairmonth>[0-1][0-9])[-_. ]+(?<ambiguousairday>[0-3][0-9])[-_. ]+(?<airyear>\\d{4})(?!\\d+)", NetRegex.IGNORE_CASE),

        // Episodes with airdate (28.04.2018)
        NetRegex.compile("^(?<title>.+?)?\\W*(?<ambiguousairday>[0-3][0-9])[-_. ]+(?<ambiguousairmonth>[0-1][0-9])[-_. ]+(?<airyear>\\d{4})(?!\\d+)", NetRegex.IGNORE_CASE),

        // Episodes with airdate (20180428)
        NetRegex.compile("^(?<title>.+?)?\\W*(?<!\\d+)(?<airyear>\\d{4})(?<airmonth>[0-1][0-9])(?<airday>[0-3][0-9])(?!\\d+)", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number (E195 or E1206)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)(?:(?:_|-|\\s|\\.)+(?:e|ep)(?<absoluteepisode>(\\d{3}|\\d{4})(\\.\\d{1,2})?))+[-_. ].*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Supports 1103/1113 naming
        NetRegex.compile("^(?<title>.+?)?(?:(?:[-_. ](?<![()\\[!]))*(?<!\\d{1,2}-)(?<season>(?<!\\d+|\\(|\\[|e|x)\\d{2})(?<episode>(?<!e|x)(?:[1-9][0-9]|[0][1-9])(?!p|i|\\d+|\\)|\\]|\\W\\d+|\\W(?:e|ep|x)\\d+)))+([-_. ]+|$)(?!\\\\)", NetRegex.IGNORE_CASE),

        // Dutch/Flemish release titles
        NetRegex.compile("^(?<title>.+?)[-_. ](?:Se\\.(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:(?:[-_ ]?afl\\.)(?<episode>\\d{1,3}(?!\\d+))(?:(?:[-]|[-_ ]en[-_ ])(?<episode>\\d{1,3}(?!\\d+)))*))", NetRegex.IGNORE_CASE),

        // Episodes with single digit episode number (S01E1, S01E5E6, etc)
        NetRegex.compile("^(?<title>.*?)(?:(?:[-_\\W](?<![()\\[!]))+S?(?<season>(?<!\\d+)\\d{1,2}(?!\\d+))(?:(?:\\-|[ex]){1,2}(?<episode>\\d{1}))+)+(\\W+|_|$)(?!\\\\)", NetRegex.IGNORE_CASE),

        // iTunes Season 1\05 Title (Quality).ext
        NetRegex.compile("^(?:Season(?:_|-|\\s|\\.)(?<season>(?<!\\d+)\\d{1,2}(?!\\d+)))(?:_|-|\\s|\\.)(?<episode>(?<!\\d+)\\d{1,2}(?!\\d+))", NetRegex.IGNORE_CASE),

        // iTunes 1-05 Title (Quality).ext
        NetRegex.compile("^(?:(?<season>(?<!\\d+)(?:\\d{1,2})(?!\\d+))(?:-(?<episode>\\d{2,3}(?!\\d+))))", NetRegex.IGNORE_CASE),

        // Anime Range - Title Absolute Episode Number (ep01-12)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)(?:_|\\s|\\.)+(?:e|ep)(?<absoluteepisode>\\d{2,3}(\\.\\d{1,2})?)-(?<absoluteepisode>(?<!\\d+)\\d{1,2}(\\.\\d{1,2})?(?!\\d+|-)).*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number (e66)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)(?:(?:_|-|\\s|\\.)+(?:e|ep)(?<absoluteepisode>\\d{2,4}(\\.\\d{1,2})?))+[-_. ].*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title Episode Absolute Episode Number (Series Title Episode 01)
        NetRegex.compile("^(?<title>.+?)[-_. ](?:Episode)(?:[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+)))+.*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime Range - Title Absolute Episode Number (1 or 2 digit absolute episode numbers in a range, 1-10)
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)[_. ]+(?<absoluteepisode>(?<!\\d+)\\d{1,2}(\\.\\d{1,2})?(?!\\d+))-(?<absoluteepisode>(?<!\\d+)\\d{1,2}(\\.\\d{1,2})?(?!\\d+|-)).*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title Episode/Episodio Absolute Episode Number
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)[-_. ]+(?:Episode|Episodio)(?:[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2,4}(\\.\\d{1,2})?(?!\\d+|[ip])))+.*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title [Absolute Episode Number] from AniLibriaTV
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)(?:[-_. ]\\[)(?:(?:-?)(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+|[ip])))+(?:\\][-_. ]).*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title Absolute Episode Number
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)(?:[-_. ]+(?<absoluteepisode>(?<!\\d+)\\d{2,4}(\\.\\d{1,2})?(?!\\d+|[ip])))+.*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Anime - Title {Absolute Episode Number}
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ]?)?(?<title>.+?)(?:(?:[-_\\W](?<![()\\[!]))+(?<absoluteepisode>(?<!\\d+)\\d{2,3}(\\.\\d{1,2})?(?!\\d+|[ip])))+.*?(?<hash>[(\\[]\\w{8}[)\\]])?$", NetRegex.IGNORE_CASE),

        // Extant, terrible multi-episode naming (extant.10708.hdtv-lol.mp4)
        NetRegex.compile("^(?<title>.+?)[-_. ](?<season>[0]?\\d?)(?:(?<episode>\\d{2}){2}(?!\\d+))[-_. ]", NetRegex.IGNORE_CASE),

        // Season only releases for poorly named anime
        NetRegex.compile("^(?:\\[(?<subgroup>.+?)\\][-_. ])?(?<title>.+?)[-_. ]+?[\\[(](?:S|Season|Saison|Series|Stagione)[-_. ]?(?<season>\\d{1,2}(?![-_. ]?\\d+))(?:[-_. )\\]]|$)+(?<extras>EXTRAS|SUBPACK)?(?!\\\\)", NetRegex.IGNORE_CASE),

        // Episodes without a title, Single episode numbers (S1E1, 1x1)
        NetRegex.compile("^(?:S?(?<season>(?<!\\d+)(?:\\d{1,2}|\\d{4})(?!\\d+))(?:(?:[-_ ]?[ex])(?<episode>\\d{1}(?!\\d+))))", NetRegex.IGNORE_CASE)
    };

    private static final NetRegex[] SPECIAL_EPISODE_TITLE_REGEX = {
        NetRegex.compile("\\.S\\d+E00\\.(?<episodetitle>.+?)(?:\\.(?:720p|1080p|2160p|HDTV|WEB|WEBRip|WEB-DL)\\.|$)", NetRegex.IGNORE_CASE),

        NetRegex.compile("\\.S\\d+\\.Special\\.(?<episodetitle>.+?)(?:\\.(?:720p|1080p|2160p|HDTV|WEB|WEBRip|WEB-DL)\\.|$)", NetRegex.IGNORE_CASE)
    };

    private static final NetRegex[] REJECT_HASHED_RELEASES_REGEXES = {
        // Generic match for md5 and mixed-case hashes.
        NetRegex.compile("^[0-9a-zA-Z]{32}", 0),

        // Generic match for shorter lower-case hashes.
        NetRegex.compile("^[a-z0-9]{24}$", 0),

        // Format seen on some NZBGeek releases
        // Be very strict with these coz they are very close to the valid 101 ep numbering.
        NetRegex.compile("^[A-Z]{11}\\d{3}$", 0),
        NetRegex.compile("^[a-z]{12}\\d{3}$", 0),

        // Backup filename (Unknown origins)
        NetRegex.compile("^Backup_\\d{5,}S\\d{2}-\\d{2}$", 0),

        // 123 - Started appearing December 2014
        NetRegex.compile("^123$", 0),

        // abc - Started appearing January 2015
        NetRegex.compile("^abc$", NetRegex.IGNORE_CASE),

        // abc - Started appearing 2020
        NetRegex.compile("^abc[-_. ]xyz", NetRegex.IGNORE_CASE),

        // b00bs - Started appearing January 2015
        NetRegex.compile("^b00bs$", NetRegex.IGNORE_CASE),

        // 170424_26 - Started appearing August 2018
        NetRegex.compile("^\\d{6}_\\d{2}$"),

        // additional Generic match for mixed-case hashes. - Started appearing Dec 2020
        NetRegex.compile("^[0-9a-zA-Z]{30}", 0),

        // additional Generic match for mixed-case hashes. - Started appearing Jan 2021
        NetRegex.compile("^[0-9a-zA-Z]{26}", 0),

        // additional Generic match for mixed-case hashes. - Started appearing Jan 2021
        NetRegex.compile("^[0-9a-zA-Z]{39}", 0),

        // additional Generic match for mixed-case hashes. - Started appearing Jan 2021
        NetRegex.compile("^[0-9a-zA-Z]{24}", 0),
    };

    private static final NetRegex[] SEASON_FOLDER_REGEXES = {
        NetRegex.compile("^(Season[ ._-]*\\d+|Specials)$", 0)
    };

    // Regex to detect whether the title was reversed.
    private static final NetRegex REVERSED_TITLE_REGEX = NetRegex.compile("(?:^|[-._ ])(p027|p0801|\\d{2,3}E\\d{2}S)[-._ ]", 0);

    private static final RegexReplace NORMALIZE_REGEX = new RegexReplace("((?:\\b|_)(?<!^)([aà](?!$)|an|the|and|or|of)(?!$)(?:\\b|_))|\\W|_",
            "", NetRegex.IGNORE_CASE);

    private static final NetRegex PERCENT_REGEX = NetRegex.compile("(?<=\\b\\d+)%", 0);

    private static final NetRegex FILE_EXTENSION_REGEX = NetRegex.compile("\\.[a-z0-9]{2,4}$", NetRegex.IGNORE_CASE);

    private static final RegexReplace SIMPLE_TITLE_REGEX = new RegexReplace("(?:(480|540|576|720|1080|2160)[ip]|[xh][\\W_]?26[45]|DD\\W?5\\W1|[<>?*]|848x480|1280x720|1920x1080|3840x2160|4096x2160|(?<![a-f0-9])(8|10)(b(?![a-z0-9])|bit)|10-bit)\\s*?",
            "", NetRegex.IGNORE_CASE);

    // Valid TLDs http://data.iana.org/TLD/tlds-alpha-by-domain.txt

    private static final RegexReplace WEBSITE_PREFIX_REGEX = new RegexReplace("^(?:(?:\\[|\\()\\s*)?(?:www\\.)?[-a-z0-9-]{1,256}\\.(?<!Naruto-Kun\\.)(?:[a-z]{2,6}\\.[a-z]{2,6}|xn--[a-z0-9-]{4,}|[a-z]{2,})\\b(?:\\s*(?:\\]|\\))|[ -]{2,})[ -]*",
            "", NetRegex.IGNORE_CASE);

    private static final RegexReplace WEBSITE_POSTFIX_REGEX = new RegexReplace("(?:\\[\\s*)?(?:www\\.)?[-a-z0-9-]{1,256}\\.(?:xn--[a-z0-9-]{4,}|[a-z]{2,6})\\b(?:\\s*\\])$",
            "", NetRegex.IGNORE_CASE);

    private static final NetRegex SIX_DIGIT_AIR_DATE_REGEX = NetRegex.compile("(?<=[_.-])(?<airdate>(?<!\\d)(?<airyear>[1-9]\\d{1})(?<airmonth>[0-1][0-9])(?<airday>[0-3][0-9]))(?=[_.-])", NetRegex.IGNORE_CASE);

    private static final RegexReplace CLEAN_RELEASE_GROUP_REGEX = new RegexReplace("^(.*?[-._ ](S\\d+E\\d+)[-._ ])|(-(RP|1|NZBGeek|Obfuscated|Scrambled|sample|Pre|postbot|xpost|Rakuv[a-z0-9]*|WhiteRev|BUYMORE|AsRequested|AlternativeToRequested|GEROV|Z0iDS3N|Chamele0n|4P|4Planet|AlteZachen|RePACKPOST))+$",
            "", NetRegex.IGNORE_CASE);

    private static final RegexReplace CLEAN_TORRENT_SUFFIX_REGEX = new RegexReplace("\\[(?:ettv|rartv|rarbg|cttv|publichd)\\]$",
            "", NetRegex.IGNORE_CASE);

    private static final NetRegex CLEAN_QUALITY_BRACKETS_REGEX = NetRegex.compile("\\[[a-z0-9 ._-]+\\]$", NetRegex.IGNORE_CASE);

// Java's look-behind cannot hold a back-reference: Sonarr's (?<!(?:BAD)(?:\\k<part2>)?) is checked before
    // part2 and at the end instead, which rejects the same names
    private static final String NOT_A_GROUP = "(?<!(?:WEB-DL|Blu-Ray|480p|576p|720p|1080p|2160p|DTS-HD|DTS-X|DTS-MA|DTS-ES|-ES|-EN|-CAT|[ ._]\\d{4}-\\d{2}|-\\d{2}))";
    private static final NetRegex RELEASE_GROUP_REGEX = NetRegex.compile("-(?<releasegroup>[a-z0-9]+(?:" + NOT_A_GROUP + "(?<part2>-[a-z0-9]+))?(?!.+?(?:480p|576p|720p|1080p|2160p)))" + NOT_A_GROUP + "(?:\\b|[-._ ]|$)|[-._ ]\\[(?<releasegroup>[a-z0-9]+)\\]$", NetRegex.IGNORE_CASE);

    private static final NetRegex INVALID_RELEASE_GROUP_REGEX = NetRegex.compile("^([se]\\d+|[0-9a-f]{8})$", NetRegex.IGNORE_CASE);

    private static final NetRegex ANIME_RELEASE_GROUP_REGEX = NetRegex.compile("^(?:\\[(?<subgroup>(?!\\s).+?(?<!\\s))\\](?:_|-|\\s|\\.)?)", NetRegex.IGNORE_CASE);

    // Handle Exception Release Groups that don't follow -RlsGrp; Manual List
    // name only...be very careful with this last; high chance of false positives
    private static final NetRegex EXCEPTION_RELEASE_GROUP_REGEX_EXACT = NetRegex.compile("(?<releasegroup>(?:D\\-Z0N3|Fight-BB|VARYG|E\\.N\\.D|KRaLiMaRKo|BluDragon|DarQ|KCRT|BEN[_. ]THE[_. ]MEN)\\b)", NetRegex.IGNORE_CASE);

    // groups whose releases end with RlsGroup) or RlsGroup]
    private static final NetRegex EXCEPTION_RELEASE_GROUP_REGEX = NetRegex.compile("(?<=[._ \\[])(?<releasegroup>(Silence|afm72|Panda|Ghost|MONOLITH|Tigole|Joy|ImE|UTR|t3nzin|Anime Time|Project Angel|Hakata Ramen|HONE|Vyndros|SEV|Garshasp|Kappa|Natty|RCVR|SAMPA|YOGI|r00t|EDGE2020|RZeroX)(?=\\]|\\)))", NetRegex.IGNORE_CASE);

    private static final NetRegex YEAR_IN_TITLE_REGEX = NetRegex.compile("^(?<title>.+?)[-_. ]+?[\\(\\[]?(?<year>\\d{4})[\\]\\)]?", NetRegex.IGNORE_CASE);

    private static final NetRegex TITLE_COMPONENTS_REGEX = NetRegex.compile("^(?:(?<title>.+?) \\((?<title>.+?)\\)|(?<title>.+?) \\| (?<title>.+?)|(?<title>.+?) AKA (?<title>.+?))$", NetRegex.IGNORE_CASE);

    private static final NetRegex PART_REGEX = NetRegex.compile("\\(\\d+\\)$", 0);
    private static final NetRegex PUNCTUATION_REGEX = NetRegex.compile("[^\\w\\s]", 0);
    private static final NetRegex ARTICLE_WORD_REGEX = NetRegex.compile("^(a|an|the)\\s", NetRegex.IGNORE_CASE);
    private static final NetRegex SPECIAL_EPISODE_WORD_REGEX = NetRegex.compile("\\b(part|special|edition|christmas)\\b\\s?", NetRegex.IGNORE_CASE);
    private static final NetRegex DUPLICATE_SPACES_REGEX = NetRegex.compile("\\s{2,}", 0);
    private static final NetRegex SEASON_FOLDER_REGEX = NetRegex.compile("^(?:S|Season|Saison|Series|Stagione)[-_. ]*(?<season>(?<!\\d+)\\d{1,4}(?!\\d+))(?:[_. ]+(?!\\d+)|$)", NetRegex.IGNORE_CASE);
    private static final NetRegex SIMPLE_EPISODE_NUMBER_REGEX = NetRegex.compile("^[ex]?(?<episode>(?<!\\d+)\\d{1,3}(?!\\d+))(?:[ex-](?<episode>(?<!\\d+)\\d{1,3}(?!\\d+)))?(?:[_. ](?!\\d+)(?<remaining>.+)|$)", NetRegex.IGNORE_CASE);

    private static final NetRegex REQUEST_INFO_REGEX = NetRegex.compile("^(?:\\[.+?\\])+", 0);

    private static final List<String> NUMBERS = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine");

    private static final NetRegex MULTI_REGEX = NetRegex.compile("[_. ](?<multi>multi)[_. ]", NetRegex.IGNORE_CASE);

    private static final DateTimeFormatter AIR_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Parser() {
    }

    /**
     * Parses a file's path: its name, or its season folder with a bare episode number, or its folder's name.
     *
     * @param path the file's path
     * @return what it says, or {@code null}
     */
    public static ParsedEpisodeInfo parsePath(String path) {
        String fileName = ParserText.fileName(path);
        String withoutName = path.substring(0, path.length() - fileName.length()).replaceAll("[/\\\\]+$", "");
        String directoryName = withoutName.isEmpty() ? null : ParserText.fileName(withoutName);
        ParsedEpisodeInfo result = parseTitle(fileName);

        NetMatch episodeNumberMatch = SIMPLE_EPISODE_NUMBER_REGEX.match(fileName);
        if (episodeNumberMatch.success() && directoryName != null
                && (result == null || result.isMiniSeries || result.absoluteEpisodeNumbers.length > 0)) {
            NetMatch seasonMatch = SEASON_FOLDER_REGEX.match(directoryName);
            if (seasonMatch.success() && seasonMatch.group("season").success()) {
                List<NetCapture> episodeCaptures = episodeNumberMatch.group("episode").captures();
                int first = parseNumber(episodeCaptures.get(0).value());
                int last = parseNumber(episodeCaptures.get(episodeCaptures.size() - 1).value());
                String pathTitle = String.format("S%sE%02d", seasonMatch.group("season").value(), first);
                if (first != last) {
                    pathTitle += String.format("-E%02d", last);
                }
                if (episodeNumberMatch.group("remaining").success()) {
                    pathTitle += " " + episodeNumberMatch.group("remaining").value();
                }
                return parseTitle(pathTitle);
            }
        }

        Integer number = ParserText.tryParseInt(ParserText.nameWithoutExtension(fileName));
        if (result == null && number != null && directoryName != null) {
            result = parseTitle(directoryName);
            if (result != null && contains(result.absoluteEpisodeNumbers, number)) {
                result.absoluteEpisodeNumbers = new int[] {number};
            } else if (result != null && contains(result.episodeNumbers, number)) {
                result.episodeNumbers = new int[] {number};
            } else {
                result = null;
            }
        }
        if (result == null && directoryName != null) {
            result = parseTitle(directoryName + " " + fileName);
        }
        if (result == null && directoryName != null) {
            result = parseTitle(directoryName + ParserText.extension(fileName));
        }
        return result;
    }

    /**
     * The title as Sonarr cleans it before parsing: reversed titles turned around, websites and torrent suffixes
     * removed.
     *
     * @param title the title
     * @return the simpler title
     */
    public static String simplifyTitle(String title) {
        if (!validateBeforeParsing(title)) {
            return title;
        }
        title = unreverse(title);
        String simpleTitle = WEBSITE_PREFIX_REGEX.replace(title);
        simpleTitle = WEBSITE_POSTFIX_REGEX.replace(simpleTitle);
        return CLEAN_TORRENT_SUFFIX_REGEX.replace(simpleTitle);
    }

    /**
     * Parses a release's title or a file's name.
     *
     * @param title the title
     * @return what it says, or {@code null} when it cannot be parsed
     */
    public static ParsedEpisodeInfo parseTitle(String title) {
        try {
            if (!validateBeforeParsing(title)) {
                return null;
            }
            title = unreverse(title);
            String releaseTitle = removeFileExtension(title);
            releaseTitle = releaseTitle.replace("【", "[").replace("】", "]");
            for (RegexReplace replace : PRE_SUBSTITUTION_REGEX) {
                releaseTitle = replace.replace(releaseTitle);
            }

            String simpleTitle = SIMPLE_TITLE_REGEX.replace(releaseTitle);
            simpleTitle = WEBSITE_PREFIX_REGEX.replace(simpleTitle);
            simpleTitle = WEBSITE_POSTFIX_REGEX.replace(simpleTitle);
            simpleTitle = CLEAN_TORRENT_SUFFIX_REGEX.replace(simpleTitle);
            simpleTitle = CLEAN_QUALITY_BRACKETS_REGEX.replace(simpleTitle, m ->
                    !QualityParser.parseQualityName(m.value()).quality.equals(Qualities.UNKNOWN) ? "" : m.value());

            NetMatch sixDigitAirDateMatch = SIX_DIGIT_AIR_DATE_REGEX.match(simpleTitle);
            if (sixDigitAirDateMatch.success()) {
                String airYear = sixDigitAirDateMatch.group("airyear").value();
                String airMonth = sixDigitAirDateMatch.group("airmonth").value();
                String airDay = sixDigitAirDateMatch.group("airday").value();
                if (!airMonth.equals("00") || !airDay.equals("00")) {
                    String fixedDate = String.format("20%s.%s.%s", airYear, airMonth, airDay);
                    simpleTitle = simpleTitle.replace(sixDigitAirDateMatch.group("airdate").value(), fixedDate);
                }
            }

            for (NetRegex regex : REPORT_TITLE_REGEX) {
                List<NetMatch> match = regex.matches(simpleTitle);
                if (match.isEmpty()) {
                    continue;
                }
                ParsedEpisodeInfo result;
                try {
                    result = parseMatchCollection(match, releaseTitle);
                } catch (InvalidDateException e) {
                    break;
                }
                if (result == null) {
                    continue;
                }
                if (result.fullSeason && ParserText.containsIgnoreCase(result.releaseTokens, "Special")) {
                    result.fullSeason = false;
                    result.special = true;
                }
                result.languages = LanguageParser.parseLanguages(result.releaseTokens);
                result.quality = QualityParser.parseQuality(title);
                result.releaseGroup = parseReleaseGroup(releaseTitle);
                String subGroup = getSubGroup(match);
                if (!isBlank(subGroup)) {
                    result.releaseGroup = subGroup;
                }
                result.releaseHash = getReleaseHash(match);
                return result;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * The series' title of a release, or the cleaned title when it cannot be parsed.
     *
     * @param title the title
     * @return the series' title
     */
    public static String parseSeriesName(String title) {
        ParsedEpisodeInfo parseResult = parseTitle(title);
        return parseResult == null ? cleanSeriesTitle(title) : parseResult.seriesTitle;
    }

    /**
     * A title as Sonarr compares titles: lower case, no punctuation, articles or accents ({@code CleanSeriesTitle}).
     *
     * @param title the title
     * @return the clean title
     */
    public static String cleanSeriesTitle(String title) {
        if (isBlank(title) || ParserText.isLong(title)) {
            return title;
        }
        title = PERCENT_REGEX.replace(title, "percent");
        return ParserText.removeAccent(NORMALIZE_REGEX.replace(title).toLowerCase(Locale.ROOT));
    }

    /**
     * An episode's title as Sonarr compares them.
     *
     * @param title the title
     * @return the normalized title
     */
    public static String normalizeEpisodeTitle(String title) {
        for (NetRegex regex : SPECIAL_EPISODE_TITLE_REGEX) {
            NetMatch match = regex.match(title);
            if (match.success()) {
                title = match.group("episodetitle").value();
                break;
            }
        }
        title = PART_REGEX.replace(title, "");
        title = PUNCTUATION_REGEX.replace(title, " ");
        title = DUPLICATE_SPACES_REGEX.replace(title, " ");
        return title.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * A title without punctuation, a leading article or double spaces, in lower case.
     *
     * @param title the title
     * @return the normalized title
     */
    public static String normalizeTitle(String title) {
        title = PUNCTUATION_REGEX.replace(title, "");
        title = ARTICLE_WORD_REGEX.replace(title, "");
        title = DUPLICATE_SPACES_REGEX.replace(title, " ");
        return title.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * An IMDb id as {@code tt} and at least seven digits.
     *
     * @param imdbId the id, with or without {@code tt}
     * @return the id, or {@code null} when it is not one
     */
    public static String normalizeImdbId(String imdbId) {
        if (!imdbId.matches("^(\\d{1,10}|(tt)\\d{1,10})$") || imdbId.length() <= 2) {
            return null;
        }
        String digits = imdbId.replace("tt", "");
        return "tt" + "0".repeat(Math.max(0, 7 - digits.length())) + digits;
    }

    /**
     * A release's group: {@code -GROUP} at the end, an anime release's {@code [Group]} first, or a known exception.
     *
     * @param title the release's title
     * @return the group, or {@code null}
     */
    public static String parseReleaseGroup(String title) {
        title = title.strip();
        title = removeFileExtension(title);
        for (RegexReplace replace : PRE_SUBSTITUTION_REGEX) {
            boolean matched = replace.matches(title);
            title = replace.replace(title);
            if (matched) {
                break;
            }
        }
        title = WEBSITE_PREFIX_REGEX.replace(title);
        title = CLEAN_TORRENT_SUFFIX_REGEX.replace(title);

        NetMatch animeMatch = ANIME_RELEASE_GROUP_REGEX.match(title);
        if (animeMatch.success()) {
            return animeMatch.group("subgroup").value();
        }

        title = CLEAN_RELEASE_GROUP_REGEX.replace(title);

        List<NetMatch> exception = EXCEPTION_RELEASE_GROUP_REGEX.matches(title);
        if (!exception.isEmpty()) {
            return exception.get(exception.size() - 1).group("releasegroup").value();
        }
        List<NetMatch> exceptionExact = EXCEPTION_RELEASE_GROUP_REGEX_EXACT.matches(title);
        if (!exceptionExact.isEmpty()) {
            return exceptionExact.get(exceptionExact.size() - 1).group("releasegroup").value();
        }
        List<NetMatch> matches = RELEASE_GROUP_REGEX.matches(title);
        if (!matches.isEmpty()) {
            String group = matches.get(matches.size() - 1).group("releasegroup").value();
            if (ParserText.tryParseInt(group) != null || INVALID_RELEASE_GROUP_REGEX.isMatch(group)) {
                return null;
            }
            return group;
        }
        return null;
    }

    /**
     * A title without a video file's extension (other extensions stay).
     *
     * @param title the title
     * @return the title
     */
    public static String removeFileExtension(String title) {
        return FILE_EXTENSION_REGEX.replace(title, m -> {
            String extension = m.value().toLowerCase(Locale.ROOT);
            return MediaFileExtensions.isVideo(extension) || extension.equals(".par2") || extension.equals(".nzb")
                    ? "" : m.value();
        });
    }

    /**
     * Whether a release says it has several languages ({@code MULTi}).
     *
     * @param title the title
     * @return whether it does
     */
    public static boolean hasMultipleLanguages(String title) {
        return MULTI_REGEX.isMatch(title);
    }

    private static String unreverse(String title) {
        if (!REVERSED_TITLE_REGEX.isMatch(title)) {
            return title;
        }
        String withoutExtension = removeFileExtension(title);
        return new StringBuilder(withoutExtension).reverse() + title.substring(withoutExtension.length());
    }

    private static SeriesTitleInfo getSeriesTitleInfo(String title, List<NetMatch> matchCollection) {
        SeriesTitleInfo info = new SeriesTitleInfo();
        info.title = title;
        NetMatch match = YEAR_IN_TITLE_REGEX.match(title);
        if (!match.success()) {
            info.titleWithoutYear = title;
        } else {
            info.titleWithoutYear = match.group("title").value();
            info.year = ParserText.toInt(match.group("year").value());
        }
        NetMatch matchComponents = TITLE_COMPONENTS_REGEX.match(info.titleWithoutYear);
        if (matchComponents.success()) {
            info.allTitles = matchComponents.group("title").captures().stream().map(NetCapture::value)
                    .toArray(String[]::new);
        } else if (matchCollection.get(0).group("title").captures().size() > 1) {
            info.allTitles = matchCollection.get(0).group("title").captures().stream()
                    .map(c -> c.value().replace('.', ' ').replace('_', ' ')).toArray(String[]::new);
        }
        return info;
    }

    private static ParsedEpisodeInfo parseMatchCollection(List<NetMatch> matchCollection, String releaseTitle) {
        NetMatch first0 = matchCollection.get(0);
        String seriesName = first0.group("title").value().replace('.', ' ').replace('_', ' ');
        seriesName = strip(REQUEST_INFO_REGEX.replace(seriesName, ""), ' ');

        Integer parsedAirYear = ParserText.tryParseInt(first0.group("airyear").value());
        int airYear = parsedAirYear == null ? 0 : parsedAirYear;
        int lastSeasonEpisodeStringIndex = first0.group("title").endIndex();
        ParsedEpisodeInfo result;

        if (airYear < 1900) {
            result = new ParsedEpisodeInfo();
            result.releaseTitle = releaseTitle;
            for (NetMatch matchGroup : matchCollection) {
                List<NetCapture> episodeCaptures = matchGroup.group("episode").captures();
                List<NetCapture> absoluteEpisodeCaptures = matchGroup.group("absoluteepisode").captures();

                if (!episodeCaptures.isEmpty()) {
                    int first = parseNumber(episodeCaptures.get(0).value());
                    int last = parseNumber(episodeCaptures.get(episodeCaptures.size() - 1).value());
                    if (first > last) {
                        return null;
                    }
                    result.episodeNumbers = IntStream.rangeClosed(first, last).toArray();
                    lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex,
                            episodeCaptures.get(episodeCaptures.size() - 1).endIndex());
                    if (matchGroup.group("special").success()) {
                        result.special = true;
                    }
                    if (matchGroup.group("splitepisode").success()) {
                        result.isSplitEpisode = true;
                    }
                }

                if (!absoluteEpisodeCaptures.isEmpty()) {
                    BigDecimal first = parseDecimal(absoluteEpisodeCaptures.get(0).value());
                    BigDecimal last = parseDecimal(absoluteEpisodeCaptures.get(absoluteEpisodeCaptures.size() - 1).value());
                    if (first.compareTo(last) > 0) {
                        return null;
                    }
                    if (!isWhole(first) || !isWhole(last)) {
                        if (absoluteEpisodeCaptures.size() != 1) {
                            return null;
                        }
                        result.specialAbsoluteEpisodeNumbers = new BigDecimal[] {first};
                        result.special = true;
                        lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex,
                                absoluteEpisodeCaptures.get(0).endIndex());
                    } else {
                        result.absoluteEpisodeNumbers = IntStream.rangeClosed(first.intValue(), last.intValue()).toArray();
                        if (matchGroup.group("special").success()) {
                            result.special = true;
                        }
                        lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex,
                                absoluteEpisodeCaptures.get(absoluteEpisodeCaptures.size() - 1).endIndex());
                    }
                }

                if (episodeCaptures.isEmpty() && absoluteEpisodeCaptures.isEmpty()) {
                    if (!isBlank(first0.group("extras").value())) {
                        result.isSeasonExtra = true;
                    }
                    String seasonPart = first0.group("seasonpart").value();
                    if (!isBlank(seasonPart)) {
                        result.seasonPart = ParserText.toInt(seasonPart);
                        result.isPartialSeason = true;
                    } else {
                        result.fullSeason = true;
                    }
                }

                if (episodeCaptures.size() == 2 && first0.group("episodecount").success()
                        && episodeCaptures.get(1).value().equals(first0.group("episodecount").value())) {
                    result.episodeNumbers = new int[0];
                    result.fullSeason = true;
                }
            }

            List<Integer> seasons = new ArrayList<>();
            for (NetCapture seasonCapture : first0.group("season").captures()) {
                Integer parsedSeason = ParserText.tryParseInt(seasonCapture.value());
                if (parsedSeason != null) {
                    seasons.add(parsedSeason);
                    lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex, seasonCapture.endIndex());
                }
            }
            if (seasons.stream().distinct().count() > 1) {
                result.isMultiSeason = true;
            }
            if (!seasons.isEmpty()) {
                result.seasonNumber = seasons.get(0);
            } else if (result.absoluteEpisodeNumbers.length == 0 && result.episodeNumbers.length > 0) {
                result.seasonNumber = 1;
                result.isMiniSeries = true;
            }
        } else {
            int airmonth;
            int airday;
            if (first0.group("ambiguousairmonth").success() && first0.group("ambiguousairday").success()) {
                int ambiguousAirMonth = ParserText.toInt(first0.group("ambiguousairmonth").value());
                int ambiguousAirDay = ParserText.toInt(first0.group("ambiguousairday").value());
                if (ambiguousAirDay <= 12 && ambiguousAirMonth <= 12) {
                    throw new InvalidDateException("Ambiguous Date, cannot validate month and day with "
                            + ambiguousAirMonth + " and " + ambiguousAirDay);
                }
                airmonth = ambiguousAirMonth;
                airday = ambiguousAirDay;
            } else {
                airmonth = ParserText.toInt(first0.group("airmonth").value());
                airday = ParserText.toInt(first0.group("airday").value());
                if (airmonth > 12) {
                    int tempDay = airday;
                    airday = airmonth;
                    airmonth = tempDay;
                }
            }
            LocalDate airDate;
            try {
                airDate = LocalDate.of(airYear, airmonth, airday);
            } catch (java.time.DateTimeException e) {
                throw new InvalidDateException("Invalid date found: " + airYear + "-" + airmonth + "-" + airday);
            }
            if (airDate.isAfter(LocalDate.now().plusDays(1))) {
                throw new InvalidDateException("Invalid date found: " + airDate);
            }
            if (airDate.isBefore(LocalDate.of(1970, 1, 1)) && isBlank(first0.group("titleyear").value())) {
                throw new InvalidDateException("Invalid date found: " + airDate);
            }
            lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex, first0.group("airyear").endIndex());
            lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex, first0.group("airmonth").endIndex());
            lastSeasonEpisodeStringIndex = Math.max(lastSeasonEpisodeStringIndex, first0.group("airday").endIndex());

            result = new ParsedEpisodeInfo();
            result.releaseTitle = releaseTitle;
            result.airDate = airDate.format(AIR_DATE_FORMAT);
            if (first0.group("part").success()) {
                result.dailyPart = ParserText.toInt(first0.group("part").value());
            }
        }

        result.releaseTokens = lastSeasonEpisodeStringIndex < releaseTitle.length()
                ? releaseTitle.substring(lastSeasonEpisodeStringIndex) : releaseTitle;
        result.seriesTitle = seriesName;
        result.seriesTitleInfo = getSeriesTitleInfo(result.seriesTitle, matchCollection);
        return result;
    }

    private static boolean validateBeforeParsing(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("password") && lower.contains("yenc")) {
            return false;
        }
        if (title.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return false;
        }
        String titleWithoutExtension = removeFileExtension(title);
        return Arrays.stream(REJECT_HASHED_RELEASES_REGEXES).noneMatch(v -> v.isMatch(titleWithoutExtension))
                && Arrays.stream(SEASON_FOLDER_REGEXES).noneMatch(v -> v.isMatch(titleWithoutExtension));
    }

    private static String getSubGroup(List<NetMatch> matchCollection) {
        NetGroup subGroup = matchCollection.get(0).group("subgroup");
        return subGroup.success() ? subGroup.value() : "";
    }

    private static String getReleaseHash(List<NetMatch> matchCollection) {
        NetGroup hash = matchCollection.get(0).group("hash");
        if (!hash.success()) {
            return "";
        }
        String hashValue = hash.value().replaceAll("^[\\[\\]()]+|[\\[\\]()]+$", "");
        return hashValue.equals("1280x720") ? "" : hashValue;
    }

    private static int parseNumber(String value) {
        String normalized = convertToNumerals(Normalizer.normalize(value, Normalizer.Form.NFKC));
        Integer number = ParserText.tryParseInt(normalized);
        if (number != null) {
            return number;
        }
        int index = NUMBERS.indexOf(value.toLowerCase(Locale.ROOT));
        if (index != -1) {
            return index;
        }
        throw new NumberFormatException(value + " isn't a number");
    }

    private static BigDecimal parseDecimal(String value) {
        String normalized = convertToNumerals(Normalizer.normalize(value, Normalizer.Form.NFKC)).strip();
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new NumberFormatException(value + " isn't a number");
        }
    }

    private static String convertToNumerals(String input) {
        StringBuilder result = new StringBuilder(input.length());
        input.codePoints().forEach(c -> {
            if (Character.isDigit(c) || Character.getType(c) == Character.LETTER_NUMBER
                    || Character.getType(c) == Character.OTHER_NUMBER) {
                int value = Character.getNumericValue(c);
                if (value >= 0) {
                    result.append(value);
                } else {
                    result.appendCodePoint(c);
                }
            } else {
                result.appendCodePoint(c);
            }
        });
        return result.toString();
    }

    private static boolean isWhole(BigDecimal number) {
        return number.stripTrailingZeros().scale() <= 0;
    }

    private static boolean contains(int[] numbers, int number) {
        return Arrays.stream(numbers).anyMatch(n -> n == number);
    }

    private static String strip(String text, char c) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == c) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == c) {
            end--;
        }
        return text.substring(start, end);
    }
}
