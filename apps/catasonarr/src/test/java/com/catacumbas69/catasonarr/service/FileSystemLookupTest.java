package com.catacumbas69.catasonarr.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemLookupTest {

    private final FileSystemLookup lookup = new FileSystemLookup();

    @TempDir
    private Path root;

    @Test
    void lookup_listsTheFoldersByName_hidingSystemAndDotFolders() throws Exception {
        Files.createDirectories(root.resolve("Series"));
        Files.createDirectories(root.resolve("anime"));
        Files.createDirectories(root.resolve("#recycle"));
        Files.createDirectories(root.resolve("@eaDir"));
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve("notes.txt"), "x");

        FileSystemLookup.Contents contents = lookup.lookup(root + "/", false, false);

        assertThat(contents.directories()).extracting(FileSystemLookup.Entry::name).containsExactly("anime", "Series");
        assertThat(contents.directories().get(1).path()).isEqualTo(root.resolve("Series") + "/");
        assertThat(contents.directories().get(1).type()).isEqualTo("folder");
        assertThat(contents.files()).isEmpty();
        assertThat(contents.parent()).isEqualTo(root.getParent() + "/");
    }

    @Test
    void lookup_whatIsTypedUpToTheLastSlashIsTheFolder_unlessTheWholeTextIsOneAndThatIsAllowed() throws Exception {
        Files.createDirectories(root.resolve("Series/Show"));

        assertThat(lookup.lookup(root + "/Ser", false, false).directories())
                .extracting(FileSystemLookup.Entry::name).containsExactly("Series");
        assertThat(lookup.lookup(root + "/Series", false, false).directories())
                .extracting(FileSystemLookup.Entry::name).containsExactly("Series");
        assertThat(lookup.lookup(root + "/Series", false, true).directories())
                .extracting(FileSystemLookup.Entry::name).containsExactly("Show");
    }

    @Test
    void lookup_filesWhenAsked_withExtensionAndSize() throws Exception {
        Files.writeString(root.resolve("episode.mkv"), "12345");

        FileSystemLookup.Entry file = lookup.lookup(root + "/", true, false).files().get(0);

        assertThat(file.type()).isEqualTo("file");
        assertThat(file.extension()).isEqualTo(".mkv");
        assertThat(file.size()).isEqualTo(5);
    }

    @Test
    void lookup_theRootHasNoParent_aMissingFolderOnlyTheWayUp_andWordsNothing() {
        assertThat(lookup.lookup("", false, false).parent()).isNull();
        FileSystemLookup.Contents missing = lookup.lookup(root + "/missing/", false, false);
        assertThat(missing.directories()).isEmpty();
        assertThat(missing.parent()).isEqualTo(root + "/");
        assertThat(lookup.lookup("Series", false, false).directories()).isEmpty();
    }

    @Test
    void type_fileOnlyForFiles() throws Exception {
        Files.writeString(root.resolve("a.mkv"), "x");

        assertThat(lookup.type(root.resolve("a.mkv").toString())).isEqualTo("file");
        assertThat(lookup.type(root.toString())).isEqualTo("folder");
        assertThat(lookup.type(root.resolve("missing").toString())).isEqualTo("folder");
    }

    @Test
    void hidden_sonarrsSystemFoldersInAnyCase_andDotNames() {
        assertThat(FileSystemLookup.hidden("#recycle")).isTrue();
        assertThat(FileSystemLookup.hidden("@EADIR")).isTrue();
        assertThat(FileSystemLookup.hidden(".DS_Store")).isTrue();
        assertThat(FileSystemLookup.hidden("Series")).isFalse();
    }
}
