package com.shinbhairava.tdflew.engine

import com.shinbhairava.tdflew.drive.DriveItem
import com.shinbhairava.tdflew.drive.DriveItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GalleryFilterTest {
    private val today = LocalDate.of(2026, 11, 4)

    @Test
    fun parsesFreeTextOnly() {
        val query = GalleryQuery.parse("sunset  beach")

        assertEquals("sunset beach", query.text)
        assertNull(query.dateRange)
    }

    @Test
    fun parsesISODateTags() {
        val query = GalleryQuery.parse("date:2026-11-04")

        assertEquals("", query.text)
        assertEquals(LocalDate.of(2026, 11, 4), query.dateRange?.start)
        assertEquals(LocalDate.of(2026, 11, 5), query.dateRange?.endExclusive)
    }

    @Test
    fun parsesYearAndMonthTags() {
        val year = GalleryQuery.parse("on:2026")
        assertEquals(LocalDate.of(2026, 1, 1), year.dateRange?.start)
        assertEquals(LocalDate.of(2027, 1, 1), year.dateRange?.endExclusive)

        val month = GalleryQuery.parse("date:2026-02")
        assertEquals(LocalDate.of(2026, 2, 1), month.dateRange?.start)
        assertEquals(LocalDate.of(2026, 3, 1), month.dateRange?.endExclusive)
    }

    @Test
    fun bareDateTokenBecomesDateFilter() {
        val query = GalleryQuery.parse("2026-11-04 holiday")

        assertEquals("holiday", query.text)
        assertEquals(LocalDate.of(2026, 11, 4), query.dateRange?.start)
    }

    @Test
    fun invalidDatesRemainSearchText() {
        val query = GalleryQuery.parse("from:2026-99-99 beach")

        assertEquals("from:2026-99-99 beach", query.text)
        assertNull(query.dateRange)
    }

    @Test
    fun combinesFromAndToBounds() {
        val query = GalleryQuery.parse("from:2026-01-01 to:2026-01-31")

        assertEquals(LocalDate.of(2026, 1, 1), query.dateRange?.start)
        assertEquals(LocalDate.of(2026, 2, 1), query.dateRange?.endExclusive)
    }

    @Test
    fun presetRangesUseInclusiveStartAndExclusiveEnd() {
        assertEquals(
            DateRange(today, today.plusDays(1)),
            galleryPresetRange(GalleryPreset.TODAY, today),
        )
        assertEquals(
            DateRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9)),
            galleryPresetRange(GalleryPreset.THIS_WEEK, today),
        )
        assertEquals(
            DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)),
            galleryPresetRange(GalleryPreset.THIS_MONTH, today),
        )
        assertEquals(
            DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)),
            galleryPresetRange(GalleryPreset.THIS_YEAR, today),
        )
        assertNull(galleryPresetRange(GalleryPreset.ALL, today))
    }

    @Test
    fun filtersByPresetAndText() {
        val items = listOf(
            item("Beach sunset", "/Beach sunset.jpg", 2026, 11, 4),
            item("Beach day", "/Beach day.jpg", 2026, 11, 3),
            item("Mountain", "/Mountain.jpg", 2026, 10, 30),
            item("Old beach", "/Old beach.jpg", 2020, 1, 1),
        )

        val result = filterGallery(
            items = items,
            query = GalleryQuery.parse("beach"),
            preset = GalleryPreset.THIS_MONTH,
            today = today,
        )

        assertEquals(listOf("Beach sunset", "Beach day"), result.map { it.name })
    }

    @Test
    fun explicitQueryNarrowsPreset() {
        val items = listOf(
            item("Today", "/Today.jpg", 2026, 11, 4),
            item("Yesterday", "/Yesterday.jpg", 2026, 11, 3),
        )

        val result = filterGallery(
            items = items,
            query = GalleryQuery.parse("date:2026-11-03"),
            preset = GalleryPreset.ALL,
            today = today,
        )

        assertEquals(listOf("Yesterday"), result.map { it.name })
    }

    @Test
    fun nonOverlappingQueryAndPresetReturnNoItems() {
        val items = listOf(
            item("Today", "/Today.jpg", 2026, 11, 4),
            item("Old", "/Old.jpg", 2020, 1, 1),
        )

        val result = filterGallery(
            items = items,
            query = GalleryQuery.parse("date:2020-01-01"),
            preset = GalleryPreset.TODAY,
            today = today,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun sortsNewestFirst() {
        val items = listOf(
            item("Older", "/Older.jpg", 2026, 1, 1),
            item("Newer", "/Newer.jpg", 2026, 2, 1),
        )

        val result = filterGallery(items, GalleryQuery.Empty, GalleryPreset.ALL, today)

        assertEquals(listOf("Newer", "Older"), result.map { it.name })
    }

    @Test
    fun dateRangeContainsHonoursExclusiveEnd() {
        val range = DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1))

        assertTrue(range.contains(LocalDate.of(2026, 11, 1)))
        assertTrue(range.contains(LocalDate.of(2026, 11, 30)))
        assertEquals(false, range.contains(LocalDate.of(2026, 12, 1)))
    }

    private fun item(
        name: String,
        path: String,
        year: Int,
        month: Int,
        day: Int,
    ): DriveItem {
        val timestamp = LocalDate.of(year, month, day)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return DriveItem(
            id = path,
            messageId = path.hashCode().toLong(),
            path = path,
            parentPath = "/",
            name = name,
            kind = DriveItemKind.PHOTO,
            size = 1_024,
            timestamp = timestamp,
            mimeType = "image/jpeg",
        )
    }
}
