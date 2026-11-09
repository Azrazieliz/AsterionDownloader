package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OrderedContentTest {
    @Test fun chaptersVolumesEpisodesAndPagesAreNumerical(){
        val chapters=listOf("Chapter 10","Chapter 2.5","Chapter 1","Chapter 2")
        assertEquals(listOf("Chapter 1","Chapter 2","Chapter 2.5","Chapter 10"),
            SequenceOrder.arrange(chapters,SequenceOrder.Kind.CHAPTER,{it}))
        val episodes=listOf("Show S02E01","Show S01E12","Show S01E03")
        assertEquals(listOf("Show S01E03","Show S01E12","Show S02E01"),
            SequenceOrder.arrange(episodes,SequenceOrder.Kind.EPISODE,{it}))
        val volumes=listOf("Volume 10","Volume 2","Volume 1")
        assertEquals(listOf("Volume 1","Volume 2","Volume 10"),
            SequenceOrder.arrange(volumes,SequenceOrder.Kind.VOLUME,{it}))
        assertEquals(listOf("Chapter 1","Volume 2","Chapter 10"),
            SequenceOrder.arrange(listOf("Chapter 10","Volume 2","Chapter 1"),
                SequenceOrder.Kind.CHAPTER,{it}))
    }
    @Test fun explicitComicPageNamesRestoreAuthoredOrder(){
        val original=listOf(
            "https://reader.example/pages/010.jpg",
            "https://reader.example/pages/002.jpg",
            "https://reader.example/pages/001.png")
        assertEquals(listOf(original[2],original[1],original[0]),
            SequenceOrder.comicPages(original))
    }
    @Test fun unnumberedComicReaderPreservesDomOrder(){
        val images=listOf("https://cdn.example/a.jpg",
            "https://cdn.example/c.jpg","https://cdn.example/b.jpg")
        assertEquals(images,SequenceOrder.comicPages(images))
    }
    @Test fun albumNeverInheritsBookSorting(){
        val photos=listOf("photo-25.jpg","photo-1.jpg","photo-10.jpg")
        assertEquals(photos,SequenceOrder.arrange(photos,
            SequenceOrder.Kind.UNORDERED,{it}))
        assertEquals(photos,photos.toList())
    }
    @Test fun numberedVolumeQueueWaitsForGaps(){
        assertEquals(3,SequenceOrder.nextVolume(3,emptySet(),setOf(3,4,5)))
        assertEquals(4,SequenceOrder.nextVolume(3,setOf(3),setOf(4,5)))
        assertNull(SequenceOrder.nextVolume(3,setOf(3),setOf(5)))
        assertNull(SequenceOrder.nextVolume(3,emptySet(),setOf(4,5)))
        assertEquals(8,SequenceOrder.nextVolume(7,setOf(7),setOf(8)))
    }
    private fun zip(vararg files:Pair<String,String>):ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use{zip->
            for((name,body) in files){
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    @Test fun imageZipVerificationRequiresExactPageCount(){
        val bytes=zip("00001_00001.jpg" to "page 1",
            "00001_00002.png" to "page 2")
        val result=ArchiveIntegrity.inspect(ByteArrayInputStream(bytes),"ZIP",2)
        assertEquals(2,result.images)
        assertThrows(IllegalArgumentException::class.java){
            ArchiveIntegrity.inspect(ByteArrayInputStream(bytes),"ZIP",3)
        }
    }
    @Test fun htmlOrOtherNondownloadResponseCannotPassAsZip(){
        assertThrows(Exception::class.java){
            ArchiveIntegrity.inspect(ByteArrayInputStream(
                "<html>Login required</html>".toByteArray()),"ZIP")
        }
    }
    @Test fun EPUBMustContainCompleteSpineMaterials(){
        val missing=zip("OEBPS/chapter-00001.xhtml" to "<p>text</p>")
        assertThrows(IllegalArgumentException::class.java){
            ArchiveIntegrity.inspect(ByteArrayInputStream(missing),"EPUB")
        }
    }
}
