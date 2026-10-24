package my.gimano.app.ugc

import kotlinx.coroutines.test.runTest
import my.gimano.app.data.account.AccountDeletion
import my.gimano.app.data.model.Author
import my.gimano.app.data.model.Photo
import my.gimano.app.data.model.Review
import my.gimano.app.data.remote.GimanoJson
import my.gimano.app.data.ugc.BlockedAuthor
import my.gimano.app.data.ugc.Blocking
import my.gimano.app.data.ugc.TermsGate
import my.gimano.app.data.ugc.UgcTerms
import my.gimano.app.ui.feedback.FeedbackConsent
import org.junit.Assert.*
import org.junit.Test

/** Apple 1.2 / Play UGC + account deletion + consent defaults. */
class StoreReadinessTest {
    private val badak = Author("Badak 5880", authorRef = "h-badak")
    private val kancil = Author("Kancil 1204", authorRef = "h-kancil")
    private fun photo(id: String, by: Author?) = Photo(id, "/media/$id.webp", uploadedBy = by)
    private fun review(id: String, by: Author, photos: List<Photo> = emptyList()) = Review(id, "p", 4, "x", photos, by)

    // --- Block this user -------------------------------------------------------------------------------------

    @Test fun blockingHidesThatAuthorsReviewsAndPhotosEverywhere() {
        val reviews = listOf(
            review("r1", badak, listOf(photo("a", badak))),
            review("r2", kancil, listOf(photo("b", kancil), photo("c", badak))),   // badak's photo on someone else's review
        )
        val blocked = setOf("h-badak")
        val shown = Blocking.reviews(reviews, blocked)
        assertEquals(listOf("r2"), shown.map { it.id })
        assertEquals(listOf("b"), shown.single().photos.map { it.id })
        assertEquals(listOf("b"), Blocking.photos(listOf(photo("b", kancil), photo("c", badak), photo("d", null)), blocked).map { it.id }.take(1))
        // Photos without an author handle (or the user's own uploads) are never hidden by a block.
        assertEquals(listOf("d"), Blocking.photos(listOf(photo("d", null)), blocked).map { it.id })
        // Nothing blocked: same lists back.
        assertSame(reviews, Blocking.reviews(reviews, emptySet()))
    }

    @Test fun blockRowsAreOnePerAuthorSoARestoreMergesThem() {
        val b = BlockedAuthor("h-badak", "Badak 5880", "2026-10-24T03:00:00Z")
        val key = Blocking.key(b.ref)
        assertEquals("blocked_author:h-badak", key)
        assertEquals(b, Blocking.decode(key, Blocking.encode(b)))
        assertNull(Blocking.decode("planned_drive:x", Blocking.encode(b)))   // other settings rows are ignored
        assertNull(Blocking.decode(key, "not json"))
    }

    @Test fun theAuthorHandleIsParsedFromTheApi() {
        val r = GimanoJson.decodeFromString(Review.serializer(),
            """{"id":"r","placeId":"p","rating":5,"author":{"displayName":"Badak 5880","authorRef":"h-badak"}}""")
        assertEquals("h-badak", r.author.authorRef)
        // docs/api.md names it `authorRef` on photos too (no other names are accepted).
        val p = GimanoJson.decodeFromString(Photo.serializer(), """{"id":"x","uploadedBy":{"displayName":"K","authorRef":"h-k"}}""")
        assertEquals("h-k", p.uploadedBy?.authorRef)
        assertNull(GimanoJson.decodeFromString(Photo.serializer(), """{"id":"x","uploadedBy":{"displayName":"K","handle":"h-k"}}""").uploadedBy?.authorRef)
        // No handle: nothing to block by.
        assertNull(GimanoJson.decodeFromString(Author.serializer(), """{"displayName":"Old"}""").authorRef)
    }

    // --- Community rules (UGC terms) -------------------------------------------------------------------------

