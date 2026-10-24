package app.whittle.automations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeMaskTest {
    private val mask = CodeMask(testCodeMaskConfig)

    @Test
    fun masksOtpFormats() {
        listOf(
            "123456 is your OTP for txn of Rs 500.00" to "123456",
            "Your verification code is 4821" to "4821",
            "G-123456 is your Google verification code" to "123456",
            "Use 908712 to log in to Swiggy" to "908712",
            "OTP: 7781. Do not share" to "7781",
        ).forEach { (message, digits) ->
            val masked = mask.mask(message)
            assertFalse(digits in masked, masked)
            assertTrue("[code hidden]" in masked, masked)
        }
    }

    @Test
    fun amountsAndDatesSurviveMasking() {
        val message = "Rs 1,250.00 debited from A/c XX1234 on 24-10-26 to VPA x@ybl. Not you? Call 18002586161"
        val masked = mask.mask(message)
        assertTrue("1,250.00" in masked && "24-10-26" in masked, masked)
        // Even beside a keyword, an amount is not a code.
        assertEquals("Rs 12500.00 paid, ref code abc", mask.mask("Rs 12500.00 paid, ref code abc"))
        assertEquals("Rs. 4500 debited, verify it", mask.mask("Rs. 4500 debited, verify it"))
    }

    @Test
    fun deliveryKeepsOrderNumber() {
        val message = "Your order 40781234 is out for delivery"
        assertEquals(message, mask.mask(message))
    }

    @Test
    fun aKeywordFarAwayDoesNotMask() {
        val message = "Your OTP is below. " + "x".repeat(60) + " ticket 55512 ends"
        assertTrue("55512" in mask.mask(message))
    }

    @Test
    fun aLongMessageWithASixDigitNumberAndNoKeywordIsLeftAlone() {
        val message = "The invoice 123456 for the quarterly report was sent to the whole team this morning and they all read it"
        assertEquals(message, mask.mask(message))
    }

    @Test
    fun everyCodeNearAKeywordIsMasked() {
        val masked = mask.mask("OTP 1111 or backup code 2222")
        assertEquals("OTP [code hidden] or backup code [code hidden]", masked)
    }

    @Test
    fun badPatternIsRejected() {
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(codePattern = "(") }
    }
}
