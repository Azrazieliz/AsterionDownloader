package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class CreatorArchivesTest {
    private val creator=ArchiveCreator(CreatorArchiveHost.PAWCHIVE,"patreon","1234","Test Artist")

    @Test fun creatorUrlsRequireExactTrustedHostsAndPaths(){
        assertEquals("1234",CreatorArchives.parseCreatorUrl(
            "https://pawchive.pw/patreon/user/1234")?.id)
        assertEquals(CreatorArchiveHost.COOMER,CreatorArchives.parseCreatorUrl(
            "https://coomer.st/fansly/user/89/posts")?.host)
        assertNull(CreatorArchives.parseCreatorUrl(
            "https://pawchive.pw.evil.example/patreon/user/1234"))
        assertNull(CreatorArchives.parseCreatorUrl(
            "https://pawchive.pw/patreon/other/1234"))
    }

    @Test fun acceptedFilePathsStayOnTheArchiveFileHost(){
        assertEquals("https://file.pawchive.pw/data/12/abc.jpg",
            CreatorArchives.fileUrl(CreatorArchiveHost.PAWCHIVE,"/12/abc.jpg"))
        assertEquals("https://file.pawchive.pw/data/12/abc.jpg",
            CreatorArchives.fileUrl(CreatorArchiveHost.PAWCHIVE,"/data/12/abc.jpg"))
        assertNull(CreatorArchives.fileUrl(CreatorArchiveHost.PAWCHIVE,
            "https://evil.example/data/anything"))
        assertNull(CreatorArchives.fileUrl(CreatorArchiveHost.KEMONO,
            "/../secrets.zip"))
    }

    @Test fun postParserDeduplicatesAttachmentsAndRejectsOtherCreators(){
        val source="""[
          {"id":"first","user":"1234","service":"patreon","title":"Main post",
           "published":"2026-10-31",
           "file":{"name":"cover.png","path":"/ab/one.png"},
           "attachments":[
             {"name":"duplicate.png","path":"/ab/one.png"},
             {"name":"book.zip","path":"/ab/book.zip"},
             {"name":"unsafe","path":"https://outside.example/abc"}
           ]},
          {"id":"foreign","user":"9999","service":"patreon",
           "file":{"name":"not-ours","path":"/bad"}}
        ]"""
        val parsed=CreatorArchives.parsePosts(source,creator)
        assertEquals(1,parsed.size)
        assertEquals("Main post",parsed.first().title)
        assertEquals(2,parsed.first().files.size)
        assertEquals("https://file.pawchive.pw/data/ab/book.zip",
            parsed.first().files[1].url)
    }

    @Test fun postParserSupportsObjectResponses(){
        val source="""{"posts":[{"id":"10","title":"An upload",
             "attachments":[{"path":"/12/image.jpg","name":"image.jpg"}]}]}"""
        val parsed=CreatorArchives.parsePosts(source,creator)
        assertEquals(1,parsed.size)
        assertEquals(1,parsed.first().files.size)
    }
}
