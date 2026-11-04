package com.shinbhairava.tdflew.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveMetadataTest {
    @Test
    fun fileMarkerRoundTripsUnicodePaths() {
        val path = "/Photos/Ärger & Freunde/naïve 東京.jpg"

        val marker = DriveMetadata.encodeFile(path)

        assertEquals(path, DriveMetadata.parseFilePath(marker))
        assertEquals(path, DriveMetadata.parseFilePath("  $marker  "))
    }

    @Test
    fun markerUsesCompactUrlSafeBase64WithoutPadding() {
        val marker = DriveMetadata.encodeFile("/a/b.txt")

        assertEquals("TDFLEW1:F:", marker.take("TDFLEW1:F:".length))
        assertEquals(false, marker.contains('+'))
        assertEquals(false, marker.contains('/'))
        assertEquals(false, marker.contains('='))
    }

    @Test
    fun folderMarkerRoundTripsRoot() {
        val marker = DriveMetadata.encodeFolder("/")

        assertEquals("/", DriveMetadata.parseFolderPath(marker))
    }

    @Test
    fun rejectsRootFileAndMalformedMarkers() {
        assertThrows(IllegalArgumentException::class.java) {
            DriveMetadata.encodeFile("/")
        }
        assertNull(DriveMetadata.parseFilePath("TDFLEW1:F:"))
        assertNull(DriveMetadata.parseFilePath("TDFLEW1:F:not*base64"))
        assertNull(DriveMetadata.parseFilePath("ordinary caption"))
        assertNull(DriveMetadata.parseFolderPath("TDFLEW1:F:/wrong-kind"))
    }

    @Test
    fun normalizesSeparatorsAndRelativePaths() {
        assertEquals("/a/b", DriveMetadata.normalizeFilePath("a//b/"))
        assertNull(DriveMetadata.normalizeFolderPath(""))
        assertEquals("/", DriveMetadata.normalizeFolderPath("/"))
        assertNull(DriveMetadata.normalizeFolderPath("/a/../b"))
        assertNull(DriveMetadata.normalizeFolderPath("/a/./b"))
    }

    @Test
    fun sanitizesNamesAndLongExtensions() {
        assertEquals("Notes.txt", DriveMetadata.sanitizeName("  Notes.txt  "))
        assertEquals("fallback", DriveMetadata.sanitizeName("folder\\fallback"))
        assertNull(DriveMetadata.sanitizeName("..."))
        assertNull(DriveMetadata.sanitizeName("\u0000\u0001"))

        val longName = "x".repeat(200) + ".jpeg"
        val sanitized = DriveMetadata.sanitizeName(longName)
        assertEquals(DriveMetadata.MAX_NAME_LENGTH, sanitized?.length)
        assertEquals(true, sanitized?.endsWith(".jpeg"))
    }

    @Test
    fun childPathStaysInsideParent() {
        assertEquals("/to/do.txt", DriveMetadata.childPath("/to", "do.txt"))
        assertEquals("/do.txt", DriveMetadata.childPath("/", "do.txt"))
        assertNull(DriveMetadata.childPath("/to", ".."))
        assertEquals("/to/absolute.txt", DriveMetadata.childPath("/to", "/absolute.txt"))
    }

    @Test
    fun parentAndNameHandleNestedPaths() {
        assertEquals("/one/two", DriveMetadata.parentOf("/one/two/file.txt"))
        assertEquals("/", DriveMetadata.parentOf("/file.txt"))
        assertEquals("file.txt", DriveMetadata.nameOf("/one/two/file.txt"))
        assertEquals("", DriveMetadata.nameOf("/"))
    }

    @Test
    fun rejectsOverlongPaths() {
        val deep = "/" + (1..10).joinToString("/") { "segment$it" }
        val overlong = deep + "/" + "y".repeat(DriveMetadata.MAX_PATH_LENGTH)
        assertNull(DriveMetadata.normalizeFilePath(overlong))
    }

    @Test
    fun rejectsPathsThatWouldOverflowTelegramCaptions() {
        val segment = "東".repeat(DriveMetadata.MAX_NAME_LENGTH)
        val path = "/" + List(4) { segment }.joinToString("/")

        assertTrue(path.length <= DriveMetadata.MAX_PATH_LENGTH)
        assertNull(DriveMetadata.normalizeFilePath(path))
        assertThrows(IllegalArgumentException::class.java) {
            DriveMetadata.encodeFile(path)
        }
    }
}