    @Test fun rulesAreAskedBeforeTheFirstContributionAndAgainWhenTheVersionChanges() {
        var stored: String? = null
        var ran = 0
        val gate = TermsGate(acceptedVersion = { stored }, persistAcceptance = { /* saved later, asynchronously */ })
        gate.require { ran++ }
        assertEquals("not yet: the rules are shown", 0, ran)
        assertTrue(gate.prompt.value)
        gate.accept()
        assertEquals("the pending action runs after I agree", 1, ran)
        assertFalse(gate.prompt.value)
        gate.require { ran++ }
        assertEquals("accepted this session, before the save lands: no second prompt", 2, ran)

        // A new terms version asks again.
        assertTrue(UgcTerms.needsAcceptance("2025-01-01"))
        assertFalse(UgcTerms.needsAcceptance(UgcTerms.VERSION))
        stored = UgcTerms.VERSION
        val later = TermsGate(acceptedVersion = { stored }, persistAcceptance = {})
        later.require { ran++ }
        assertEquals(3, ran)
    }

    @Test fun notNowDropsTheAction() {
        var ran = false
        var saved: String? = null
        val gate = TermsGate(acceptedVersion = { null }, persistAcceptance = { saved = it })
        gate.require { ran = true }
        gate.decline()
        assertFalse(ran)
        assertFalse(gate.prompt.value)
        assertNull(saved)
        gate.require { ran = true }; gate.accept()
        assertTrue(ran)
        assertEquals(UgcTerms.VERSION, saved)
    }

    // --- Delete my Manotu account ----------------------------------------------------------------------------

    private class Steps(var registered: Boolean = true, val serverError: Exception? = null) {
        val calls = mutableListOf<String>()
        fun deletion() = AccountDeletion(
            isRegistered = { registered },
            deleteOnServer = { calls += "server"; serverError?.let { throw it } },
            forgetIdentity = { calls += "forget" },
            clearLocalData = { calls += "clear" },
        )
    }

    @Test fun serverFirstThenTheKeyAndLocalDataOnlyIfAsked() = runTest {
        Steps().let { s -> assertEquals(AccountDeletion.Outcome.DELETED, s.deletion().run(alsoClearLocal = false)); assertEquals(listOf("server", "forget"), s.calls) }
        Steps().let { s -> assertEquals(AccountDeletion.Outcome.DELETED, s.deletion().run(alsoClearLocal = true)); assertEquals(listOf("server", "forget", "clear"), s.calls) }
        // Never registered: nothing on the server, only the phone side.
        Steps(registered = false).let { s -> assertEquals(AccountDeletion.Outcome.DELETED, s.deletion().run(false)); assertEquals(listOf("forget"), s.calls) }
    }

    @Test fun ifTheServerFailsNothingOnThePhoneChanges() = runTest {
        Steps(serverError = java.io.IOException("offline")).let { s ->
            assertEquals(AccountDeletion.Outcome.OFFLINE, s.deletion().run(true)); assertEquals(listOf("server"), s.calls)
        }
        Steps(serverError = IllegalStateException("HTTP 500")).let { s ->
            assertEquals(AccountDeletion.Outcome.FAILED, s.deletion().run(true)); assertEquals(listOf("server"), s.calls)
        }
        assertEquals("https://manotu.app/delete/", AccountDeletion.WEB_URL)
    }

    // --- Feedback consent ------------------------------------------------------------------------------------

    @Test fun feedbackScreenshotAndDeviceInfoStartUnticked() {
        assertFalse(FeedbackConsent.DEFAULT_SCREENSHOT)
        assertFalse(FeedbackConsent.DEFAULT_DEVICE)
        assertFalse(my.gimano.app.data.settings.AppSettings().feedbackDevice)
        val d = my.gimano.app.ui.feedback.FeedbackDraft()
        assertFalse(d.includeScreenshot); assertFalse(d.includeDevice)
    }
}
